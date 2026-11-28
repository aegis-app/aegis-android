package jp.aegis.client.a2s

import jp.aegis.client.json.MiniJson
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ========================================
// 📦 A.A.S イベント JSON の解析と検証
// ========================================
// 📌 MiniJson の値 (Map / List) から A2sEvent へ変換する純粋関数の集まり。
// 形が崩れたイベントは null にして弾く (変換の失敗と上流の差異を区別する
// ため、必須フィールドの検証を変換前に置くのが旧実装からの継承事項)
object A2sParse {

    // ⚙️ A.A.S が有効か: ベース URL とトークンの両方が空でないときだけ有効
    fun isEnabled(baseUrl: String, token: String): Boolean =
        baseUrl.trim().isNotEmpty() && token.trim().isNotEmpty()

    // ⚙️ ベース URL の正規化: 前後の空白と末尾のスラッシュを取り除く
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        return s
    }

    // 🕐 ISO 8601 時刻 (updatedAt / receivedAt) を epoch ミリ秒にする (失敗時 0)。
    // "2026-11-26T00:00:10.000Z" を基本に、ミリ秒なし・オフセット表記も受ける
    fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrEmpty()) return 0
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                val d = fmt.parse(iso)
                if (d != null) return d.time
            } catch (e: Exception) {
                // 📝 次の形式を試す
            }
        }
        return 0
    }

    // 🕐 JST 表記 "2026/11/26 12:34:56" (秒なしも可) を epoch ミリ秒にする (失敗時 0)
    fun parseJstMillis(text: String?): Long {
        if (text.isNullOrEmpty()) return 0
        val patterns = arrayOf("yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                val d = fmt.parse(text)
                if (d != null) return d.time
            } catch (e: Exception) {
                // 📝 次の形式を試す
            }
        }
        return 0
    }

    // 🔗 GET /v1/events の URL を組み立てる。since は ISO 文字列を epoch ミリ秒へ
    // 変換して渡す (A.A.S の since= はミリ秒指定)。変換できない since は付けない
    fun eventsUrl(baseUrl: String, kind: String = "", sinceIso: String = "", limit: Int = 50): String {
        val sb = StringBuilder(normalizeBaseUrl(baseUrl)).append("/v1/events?limit=").append(limit)
        if (kind.isNotEmpty()) sb.append("&kind=").append(kind)
        if (sinceIso.isNotEmpty()) {
            val ms = parseIsoMillis(sinceIso)
            if (ms > 0) sb.append("&since=").append(ms)
        }
        return sb.toString()
    }

    // 🔗 GET /v1/stream (SSE) の URL
    fun streamUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/v1/stream"

    // 🔗 GET /v1/status の URL (接続テスト用)
    fun statusUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/v1/status"

    // ----------------------------------------
    // 📦 JSON → A2sEvent 変換
    // ----------------------------------------

    // 📥 イベント JSON テキスト (SSE フレームの data 等) を 1 件のイベントへ変換する
    fun eventFromJsonString(json: String): A2sEvent? {
        val root = MiniJson.obj(MiniJson.parseOrNull(json)) ?: return null
        return eventFromMap(root)
    }

    // 📥 GET /v1/events の応答 {"events": [...]} をイベント一覧へ変換する。
    // 形式が崩れた応答は空の一覧として扱う
    fun eventsFromJson(body: String): List<A2sEvent> {
        val root = MiniJson.obj(MiniJson.parseOrNull(body)) ?: return emptyList()
        val arr = MiniJson.arr(root["events"]) ?: return emptyList()
        val out = ArrayList<A2sEvent>()
        for (item in arr) {
            val m = MiniJson.obj(item) ?: continue
            val ev = eventFromMap(m)
            if (ev != null) out.add(ev)
        }
        return out
    }

    // 📥 値マップ 1 件をイベントへ変換する。検証に落ちたものは null
    fun eventFromMap(item: Map<String, Any?>): A2sEvent? {
        val kind = MiniJson.str(item, "kind")
        val key = MiniJson.str(item, "key")
        val updatedAt = MiniJson.str(item, "updatedAt")
        val receivedAt = MiniJson.str(item, "receivedAt")
        val ev = when (kind) {
            "quake" -> A2sEvent(kind, key, updatedAt, receivedAt, quake = parseQuake(item, key))
            "eew" -> A2sEvent(kind, key, updatedAt, receivedAt, eew = parseEew(item, key))
            "tsunami" -> A2sEvent(kind, key, updatedAt, receivedAt, tsunami = parseTsunami(item))
            "warning" -> A2sEvent(kind, key, updatedAt, receivedAt, warning = parseWarning(item))
            "typhoon" -> A2sEvent(kind, key, updatedAt, receivedAt, typhoon = parseTyphoon(item))
            in A2S_KINDS -> A2sEvent(kind, key, updatedAt, receivedAt, generic = parseGeneric(item))
            else -> return null
        }
        return if (isValid(ev)) ev else null
    }

    // 🔍 受信イベントの最小検証: 封筒の必須項目と、種別ごとの同一視に必要な項目
    fun isValid(ev: A2sEvent): Boolean {
        if (ev.key.isEmpty() || ev.updatedAt.isEmpty()) return false
        return when (ev.kind) {
            "quake" -> {
                val q = ev.quake ?: return false
                q.time.isNotEmpty() && q.hypocenter.isNotEmpty()
            }
            "eew" -> {
                val e = ev.eew ?: return false
                e.baseKey.isNotEmpty() && e.originTime.isNotEmpty()
            }
            "tsunami" -> ev.tsunami != null
            "warning" -> {
                val w = ev.warning ?: return false
                w.officeCode.isNotEmpty() && w.areaCode.isNotEmpty()
            }
            "typhoon" -> ev.typhoon != null
            in A2S_KINDS -> ev.generic != null
            else -> false
        }
    }

    // 🌍 地震ペイロードの切り出し
    private fun parseQuake(item: Map<String, Any?>, key: String): QuakePayload {
        val points = ArrayList<QuakePoint>()
        for (p in MiniJson.arr(item["points"]).orEmpty()) {
            val m = MiniJson.obj(p) ?: continue
            points.add(
                QuakePoint(
                    MiniJson.str(m, "pref"), MiniJson.str(m, "name"),
                    MiniJson.int(m, "scale", 0), MiniJson.bool(m, "isArea")
                )
            )
        }
        val prefScales = ArrayList<PrefScale>()
        for (p in MiniJson.arr(item["prefScales"]).orEmpty()) {
            val m = MiniJson.obj(p) ?: continue
            prefScales.add(PrefScale(MiniJson.str(m, "pref"), MiniJson.int(m, "scale", 0)))
        }
        val lat = MiniJson.numOrNull(item, "lat")
        val lon = MiniJson.numOrNull(item, "lon")
        val latOk = lat != null && lat in -90.0..90.0 && lat != 0.0
        val lonOk = lon != null && lon in -180.0..180.0 && lon != 0.0
        return QuakePayload(
            telegramId = MiniJson.str(item, "telegramId").ifEmpty { key },
            time = MiniJson.str(item, "time").ifEmpty { MiniJson.str(item, "originTime") },
            announcedTime = MiniJson.str(item, "announcedTime").ifEmpty { MiniJson.str(item, "time") },
            hypocenter = MiniJson.str(item, "hypocenter").trim(),
            magnitude = MiniJson.numOrNull(item, "magnitude"),
            depthKm = MiniJson.intOrNull(item, "depthKm"),
            maxScale = MiniJson.int(item, "maxScale", -1),
            tsunamiKind = MiniJson.str(item, "tsunamiKind").ifEmpty { "Unknown" },
            foreign = MiniJson.bool(item, "foreign"),
            issueType = MiniJson.str(item, "issueType"),
            issueCorrect = MiniJson.str(item, "issueCorrect"),
            lat = if (latOk && lonOk) lat else null,
            lon = if (latOk && lonOk) lon else null,
            points = points,
            prefScales = prefScales,
            originTime = MiniJson.str(item, "originTime"),
            tsunamiText = MiniJson.strOrNull(item, "tsunamiText") ?: ""
        )
    }

    // 🚨 EEW ペイロードの切り出し
    private fun parseEew(item: Map<String, Any?>, key: String): EewPayload {
        val areaScales = ArrayList<EewAreaScale>()
        for (a in MiniJson.arr(item["areaScales"]).orEmpty()) {
            val m = MiniJson.obj(a) ?: continue
            areaScales.add(
                EewAreaScale(
                    MiniJson.str(m, "pref"), MiniJson.str(m, "name"),
                    MiniJson.int(m, "scale", -1), MiniJson.intOrNull(m, "scaleTo")
                )
            )
        }
        val areaArrivals = ArrayList<EewAreaArrival>()
        for (a in MiniJson.arr(item["areaArrivals"]).orEmpty()) {
            val m = MiniJson.obj(a) ?: continue
            areaArrivals.add(EewAreaArrival(MiniJson.str(m, "pref"), MiniJson.str(m, "arrivalTime")))
        }
        return EewPayload(
            baseKey = MiniJson.str(item, "baseKey").ifEmpty { key },
            time = MiniJson.str(item, "time"),
            originTime = MiniJson.str(item, "originTime"),
            cancel = MiniJson.bool(item, "cancel"),
            isWarn = MiniJson.bool(item, "isWarn"),
            serial = MiniJson.intOrNull(item, "serial"),
            eventId = MiniJson.strOrNull(item, "eventId"),
            isAssumption = MiniJson.bool(item, "isAssumption"),
            isFinal = MiniJson.bool(item, "isFinal"),
            hypocenter = MiniJson.str(item, "hypocenter"),
            estScale = MiniJson.int(item, "estScale", -1),
            estMagnitude = MiniJson.numOrNull(item, "estMagnitude"),
            lat = MiniJson.numOrNull(item, "lat"),
            lon = MiniJson.numOrNull(item, "lon"),
            depthKm = MiniJson.intOrNull(item, "depthKm"),
            areaScales = areaScales,
            areaArrivals = areaArrivals
        )
    }

    // 🌊 津波ペイロードの切り出し
    private fun parseTsunami(item: Map<String, Any?>): TsunamiPayload {
        val areas = ArrayList<TsunamiArea>()
        for (a in MiniJson.arr(item["areas"]).orEmpty()) {
            val m = MiniJson.obj(a) ?: continue
            areas.add(
                TsunamiArea(
                    name = MiniJson.str(m, "name"),
                    grade = MiniJson.str(m, "grade"),
                    maxHeightLabel = MiniJson.strOrNull(m, "maxHeightLabel"),
                    maxHeightValue = MiniJson.numOrNull(m, "maxHeightValue"),
                    arrivalLabel = MiniJson.strOrNull(m, "arrivalLabel"),
                    firstHeightCondition = MiniJson.strOrNull(m, "firstHeightCondition")
                )
            )
        }
        return TsunamiPayload(
            time = MiniJson.str(item, "time"),
            cancelled = MiniJson.bool(item, "cancelled"),
            grade = MiniJson.str(item, "grade"),
            areas = areas
        )
    }

    // ⚠️ 警報・注意報ペイロードの切り出し
    private fun parseWarning(item: Map<String, Any?>): WarningPayload {
        val items = ArrayList<WarningItem>()
        for (w in MiniJson.arr(item["warnings"]).orEmpty()) {
            val m = MiniJson.obj(w) ?: continue
            items.add(
                WarningItem(
                    MiniJson.str(m, "code"), MiniJson.str(m, "name"),
                    MiniJson.str(m, "status"), MiniJson.int(m, "severity", 0)
                )
            )
        }
        return WarningPayload(
            officeCode = MiniJson.str(item, "officeCode"),
            prefName = MiniJson.str(item, "prefName"),
            areaCode = MiniJson.str(item, "areaCode"),
            areaName = MiniJson.str(item, "areaName"),
            reportDatetime = MiniJson.str(item, "reportDatetime"),
            headline = MiniJson.str(item, "headline"),
            warnings = items,
            maxSeverity = MiniJson.int(item, "maxSeverity", 0),
            time = MiniJson.str(item, "time"),
            released = MiniJson.bool(item, "released")
        )
    }

    // 🌀 台風ペイロードの切り出し
    private fun parseTyphoon(item: Map<String, Any?>): TyphoonPayload {
        val lines = ArrayList<String>()
        for (v in MiniJson.arr(item["lines"]).orEmpty()) {
            val s = v as? String ?: continue
            if (s.isNotEmpty()) lines.add(s)
        }
        val fields = LinkedHashMap<String, String>()
        val fobj = MiniJson.obj(item["fields"])
        if (fobj != null) {
            for ((k, v) in fobj) {
                if (v is String || v is Double || v is Boolean) fields[k] = v.toString()
            }
        }
        return TyphoonPayload(
            number = MiniJson.strOrNull(item, "number"),
            name = MiniJson.str(item, "name"),
            englishName = MiniJson.str(item, "englishName"),
            lines = lines,
            fields = fields,
            time = MiniJson.str(item, "time")
        )
    }

    // 📄 共通ペイロード (詳細モデルを持たない種別) の切り出し
    private fun parseGeneric(item: Map<String, Any?>): GenericPayload {
        val lines = ArrayList<String>()
        for (v in MiniJson.arr(item["lines"]).orEmpty()) {
            val s = v as? String ?: continue
            if (s.isNotEmpty()) lines.add(s)
        }
        return GenericPayload(
            title = MiniJson.str(item, "title").ifEmpty { MiniJson.str(item, "headline") },
            lines = lines,
            time = MiniJson.str(item, "time"),
            released = MiniJson.bool(item, "released")
        )
    }
}
