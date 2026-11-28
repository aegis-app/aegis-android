package jp.aegis.client

import jp.aegis.client.a2s.A2sParse
import jp.aegis.client.a2s.DisplayText
import jp.aegis.client.a2s.EewAreaArrival
import jp.aegis.client.a2s.EewAreaScale
import jp.aegis.client.a2s.EewCountdown
import jp.aegis.client.a2s.EewPayload
import jp.aegis.client.a2s.EventStore
import jp.aegis.client.a2s.NotifyPlanner
import jp.aegis.client.a2s.SseParser
import jp.aegis.client.a2s.reconnectBackoffMs
import jp.aegis.client.a2s.scaleLabel
import jp.aegis.client.json.MiniJson
import jp.aegis.client.news.News
import jp.aegis.client.news.NewsItem
import jp.aegis.client.places.Places
import jp.aegis.client.radar.Radar
import jp.aegis.client.weather.WeatherParse

// ========================================
// 🧪 単体テスト (純粋層のみ。Android には触れない)
// ========================================
// 📝 実行は tests/run-tests.sh。失敗が 1 件でもあれば終了コード 1 で終わる

private var passed = 0 // 通過した件数
private var failed = 0 // 失敗した件数

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) {
    check("$name (expected=$expected actual=$actual)", expected == actual)
}

// 📦 テスト用の地震イベント JSON
private fun quakeJson(updatedAt: String, maxScale: Int = 40, telegramId: String = "TEL1"): String = """
    {"kind":"quake","key":"551:2026/11/26 12:00:00|@36.3,139.0","updatedAt":"$updatedAt",
     "receivedAt":"2026-11-26T03:00:10.000Z","telegramId":"$telegramId",
     "time":"2026/11/26 12:00:00","announcedTime":"2026/11/26 12:03:00","hypocenter":"茨城県南部",
     "magnitude":5.1,"depthKm":50,"maxScale":$maxScale,"tsunamiKind":"Unknown","foreign":false,
     "issueType":"DetailScale","issueCorrect":"","lat":36.0,"lon":140.0,
     "points":[{"pref":"茨城県","name":"水戸市","scale":40,"isArea":false}],
     "prefScales":[{"pref":"茨城県","scale":40}],"originTime":"","tsunamiText":""}
""".trimIndent()

// 📦 テスト用の EEW イベント JSON
private fun eewJson(updatedAt: String, cancel: Boolean = false, estScale: Int = 45, isWarn: Boolean = true): String = """
    {"kind":"eew","key":"eew-2026/11/26 12:00:00@茨城県南部${if (cancel) "-cancel" else ""}",
     "baseKey":"eew-2026/11/26 12:00:00@茨城県南部","updatedAt":"$updatedAt",
     "receivedAt":"2026-11-26T03:00:05.000Z","time":"2026/11/26 12:00:05","originTime":"2026/11/26 12:00:00",
     "cancel":$cancel,"isWarn":$isWarn,"serial":3,"eventId":"20261126120000","isAssumption":false,
     "isFinal":false,"hypocenter":"茨城県南部","estScale":$estScale,"estMagnitude":5.4,
     "lat":36.0,"lon":140.0,"depthKm":50,
     "areaScales":[{"pref":"茨城県","name":"茨城県南部","scale":45,"scaleTo":50}],
     "areaArrivals":[{"pref":"群馬県","arrivalTime":"2026/11/26 12:01:00"}]}
""".trimIndent()

