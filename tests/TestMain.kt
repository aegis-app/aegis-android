package jp.aegis.client

import jp.aegis.client.a2s.A2sParse
import jp.aegis.client.a2s.DisplayText
import jp.aegis.client.a2s.EventStore
import jp.aegis.client.a2s.NotifyPlanner
import jp.aegis.client.a2s.SseParser
import jp.aegis.client.a2s.reconnectBackoffMs
import jp.aegis.client.a2s.scaleLabel
import jp.aegis.client.json.MiniJson
import jp.aegis.client.places.Places
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