fun main() {
    // ----------------------------------------
    // 🧩 MiniJson
    // ----------------------------------------
    run {
        val v = MiniJson.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":1.5e2}}""")
        val m = MiniJson.obj(v)!!
        eq("json num", 1.0, m["a"])
        eq("json arr size", 3, MiniJson.arr(m["b"])!!.size)
        eq("json nested", 150.0, MiniJson.obj(m["c"])!!["d"])
        eq("json str escape", "a\nb\"c", MiniJson.parse("\"a\\nb\\\"c\""))
        eq("json unicode", "あ", MiniJson.parse("\"\\u3042\""))
        check("json broken is null", MiniJson.parseOrNull("{oops") == null)
        check("json trailing rejected", try {
            MiniJson.parse("{} x"); false
        } catch (e: Exception) {
            true
        })
    }

    // ----------------------------------------
    // 📦 A2sParse: URL・時刻・有効判定
    // ----------------------------------------
    run {
        check("enabled both", A2sParse.isEnabled("https://x", "tok"))
        check("disabled empty token", !A2sParse.isEnabled("https://x", " "))
        eq("normalize trims slashes", "https://x", A2sParse.normalizeBaseUrl(" https://x// "))
        eq(
            "events url with since",
            "https://x/v1/events?limit=50&kind=quake&since=1795662010000",
            A2sParse.eventsUrl("https://x/", "quake", "2026-11-26T03:00:10.000Z", 50)
        )
        eq(
            "events url without since",
            "https://x/v1/events?limit=10",
            A2sParse.eventsUrl("https://x", "", "", 10)
        )
        eq("iso millis", 1795662010000L, A2sParse.parseIsoMillis("2026-11-26T03:00:10.000Z"))
        check("iso offset form", A2sParse.parseIsoMillis("2026-11-26T12:00:10+09:00") == 1795662010000L)
        eq("iso bad is 0", 0L, A2sParse.parseIsoMillis("not-a-date"))
        eq("jst millis", 1795662000000L, A2sParse.parseJstMillis("2026/11/26 12:00:00"))
    }

    // ----------------------------------------
    // 🌍 A2sParse: 地震イベントの変換と検証
    // ----------------------------------------
    run {
        val ev = A2sParse.eventFromJsonString(quakeJson("2026-11-26T03:00:10.000Z"))!!
        eq("quake kind", "quake", ev.kind)
        eq("quake hypocenter", "茨城県南部", ev.quake!!.hypocenter)
        eq("quake magnitude", 5.1, ev.quake!!.magnitude)
        eq("quake points", 1, ev.quake!!.points.size)
        check("quake missing hypocenter invalid", A2sParse.eventFromJsonString(
            quakeJson("2026-11-26T03:00:10.000Z").replace("茨城県南部", "")
        ) == null)
        check("quake missing updatedAt invalid", A2sParse.eventFromJsonString(
            quakeJson("2026-11-26T03:00:10.000Z").replace("\"updatedAt\":\"2026-11-26T03:00:10.000Z\"", "\"updatedAt\":\"\"")
        ) == null)
        check("unknown kind rejected", A2sParse.eventFromJsonString(
            """{"kind":"nope","key":"k","updatedAt":"2026-11-26T03:00:10.000Z"}"""
        ) == null)
        val list = A2sParse.eventsFromJson("""{"events":[${quakeJson("2026-11-26T03:00:10.000Z")}]}""")
        eq("events list size", 1, list.size)
        eq("events broken body empty", 0, A2sParse.eventsFromJson("not json").size)
    }

    // ----------------------------------------
    // 🚨 A2sParse: EEW と鮮度・行動文言
    // ----------------------------------------
    run {
        val ev = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:00:10.000Z"))!!
        eq("eew baseKey", "eew-2026/11/26 12:00:00@茨城県南部", ev.eew!!.baseKey)
        eq("eew scaleTo", 50, ev.eew!!.areaScales[0].scaleTo)
        val reportMs = A2sParse.parseJstMillis("2026/11/26 12:00:05")
        check("eew fresh within 15min", DisplayText.isEewFresh(ev.eew!!, reportMs + 10 * 60_000L))
        check("eew stale after 15min", !DisplayText.isEewFresh(ev.eew!!, reportMs + 16 * 60_000L))
        eq("action strong", "強い揺れに備え、頭を守り安全な場所へ移動してください。", DisplayText.eewActionText(45, true))
        eq("action mid", "やや強い揺れに備えてください。落下物に注意し、頭を守ってください。", DisplayText.eewActionText(40, false))
        eq("action light", "揺れに注意してください。", DisplayText.eewActionText(30, false))
        eq("action unknown warn", "強い揺れに備え、頭を守り安全な場所へ移動してください。", DisplayText.eewActionText(-1, true))
        eq("action unknown forecast", "揺れに注意してください。", DisplayText.eewActionText(-1, false))
    }

    // ----------------------------------------
    // 🗄️ EventStore: 重複排除・マージ・解除
    // ----------------------------------------
    run {
        val store = EventStore()
        val q1 = A2sParse.eventFromJsonString(quakeJson("2026-11-26T03:00:10.000Z"))!!
        eq("ingest new", EventStore.IngestResult.NEW, store.ingest(q1))
        eq("ingest duplicate", EventStore.IngestResult.DUPLICATE, store.ingest(q1))
        val q2 = A2sParse.eventFromJsonString(quakeJson("2026-11-26T03:05:10.000Z", maxScale = 45))!!
        eq("ingest updated", EventStore.IngestResult.UPDATED, store.ingest(q2))
        eq("store keeps one", 1, store.snapshot().size)
        eq("store lastUpdatedAt", "2026-11-26T03:05:10.000Z", store.lastUpdatedAt)
        // EEW は取消でも baseKey で同一視される
        val e1 = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:00:10.000Z"))!!
        val e2 = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:01:10.000Z", cancel = true))!!
        store.ingest(e1)
        eq("eew cancel is update of same identity", EventStore.IngestResult.UPDATED, store.ingest(e2))
        eq("eew still one", 1, store.byKind("eew").size)
        check("latest eew is cancel", store.latestEew()!!.eew!!.cancel)
        // 警報の解除で取り除かれる
        val wJson = """
            {"kind":"warning","key":"warning:080000:082010","updatedAt":"2026-11-26T03:00:10.000Z",
             "receivedAt":"2026-11-26T03:00:10.000Z","officeCode":"080000","prefName":"茨城県",
             "areaCode":"082010","areaName":"茨城県南部","reportDatetime":"2026/11/26 12:00:00",
             "headline":"","warnings":[{"code":"33","name":"大雨警報","status":"発表","severity":3}],
             "maxSeverity":3,"time":"2026/11/26 12:00:00","released":false}
        """.trimIndent()
        val w = A2sParse.eventFromJsonString(wJson)!!
        store.ingest(w)
        eq("warning stored", 1, store.byKind("warning").size)
        val wRel = A2sParse.eventFromJsonString(wJson.replace("\"released\":false", "\"released\":true"))!!
        eq("warning release result", EventStore.IngestResult.RELEASED, store.ingest(wRel))
        eq("warning removed", 0, store.byKind("warning").size)
    }

    // ----------------------------------------
    // 📡 SseParser とバックオフ
    // ----------------------------------------
    run {
        val p = SseParser()
        var frames = p.push("event: quake\ndata: {\"a\":1}\n\n")
        eq("sse one frame", 1, frames.size)
        eq("sse frame type", "quake", frames[0].type)
        frames = p.push(": heartbeat\n\nevent: ready\ndata: {}\n\n")
        eq("sse ready frame", 1, frames.size)
        eq("sse ready type", "ready", frames[0].type)
        // 断片が途中で割れても組み立てられる
        val p2 = SseParser()
        eq("sse partial no frame", 0, p2.push("event: eew\nda").size)
        val f2 = p2.push("ta: x\n\n")
        eq("sse reassembled", 1, f2.size)
        eq("sse reassembled data", "x", f2[0].data)
        // 複数行 data は改行で連結される
        val p3 = SseParser()
        val f3 = p3.push("data: line1\ndata: line2\n\n")
        eq("sse multiline data", "line1\nline2", f3[0].data)
        eq("sse default type", "message", f3[0].type)
        eq("backoff 0", 5_000L, reconnectBackoffMs(0))
        eq("backoff 1", 10_000L, reconnectBackoffMs(1))
        eq("backoff 2", 30_000L, reconnectBackoffMs(2))
        eq("backoff capped", 60_000L, reconnectBackoffMs(9))
    }

    // ----------------------------------------
    // 🔔 NotifyPlanner
    // ----------------------------------------
    run {
        val now = A2sParse.parseJstMillis("2026/11/26 12:05:00")
        val q = A2sParse.eventFromJsonString(quakeJson("2026-11-26T03:00:10.000Z", maxScale = 40))!!
        check("quake above threshold notifies", NotifyPlanner.plan(q, false, true, now, 30, 1) != null)
        check("quake below threshold silent", NotifyPlanner.plan(q, false, true, now, 45, 1) == null)
        check("replay never notifies", NotifyPlanner.plan(q, true, true, now, 30, 1) == null)
        check("duplicate never notifies", NotifyPlanner.plan(q, false, false, now, 30, 1) == null)
        eq("quake plan id", "quake:TEL1", NotifyPlanner.plan(q, false, true, now, 30, 1)!!.id)
        val eew = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:00:10.000Z"))!!
        val eewPlan = NotifyPlanner.plan(eew, false, true, now, 30, 1)
        check("eew warn notifies urgent", eewPlan != null && eewPlan.urgent)
        val eewSmall = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:00:10.000Z", estScale = 30, isWarn = false))!!
        check("eew small forecast silent", NotifyPlanner.plan(eewSmall, false, true, now, 30, 1) == null)
        val eewCancel = A2sParse.eventFromJsonString(eewJson("2026-11-26T03:01:10.000Z", cancel = true))!!
        check("eew cancel notifies", NotifyPlanner.plan(eewCancel, false, true, now, 30, 1) != null)
        check("eew stale silent", NotifyPlanner.plan(eew, false, true, now + 60 * 60_000L, 30, 1) == null)
        val tJson = """
            {"kind":"tsunami","key":"552:TSU1","updatedAt":"2026-11-26T03:00:10.000Z",
             "receivedAt":"2026-11-26T03:00:10.000Z","time":"2026/11/26 12:00:00","cancelled":false,
             "grade":"注意報","areas":[{"name":"茨城県","grade":"津波注意報","maxHeightLabel":"1m",
             "maxHeightValue":1.0,"arrivalLabel":"到達","firstHeightCondition":""}]}
        """.trimIndent()
        val t = A2sParse.eventFromJsonString(tJson)!!
        val tPlan = NotifyPlanner.plan(t, false, true, now, 30, 1)
        check("tsunami notifies urgent", tPlan != null && tPlan.urgent)
        eq("tsunami plan id strips prefix", "tsunami:TSU1", tPlan!!.id)
        val wJson = """
            {"kind":"warning","key":"warning:080000:082010","updatedAt":"2026-11-26T03:00:10.000Z",
             "receivedAt":"2026-11-26T03:00:10.000Z","officeCode":"080000","prefName":"茨城県",
             "areaCode":"082010","areaName":"茨城県南部","reportDatetime":"2026/11/26 12:00:00",
             "headline":"","warnings":[{"code":"33","name":"大雨警報","status":"発表","severity":3}],
             "maxSeverity":3,"time":"2026/11/26 12:00:00","released":false}
        """.trimIndent()
        val w = A2sParse.eventFromJsonString(wJson)!!
        check("warning alerts-only notifies severity3", NotifyPlanner.plan(w, false, true, now, 30, 1) != null)
        check("warning off silent", NotifyPlanner.plan(w, false, true, now, 30, 0) == null)
        val typoJson = """
            {"kind":"typhoon","key":"typhoon:15","updatedAt":"2026-11-26T03:00:10.000Z",
             "receivedAt":"2026-11-26T03:00:10.000Z","number":"15","name":"台風第15号",
             "englishName":"TYPHOON","lines":["台風第15号が発生しました"],"fields":{},"time":"2026/11/26 12:00:00"}
        """.trimIndent()
        val typo = A2sParse.eventFromJsonString(typoJson)!!
        check("typhoon display only", NotifyPlanner.plan(typo, false, true, now, 30, 2) == null)
        eq("typhoon body uses lines", "台風第15号が発生しました", DisplayText.typhoonBody(typo.typhoon))
    }

    // ----------------------------------------
    // 📝 DisplayText / scaleLabel / 共通種別
    // ----------------------------------------
    run {
        eq("scale 45", "5弱", scaleLabel(45))
        eq("scale 70", "7", scaleLabel(70))
        eq("scale unknown", "不明", scaleLabel(-1))
        val q = A2sParse.eventFromJsonString(quakeJson("2026-11-26T03:00:10.000Z"))!!
        check("quake body has hypocenter", DisplayText.body(q).contains("茨城県南部"))
        check("quake body depth", DisplayText.body(q).contains("約50km"))
        val g = A2sParse.eventFromJsonString(
            """{"kind":"tornado","key":"t1","updatedAt":"2026-11-26T03:00:10.000Z",
                "receivedAt":"2026-11-26T03:00:10.000Z","title":"竜巻注意情報",
                "lines":["群馬県竜巻注意情報 第1号"],"time":"2026/11/26 12:00:00","released":false}"""
        )!!
        eq("generic kind parsed", "tornado", g.kind)
        check("generic body has line", DisplayText.body(g).contains("群馬県竜巻注意情報"))
        check("generic never notifies", NotifyPlanner.plan(g, false, true, 0L, 10, 2) == null)
    }

    // ----------------------------------------
    // 📍 Places: 都道府県テーブル・距離・座標検証・区域解析
    // ----------------------------------------
    run {
        eq("47 prefectures", 47, Places.PREFS.size)
        eq("prefName gunma", "群馬県", Places.prefNameForCode("100000"))
        eq("prefName tokyo", "東京都", Places.prefNameForCode("130000"))
        eq("prefName unknown", "", Places.prefNameForCode("999999"))
        eq("prefName null", "", Places.prefNameForCode(null))
        // 球面距離: 高崎〜大宮は約 72.5km
        val distKm = Places.haversineKm(36.3219, 139.0023, 35.9064, 139.6239)
        check("haversine takasaki-omiya ~72.5km", distKm > 68 && distKm < 77)
        eq("nearestPref takasaki", "100000", Places.nearestPref(36.3219, 139.0023).code)
        eq("nearestPref omiya", "110000", Places.nearestPref(35.9064, 139.6239).code)
        eq("nearestPref tokyo", "130000", Places.nearestPref(35.6895, 139.6917).code)
        // 座標の範囲チェック
        eq("coord ok", "", Places.coordRangeError(35.0, 139.0))
        eq("coord lat range", "緯度は -90〜90 の範囲で入力してください", Places.coordRangeError(91.0, 139.0))
        eq("coord lon range", "経度は -180〜180 の範囲で入力してください", Places.coordRangeError(35.0, 181.0))
        eq("coord NaN", "緯度・経度が不正です", Places.coordRangeError(Double.NaN, 139.0))
        eq("japan warning inside", "", Places.coordJapanWarning(35.0, 139.0))
        check("japan warning outside", Places.coordJapanWarning(0.0, 0.0).isNotEmpty())
        // 予報 JSON の一次細分区域 (名称 + コード、重複名は畳む)
        val forecastJson = """[{"timeSeries":[{"areas":[
          {"area":{"name":"南部","code":"100010"},"weatherCodes":["200"]},
          {"area":{"name":"北部","code":"100020"},"weatherCodes":["100"]},
          {"area":{"name":"南部","code":"100010"},"weatherCodes":["200"]}]}]}]"""
        val fAreas = Places.parseForecastAreas(forecastJson)
        eq("forecast areas count", 3, fAreas.size)
        eq("forecast area first name", "南部", fAreas[0].name)
        eq("forecast area first code", "100010", fAreas[0].code)
        eq("forecast area names dedup", listOf("南部", "北部"), Places.forecastAreaNames(forecastJson))
        eq("forecast areas broken", 0, Places.parseForecastAreas("{}").size)
    }

    // ----------------------------------------
    // 🌧️ Radar: タイル計算・フレーム解析・鮮度
    // ----------------------------------------
    run {
        eq("tileX takasaki z8", 226, Radar.tileX(139.0023, 8))
        eq("tileY takasaki z8", 100, Radar.tileY(36.3219, 8))
        check("tileX clamp", Radar.tileX(-180.0, 8) == 0 && Radar.tileX(180.0, 8) == 255)
        // 雨タイル (z7) のカバー範囲: 中心タイルが偶数なら 2×2 枚・オフセット -256/256
        val rainEven = Radar.rainTilesFor(225, 99)
        eq("rainTiles even count", 4, rainEven.size)
        check(
            "rainTiles even set",
            rainEven.any { it.contentEquals(intArrayOf(112, 49, -256, -256)) } &&
                rainEven.any { it.contentEquals(intArrayOf(113, 50, 256, 256)) }
        )
        // 中心タイルが奇数ならオフセット 0/512
        val rainOdd = Radar.rainTilesFor(226, 100)
        check(
            "rainTiles odd set",
            rainOdd.any { it.contentEquals(intArrayOf(113, 50, 0, 0)) } &&
                rainOdd.any { it.contentEquals(intArrayOf(114, 51, 512, 512)) }
        )
        // マーカー位置: 高崎は中心タイル (226,100) の中にある
        val marker = Radar.markerPixel(36.3219, 139.0023, 8, 225, 99, 256)
        check("marker inside canvas", marker[0] in 256f..512f && marker[1] in 256f..512f)
        // 鮮度 (フレーム時刻は 2026/11/26 12:00:00 JST)
        val frameSec = 1795662000L
        eq("radar age 10min", 10L, Radar.radarAgeMinutes(frameSec, frameSec * 1000L + 10 * 60_000L))
        eq("radar age future is 0", 0L, Radar.radarAgeMinutes(frameSec, frameSec * 1000L - 60_000L))
        eq("freshness fresh", "約5分前の観測", Radar.radarFreshnessText(frameSec, frameSec * 1000L + 5 * 60_000L))
        eq("freshness at threshold", "約20分前の観測", Radar.radarFreshnessText(frameSec, frameSec * 1000L + 20 * 60_000L))
        val staleText = Radar.radarFreshnessText(frameSec, frameSec * 1000L + 21 * 60_000L)
        check("freshness stale note", staleText.startsWith("約21分前の観測") && staleText.contains("古い観測です"))
        // URL 構築
        eq(
            "jma tile url",
            "https://www.jma.go.jp/bosai/jmatile/data/nowc/20261126120000/none/20261126120000/surf/hrpns/8/226/100.png",
            Radar.jmaNowcastTileUrl("20261126120000", "20261126120000", 8, 226, 100)
        )
        eq(
            "gsi tile url",
            "https://cyberjapandata.gsi.go.jp/xyz/std/8/226/100.png",
            Radar.gsiTileUrl(8, 226, 100)
        )
        eq(
            "rainviewer tile url",
            "https://tilecache.rainviewer.com/v2/radar/1795662000/256/7/113/50/2/1_1.png",
            Radar.rainViewerTileUrl("/v2/radar/1795662000", 113, 50)
        )
        // フレーム解析
        val tt = """[{"basetime":"20261126115500","validtime":"20261126120000"},
                     {"basetime":"20261126115000","validtime":"20261126115500"}]"""
        val frame = Radar.parseJmaNowcastLatest(tt)!!
        eq("jma latest basetime", "20261126115500", frame.basetime)
        eq("jma latest validtime", "20261126120000", frame.validtime)
        check("jma latest broken", Radar.parseJmaNowcastLatest("{}") == null)
        eq("jma time14 epoch", 1795662000L, Radar.jmaTime14ToEpochSec("20261126120000"))
        eq("jma time14 invalid", 0L, Radar.jmaTime14ToEpochSec("x"))
        val rv = """{"radar":{"past":[{"path":"/v2/radar/1795658400","time":1795658400},
                                      {"path":"/v2/radar/1795662000","time":1795662000}]}}"""
        val rain = Radar.parseRainViewerLatest(rv)!!
        eq("rainviewer latest path", "/v2/radar/1795662000", rain.path)
        eq("rainviewer latest time", 1795662000L, rain.epochSec)
        check("rainviewer broken", Radar.parseRainViewerLatest("{}") == null)
        // タイル欠落の注記
        eq("missing note complete", "", Radar.tileMissingNote("レーダータイル", 9, 9))
        eq("missing note total loss", "", Radar.tileMissingNote("レーダータイル", 9, 0))
        check("missing note partial", Radar.tileMissingNote("レーダータイル", 9, 7).contains("7/9枚"))
        eq("missing note zero expected", "", Radar.tileMissingNote("地図タイル", 0, 0))
    }

    // ----------------------------------------
    // ⏱️ EewCountdown: 到達推定・地域照合・カウントダウン
    // ----------------------------------------
    run {
        val takasaki = jp.aegis.client.weather.DEFAULT_PLACES[0]
        fun mkEew(
            originTime: String, lat: Double?, lon: Double?, depthKm: Int?,
            arrivals: List<EewAreaArrival> = emptyList(),
            scales: List<EewAreaScale> = emptyList(), cancel: Boolean = false
        ) = EewPayload(
            baseKey = "k", time = originTime, originTime = originTime, cancel = cancel,
            isWarn = true, serial = 1, eventId = null, isAssumption = false, isFinal = false,
            hypocenter = "テスト震源", estScale = 45, estMagnitude = 5.0,
            lat = lat, lon = lon, depthKm = depthKm, areaScales = scales, areaArrivals = arrivals
        )
        // S 波推定: 震源直上 (震央距離 0)・深さ 35km → 35/3.5 = 10 秒後
        val est = BooleanArray(1)
        val swEew = mkEew("1970/01/01 09:16:40", takasaki.lat, takasaki.lon, 35)
        eq("arrival S-wave estimate", 1_010_000L, EewCountdown.arrivalMillis(swEew, takasaki, est))
        check("arrival S-wave flagged estimated", est[0])
        // 到達予想時刻があればそれを優先する
        val arrEew = mkEew(
            "2026/11/26 12:00:00", null, null, null,
            arrivals = listOf(EewAreaArrival("群馬県", "2026/11/26 12:01:00"))
        )
        val expectedArr = A2sParse.parseJstMillis("2026/11/26 12:01:00")
        eq("arrival uses area arrivalTime", expectedArr, EewCountdown.arrivalMillis(arrEew, takasaki, est))
        check("arrival from table not estimated", !est[0])
        // 情報が無ければ -1
        val noEew = mkEew("2026/11/26 12:00:00", null, null, null)
        eq("arrival unknown is -1", -1L, EewCountdown.arrivalMillis(noEew, takasaki, est))
        // カウントダウンの活性判定: 到達前は true、到達 +30 秒を過ぎたら false、取消は false
        check("countdown active before arrival", EewCountdown.countdownActive(arrEew, listOf(takasaki), expectedArr - 5000))
        check("countdown ends after +30s", !EewCountdown.countdownActive(arrEew, listOf(takasaki), expectedArr + 31000))
        val cancelEew = mkEew(
            "2026/11/26 12:00:00", null, null, null,
            arrivals = listOf(EewAreaArrival("群馬県", "2026/11/26 12:01:00")), cancel = true
        )
        check("countdown inactive on cancel", !EewCountdown.countdownActive(cancelEew, listOf(takasaki), expectedArr - 5000))
        // 地域照合: 都道府県で絞り、区域名の一致を優先する
        val scaleEew = mkEew(
            "2026/11/26 12:00:00", null, null, null,
            scales = listOf(
                EewAreaScale("群馬県", "群馬県北部", 30, null),
                EewAreaScale("群馬県", "群馬県南部", 40, null),
                EewAreaScale("埼玉県", "埼玉県南部", 45, null)
            )
        )
        eq("area match by areaName", "群馬県南部", EewCountdown.eewAreaForLocation(scaleEew, takasaki)?.name)
        val noAreaPlace = jp.aegis.client.weather.Place("高崎", 36.32, 139.00, "100000", "")
        eq("area fallback is max scale in pref", "群馬県南部", EewCountdown.eewAreaForLocation(scaleEew, noAreaPlace)?.name)
        check(
            "local forecast text",
            EewCountdown.localForecastText(scaleEew, listOf(takasaki))
                .contains("高崎: 予想震度 4（推定・対象地域「群馬県南部」）")
        )
        // カウントダウン本文: 残り秒数と到達後の文言
        val bodyBefore = EewCountdown.countdownBody(arrEew, listOf(takasaki), expectedArr - 5000)
        check("countdown body seconds", bodyBefore.contains("⏱ 高崎: あと約5秒"))
        val bodyAfter = EewCountdown.countdownBody(arrEew, listOf(takasaki), expectedArr + 10000)
        check("countdown body arrived", bodyAfter.contains("⏱ 高崎: 到達した可能性が高いです"))
    }

    // ----------------------------------------
    // 📰 News: RSS 解析・要約整形・選別
    // ----------------------------------------
    run {
        eq("summary strips html", "abc def", News.truncateSummary("<p>abc</p>  def", 80))
        val truncated = News.truncateSummary("あ".repeat(100), 80)
        check("summary truncates with ellipsis", truncated.length == 81 && truncated.endsWith("…"))
        eq("summary null is empty", "", News.truncateSummary(null, 80))
        eq("pubDate to JST time", "12:04", News.pubDateToTimeText("Sat, 28 Nov 2026 03:04:05 +0000"))
        eq("pubDate broken is empty", "", News.pubDateToTimeText("not-a-date"))
        val rss = """<?xml version="1.0" encoding="UTF-8"?>
        <rss><channel>
        <item><title>関東で地震 震度4</title><link>https://example.jp/1</link>
        <description><![CDATA[<p>関東地方で地震がありました。震度4を観測。</p>]]></description>
        <pubDate>Sat, 28 Nov 2026 03:04:05 +0000</pubDate></item>
        <item><title>大リーグ 大谷が本塁打</title><link>https://example.jp/2</link>
        <description>大谷選手が&amp;本塁打を放った</description>
        <pubDate>Sat, 28 Nov 2026 02:00:00 +0000</pubDate></item>
        </channel></rss>"""
        val parsed = News.parseRss(rss)
        eq("rss item count", 2, parsed.size)
        eq("rss title", "関東で地震 震度4", parsed[0].title)
        eq("rss time", "12:04", parsed[0].timeText)
        eq("rss summary cdata stripped", "関東地方で地震がありました。震度4を観測。", parsed[0].summary)
        eq("rss entity unescaped", "大谷選手が&本塁打を放った", parsed[1].summary)
        check("rss disaster detection", News.isDisaster(parsed[0]) && !News.isDisaster(parsed[1]))
        check("rss broken is empty", News.parseRss("not xml").isEmpty())
        // 選別: リンク重複の排除と防災優先
        fun item(title: String, link: String) = NewsItem(title, "", "", link)
        val sel = News.selectItems(
            listOf(item("通常ニュース", "https://example.jp/a"), item("地震が発生", "https://example.jp/b")),
            listOf(item("重複", "https://example.jp/b"), item("リンクなし", ""))
        )
        eq("select dedupes and drops empty link", 2, sel.size)
        eq("select disaster first", "地震が発生", sel[0].title)
        // 防災 5 件 + 一般 5 件 → 一般は混ざらない (防災>=4 かつ合計>=4 で打ち切り)
        val many = News.selectItems(
            (1..5).map { item("地震その$it", "https://example.jp/d$it") },
            (1..5).map { item("一般その$it", "https://example.jp/g$it") }
        )
        eq("select disaster>=4 blocks general fill", 5, many.size)
        // 防災 2 件 + 一般 10 件 → 合計 8 件まで一般で埋める
        val filled = News.selectItems(
            (1..2).map { item("台風その$it", "https://example.jp/d$it") },
            (1..10).map { item("一般その$it", "https://example.jp/g$it") }
        )
        eq("select fills up to 8", 8, filled.size)
        // 防災 10 件 → 8 件に切り詰める
        val capped = News.selectItems((1..10).map { item("大雨その$it", "https://example.jp/d$it") }, emptyList())
        eq("select caps at 8", 8, capped.size)
    }

    // ----------------------------------------
    // 🌤️ WeatherParse
    // ----------------------------------------
    run {
        val jma = """
            [{"publishingOffice":"前橋地方気象台","reportDatetime":"2026-11-26T11:00:00+09:00",
              "timeSeries":[
                {"timeDefines":["2026-11-26T11:00:00+09:00"],
                 "areas":[{"area":{"name":"南部","code":"100010"},"weathers":["晴れ　時々　くもり"],"pops":["10","20"],"temps":["5","15"]}]},
                {"timeDefines":["2026-11-26T17:00:00+09:00"],
                 "areas":[{"area":{"name":"南部","code":"100010"},"tempsMin":["4"],"tempsMax":["14"]}]}
              ]}]
        """.trimIndent()
        val f = WeatherParse.parseJmaForecast(jma, "100010")!!
        check("jma weather text", f.weatherText.contains("晴れ"))
        eq("jma max temp", "15", f.maxTemp)
        check("jma pop text", f.popText!!.contains("10"))
        check("jma wrong area null", WeatherParse.parseJmaForecast(jma, "999999") == null)
        val om = """{"current":{"temperature_2m":18.2,"relative_humidity_2m":65,"weather_code":0}}"""
        eq("open-meteo current", "18.2℃ / 湿度65%", WeatherParse.parseOpenMeteoCurrent(om))
        check("open-meteo broken null", WeatherParse.parseOpenMeteoCurrent("{}") == null)
    }

    println("tests: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
