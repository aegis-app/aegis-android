package jp.aegis.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import jp.aegis.client.a2s.A2sEvent
import jp.aegis.client.a2s.A2sHttp
import jp.aegis.client.a2s.A2sParse
import jp.aegis.client.a2s.A2sStreamClient
import jp.aegis.client.a2s.DisplayText
import jp.aegis.client.a2s.EventStore
import jp.aegis.client.a2s.NotifyPlanner
import jp.aegis.client.news.News
import jp.aegis.client.places.Places
import jp.aegis.client.places.Pref
import jp.aegis.client.radar.RadarComposer
import jp.aegis.client.weather.Place
import jp.aegis.client.weather.WeatherParse

// ========================================
// 📱 MainActivity (画面: ブリーフィング / イベント / 設定)
// ========================================
// 📌 UI はプラットフォームの View だけで組み立てる (Compose は使わない。
// 理由は DESIGN.md の判断記録)。データの入口は A.A.S:
// - 表示中は SSE (GET /v1/stream) で受け、切断中は REST で回収する
// - A.A.S 未設定・到達不能のときは地震系と警報・台風の情報は出ない (仕様)。
// 天気予報など A.A.S 対象外の機能はそのまま使える
class MainActivity : Activity() {

    // 🗄️ 受信イベントの状態 (SSE と REST の両方がここへ取り込む)
    private val store = EventStore()

    // 📡 SSE クライアント (表示中のみ接続)
    private var streamClient: A2sStreamClient? = null

    // 🧱 画面部品
    private lateinit var tabBar: LinearLayout
    private lateinit var content: LinearLayout
    private var currentTab = "briefing" // 現在のタブ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        root.addView(tabBar)
        val scroll = ScrollView(this)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        buildTabBar()
        currentTab = intent?.getStringExtra(Sync.EXTRA_TAB) ?: "briefing"
        showTab(currentTab)
        Sync.scheduleNext(applicationContext)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        val tab = intent?.getStringExtra(Sync.EXTRA_TAB)
        if (tab != null) showTab(tab)
    }

    override fun onStart() {
        super.onStart()
        startStream()
    }

    override fun onStop() {
        streamClient?.stop()
        streamClient = null
        super.onStop()
    }

    // ----------------------------------------
    // 🧭 タブ
    // ----------------------------------------

    private fun buildTabBar() {
        tabBar.removeAllViews()
        for ((id, label) in listOf(
            "briefing" to "📌 ブリーフィング", "events" to "📋 イベント",
            "radar" to "🌧️ レーダー", "news" to "📰 ニュース", "settings" to "⚙️ 設定"
        )) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { showTab(id) }
            tabBar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showTab(id: String) {
        currentTab = id
        content.removeAllViews()
        when (id) {
            "events" -> renderEvents()
            "radar" -> renderRadar()
            "news" -> renderNews()
            "settings" -> renderSettings()
            else -> renderBriefing()
        }
    }

    // ----------------------------------------
    // 🧱 部品の小物
    // ----------------------------------------

    private fun text(s: String, size: Float = 15f, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(16, 8, 16, 8)
        return tv
    }

    private fun card(title: String, body: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(8, 8, 8, 8)
        box.setBackgroundColor(Color.rgb(245, 245, 245))
        box.addView(text(title, 16f, true))
        box.addView(text(body, 14f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(8, 8, 8, 8)
        box.layoutParams = lp
        return box
    }

    // ----------------------------------------
    // 📌 ブリーフィング画面
    // ----------------------------------------

    private fun renderBriefing() {
        content.addView(text("📌 現在のまとめ", 18f, true))
        val summaryBox = LinearLayout(this)
        summaryBox.orientation = LinearLayout.VERTICAL
        content.addView(summaryBox)
        content.addView(text("🌤️ 天気", 18f, true))
        val weatherBox = LinearLayout(this)
        weatherBox.orientation = LinearLayout.VERTICAL
        content.addView(weatherBox)
        loadBriefing(summaryBox, weatherBox)
    }

    // 📥 サマリと天気を並行取得して差し込む (失敗した部分はその旨を表示する)
    private fun loadBriefing(summaryBox: LinearLayout, weatherBox: LinearLayout) {
        val url = Settings.a2sUrl(this)
        val token = Settings.a2sToken(this)
        val places = Settings.places(this)
        Thread {
            // 📡 A.A.S から最新を取り込む (未設定なら地震系は空のまま)
            if (A2sParse.isEnabled(url, token)) {
                try {
                    for (ev in A2sHttp.fetchEvents(url, token, "", Settings.lastSince(this), 50)) {
                        store.ingest(ev)
                    }
                } catch (e: Exception) {
                    // 📝 到達不能時は保持分だけで組み立てる
                }
            }
            val events = store.snapshot()
            val summary = buildSummary(events, places)
            // 🌤️ 天気は地点ごとに取得する
            val weatherCards = ArrayList<Pair<String, String>>()
            for (place in places) {
                weatherCards.add(place.name to fetchWeatherText(place))
            }
            runOnUiThread {
                summaryBox.addView(card("現在の状況", summary))
                // 🚨 新鮮な EEW があれば最上部に固定表示する
                val eew = store.latestEew()?.eew
                if (eew != null && DisplayText.isEewFresh(eew, System.currentTimeMillis())) {
                    val prefs = places.map { prefNameOf(it) }
                    summaryBox.addView(card(DisplayText.eewTitle(eew), DisplayText.eewBody(eew, prefs)), 0)
                }
                for ((name, body) in weatherCards) {
                    weatherBox.addView(card(name, body))
                }
            }
        }.start()
    }

    // 📝 「現在のまとめ」の本文: 警報 / 津波 / 最新地震 / 台風 を 1 枚に集約する
    private fun buildSummary(events: List<A2sEvent>, places: List<Place>): String {
        val sb = StringBuilder()
        val warnings = events.filter { it.kind == "warning" }
        var anyWarning = false
        for (place in places) {
            val hit = warnings.firstOrNull {
                val w = it.warning ?: return@firstOrNull false
                w.officeCode == place.prefCode && (place.areaCode.isEmpty() || w.areaCode == place.areaCode)
            }
            if (hit != null) {
                anyWarning = true
                sb.append(place.name).append(": ")
                    .append(hit.warning!!.warnings.joinToString("、") { w -> w.name })
                    .append('\n')
            }
        }
        if (!anyWarning) sb.append("発表中の警報・注意報はありません\n")
        val tsunami = events.firstOrNull { it.kind == "tsunami" }
        sb.append(
            if (tsunami?.tsunami?.cancelled == false && tsunami.tsunami.areas.isNotEmpty())
                "津波警報・注意報が発表されています\n"
            else "津波警報・注意報はありません\n"
        )
        val quake = events.firstOrNull { it.kind == "quake" }
        if (quake?.quake != null) {
            sb.append("最新地震: ").append(quake.quake.hypocenter)
                .append(" 最大震度 ").append(jp.aegis.client.a2s.scaleLabel(quake.quake.maxScale))
                .append('\n')
        }
        val typhoon = events.firstOrNull { it.kind == "typhoon" }
        if (typhoon?.typhoon != null) {
            sb.append("台風: ").append(DisplayText.typhoonBody(typhoon.typhoon)).append('\n')
        }
        if (!A2sParse.isEnabled(Settings.a2sUrl(this), Settings.a2sToken(this))) {
            sb.append("※ A.A.S が未設定のため、地震・津波・警報・台風は表示されません (設定タブで接続できます)\n")
        }
        return sb.toString().trim()
    }

    // 🌤️ 1 地点の天気テキスト (気象庁予報 + Open-Meteo 現在気象)
    private fun fetchWeatherText(place: Place): String {
        val sb = StringBuilder()
        try {
            val body = A2sHttp.fetchOnce(WeatherParse.jmaForecastUrl(place.prefCode), "")
            val f = WeatherParse.parseJmaForecast(body, place.areaCode)
            if (f != null) {
                sb.append(f.weatherText).append('\n')
                if (f.maxTemp != null || f.minTemp != null) {
                    sb.append("気温 ").append(f.minTemp ?: "?").append("〜").append(f.maxTemp ?: "?").append("℃\n")
                }
                if (f.popText != null) sb.append(f.popText).append('\n')
            }
        } catch (e: Exception) {
            // 📝 予報が取れない場合は現在気象だけでも出す
        }
        try {
            val body = A2sHttp.fetchOnce(WeatherParse.openMeteoUrl(place.lat, place.lon), "")
            val cur = WeatherParse.parseOpenMeteoCurrent(body)
            if (cur != null) sb.append("現在: ").append(cur)
        } catch (e: Exception) {
            // 📝 現在気象は補助情報のため、失敗しても静かに省く
        }
        val s = sb.toString().trim()
        return if (s.isEmpty()) "天気を取得できませんでした" else s
    }

    // 🗾 地点の都道府県名 (EEW 到達予想の照合用)。テーブルに無いコードは地点名で代替する
    private fun prefNameOf(place: Place): String =
        Places.prefNameForCode(place.prefCode).ifEmpty { place.name }

    // ----------------------------------------
    // 📋 イベント画面
    // ----------------------------------------

    private fun renderEvents() {
        content.addView(text("📋 イベント (新しい順)", 18f, true))
        val list = store.snapshot()
        if (list.isEmpty()) {
            content.addView(text("まだイベントがありません。A.A.S の設定と接続を確認してください。", 14f))
            // 📥 未取得なら REST で取りに行く
            val url = Settings.a2sUrl(this)
            val token = Settings.a2sToken(this)
            if (A2sParse.isEnabled(url, token)) {
                Thread {
                    try {
                        for (ev in A2sHttp.fetchEvents(url, token, "", "", 50)) store.ingest(ev)
                        runOnUiThread { if (currentTab == "events") showTab("events") }
                    } catch (e: Exception) {
                        // 📝 失敗時は空表示のまま (次回開いたときに再試行する)
                    }
                }.start()
            }
            return
        }
        for (ev in list) {
            val b = Button(this)
            b.text = DisplayText.title(ev) + "\n" + ev.updatedAt
            b.gravity = Gravity.START
            b.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(DisplayText.title(ev))
                    .setMessage(DisplayText.body(ev))
                    .setPositiveButton("閉じる", null)
                    .show()
            }
            content.addView(b)
        }
    }

    // ----------------------------------------
    // 🌧️ レーダー画面
    // ----------------------------------------

    private var radarPlaceIndex = 0 // レーダーの表示地点の位置

    private fun renderRadar() {
        val places = Settings.places(this)
        content.addView(text("🌧️ 雨雲レーダー", 18f, true))
        // 📍 地点の切り替え
        val selector = LinearLayout(this)
        selector.orientation = LinearLayout.HORIZONTAL
        for ((i, place) in places.withIndex()) {
            val b = Button(this)
            b.text = place.name
            b.setOnClickListener {
                radarPlaceIndex = i
                showTab("radar")
            }
            selector.addView(b)
        }
        content.addView(selector)
        val place = places.getOrNull(radarPlaceIndex) ?: places.firstOrNull()
        if (place == null) {
            content.addView(text("地点が登録されていません。", 14f))
            return
        }
        val caption = text(place.name + " 周辺\n読み込み中…", 13f)
        content.addView(caption)
        val image = ImageView(this)
        image.adjustViewBounds = true
        content.addView(image)
        // 📡 合成は通信と画像処理を伴うためワーカースレッドで行う
        Thread {
            try {
                val result = RadarComposer.radarImage(place)
                runOnUiThread {
                    if (currentTab != "radar") return@runOnUiThread
                    image.setImageBitmap(result.bitmap)
                    caption.text = place.name + " 周辺\n" + result.caption
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (currentTab != "radar") return@runOnUiThread
                    caption.text = place.name + " 周辺\nレーダーを取得できませんでした: " + (e.message ?: "") +
                        "\n通信状態を確認して、タブを開き直してください。"
                }
            }
        }.start()
    }

    // ----------------------------------------
    // 📰 ニュース画面 (NHK RSS)
    // ----------------------------------------

    private fun renderNews() {
        content.addView(text("📰 ニュース (NHK)", 18f, true))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        content.addView(box)
        box.addView(text("読み込み中…", 13f))
        // 📡 RSS の取得はワーカースレッドで行う
        Thread {
            try {
                val items = News.fetchItems()
                runOnUiThread {
                    if (currentTab != "news") return@runOnUiThread
                    box.removeAllViews()
                    for (item in items) {
                        val cardView = card(
                            (if (item.timeText.isNotEmpty()) item.timeText + " " else "") + item.title,
                            item.summary
                        )
                        if (item.link.isNotEmpty()) {
                            cardView.setOnClickListener {
                                try {
                                    startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(item.link)
                                        )
                                    )
                                } catch (e: Exception) {
                                    // ブラウザが無い環境では何もしない
                                }
                            }
                        }
                        box.addView(cardView)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (currentTab != "news") return@runOnUiThread
                    box.removeAllViews()
                    box.addView(card("ニュースを取得できませんでした", "通信状態を確認して、タブを開き直してください。"))
                }
            }
        }.start()
    }

    // ----------------------------------------
    // ⚙️ 設定画面
    // ----------------------------------------

    private fun renderSettings() {
        content.addView(text("📡 A.A.S (Aegis Alert System)", 18f, true))
        content.addView(text("地震・津波・EEW・警報・台風の唯一の情報源です。URL とトークンの両方を保存したときだけ有効になります。", 13f))
        val urlInput = EditText(this)
        urlInput.hint = "A.A.S の URL (例: https://example.ts.net)"
        urlInput.setText(Settings.a2sUrl(this))
        content.addView(urlInput)
        val tokenInput = EditText(this)
        tokenInput.hint = "トークン"
        tokenInput.setText(Settings.a2sToken(this))
        content.addView(tokenInput)
        val status = text("", 13f)
        val saveBtn = Button(this)
        saveBtn.text = "保存"
        saveBtn.setOnClickListener {
            Settings.setA2s(this, urlInput.text.toString(), tokenInput.text.toString())
            store.clear()
            Sync.scheduleNext(applicationContext)
            startStream()
            status.text = "保存しました"
        }
        content.addView(saveBtn)
        val testBtn = Button(this)
        testBtn.text = "接続テスト"
        testBtn.setOnClickListener {
            status.text = "接続中…"
            Thread {
                val msg = try {
                    A2sHttp.checkStatus(urlInput.text.toString(), tokenInput.text.toString())
                    "✅ A.A.S へ接続できました"
                } catch (e: Exception) {
                    "❌ 接続できませんでした (" + e.message + ")"
                }
                runOnUiThread { status.text = msg }
            }.start()
        }
        content.addView(testBtn)
        content.addView(status)

        content.addView(text("🔔 バックグラウンド同期と通知", 18f, true))
        val bgBtn = Button(this)
        fun refreshBgBtn() {
            bgBtn.text = "バックグラウンド同期: " + if (Settings.isBgSyncEnabled(this)) "ON" else "OFF"
        }
        refreshBgBtn()
        bgBtn.setOnClickListener {
            Settings.setBgSyncEnabled(this, !Settings.isBgSyncEnabled(this))
            Sync.scheduleNext(applicationContext)
            refreshBgBtn()
        }
        content.addView(bgBtn)
        content.addView(labeledSpinner("同期間隔", listOf("15分", "30分", "60分"),
            listOf(15, 30, 60).indexOf(Settings.intervalMinutes(this)).coerceAtLeast(0)) { idx ->
            Settings.setIntervalMinutes(this, listOf(15, 30, 60)[idx])
            Sync.scheduleNext(applicationContext)
        })
        content.addView(labeledSpinner("地震の通知しきい値", listOf("震度3以上", "震度4以上", "震度5弱以上"),
            listOf(30, 40, 45).indexOf(Settings.quakeThresholdScale(this)).coerceAtLeast(0)) { idx ->
            Settings.setQuakeThresholdScale(this, listOf(30, 40, 45)[idx])
        })
        content.addView(labeledSpinner("警報の通知", listOf("しない", "警報以上", "注意報から"),
            Settings.warnMode(this)) { idx ->
            Settings.setWarnMode(this, idx)
        })
        val notifyTest = Button(this)
        notifyTest.text = "通知テスト"
        notifyTest.setOnClickListener {
            requestNotificationPermission()
            Sync.postTestNotification(applicationContext)
        }
        content.addView(notifyTest)

        content.addView(text("📍 地点の追加", 18f, true))
        val nameInput = EditText(this); nameInput.hint = "地点名"
        val latInput = EditText(this); latInput.hint = "緯度 (例: 35.68)"
        val lonInput = EditText(this); lonInput.hint = "経度 (例: 139.69)"
        val prefInput = EditText(this); prefInput.hint = "都道府県コード (例: 130000)"
        val areaInput = EditText(this); areaInput.hint = "一次細分区域コード (例: 130010)"
        val areaNameInput = EditText(this); areaNameInput.hint = "一次細分区域名 (例: 南部)"
        content.addView(nameInput); content.addView(latInput); content.addView(lonInput)
        content.addView(prefInput); content.addView(areaInput); content.addView(areaNameInput)
        val placeStatus = text(placeStatusMessage, 13f)
        val addBtn = Button(this)
        addBtn.text = "地点を追加"
        addBtn.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull()
            val lon = lonInput.text.toString().toDoubleOrNull()
            if (nameInput.text.isEmpty() || lat == null || lon == null) {
                placeStatusMessage = "地点名・緯度・経度を入力してください"
                placeStatus.text = placeStatusMessage
                return@setOnClickListener
            }
            // ⚠️ 座標の範囲チェック (旧実装と同じ): 範囲外は登録しない。日本域外は警告したうえで登録する
            val rangeError = Places.coordRangeError(lat, lon)
            if (rangeError.isNotEmpty()) {
                placeStatusMessage = rangeError
                placeStatus.text = placeStatusMessage
                return@setOnClickListener
            }
            Settings.addPlace(
                this,
                Place(
                    nameInput.text.toString(), lat, lon, prefInput.text.toString(),
                    areaInput.text.toString(), areaNameInput.text.toString()
                )
            )
            val japanWarning = Places.coordJapanWarning(lat, lon)
            placeStatusMessage = "地点「" + nameInput.text.toString() + "」を追加しました" +
                if (japanWarning.isNotEmpty()) "\n" + japanWarning else ""
            showTab("settings")
        }
        content.addView(addBtn)
        // 📍 GPS: 現在地を取得し、都道府県を推定して地域を選んで登録する
        gpsStatus = placeStatus
        val gpsBtn = Button(this)
        gpsBtn.text = "📍 現在地 (GPS) を取得して地点登録"
        gpsBtn.setOnClickListener { onGpsClicked() }
        content.addView(gpsBtn)
        content.addView(text("GPSで取得した位置から都道府県を推定し、地域区分を選んで「現在地」として登録します。", 12f))
        content.addView(placeStatus)
        content.addView(text("登録地点: " + Settings.places(this).joinToString("、") { it.name }, 13f))
        content.addView(text("AEGIS Android v1.0.0", 12f))
    }

    // 🏷️ ラベル + Spinner の組を作る
    private fun labeledSpinner(label: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(text(label, 13f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(selected)
        var first = true
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (first) {
                    first = false
                    return
                }
                onSelect(pos)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        box.addView(spinner)
        return box
    }

    // ----------------------------------------
    // 📡 SSE の開始と受信時の処理
    // ----------------------------------------

    private fun startStream() {
        streamClient?.stop()
        val client = A2sStreamClient(
            urlProvider = { Settings.a2sUrl(applicationContext) },
            tokenProvider = { Settings.a2sToken(applicationContext) },
            store = store
        ) { ev, replay, result ->
            val fresh = result == EventStore.IngestResult.NEW || result == EventStore.IngestResult.UPDATED
            // 🔔 表示中でも新規の重要イベントは通知する (再生・重複は Planner が弾く)
            val plan = NotifyPlanner.plan(
                ev, replay, fresh, System.currentTimeMillis(),
                Settings.quakeThresholdScale(applicationContext),
                Settings.warnMode(applicationContext)
            )
            if (plan != null) {
                val notified = Settings.notifiedIds(applicationContext)
                if (notified.add(plan.id)) {
                    Settings.saveNotifiedIds(applicationContext, notified)
                    Sync.postNotification(applicationContext, plan)
                }
            }
            runOnUiThread {
                if (currentTab == "events") showTab("events")
                else if (currentTab == "briefing") showTab("briefing")
            }
        }
        streamClient = client
        client.start()
    }

    // 🔔 通知権限のリクエスト (Android 13+)
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ========================================
    // 📍 GPS 地点登録 (旧実装と同じ流れ)
    // ========================================
    // 📝 権限確認 → 許可リクエスト → 最終位置 (10 分以内なら即利用) →
    // 無ければ 1 回だけの更新要求 (15 秒タイムアウト) → 都道府県推定 →
    // 一次細分区域を選んで「現在地 (区域名)」として登録する

    private var gpsStatus: TextView? = null
    private var placeStatusMessage: String = ""
    private var gpsListener: GpsListener? = null
    private val gpsTimeoutHandler = Handler(Looper.getMainLooper())
    private val gpsTimeoutTask = Runnable {
        stopGpsUpdates()
        placeStatusMessage = "現在地を取得できませんでした (タイムアウト)。電波の良い場所で再試行してください"
        gpsStatus?.text = placeStatusMessage
    }

    // 📍 地点まわりの状態文を更新する (再描画後も残るよう保持する)
    private fun setPlaceStatus(msg: String) {
        placeStatusMessage = msg
        gpsStatus?.text = msg
    }

    // 📍 GPS ボタン: 権限が無ければリクエストし、あれば取得を開始する
    private fun onGpsClicked() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            setPlaceStatus("位置情報の許可が必要です")
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
            return
        }
        fetchGpsLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            fetchGpsLocation()
        } else {
            setPlaceStatus("位置情報の許可が必要です")
        }
    }

    // 📡 位置の取得: 最終位置が 10 分以内なら即利用、無ければ 1 回だけ更新を要求する
    private fun fetchGpsLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        setPlaceStatus("現在地を取得中…")
        try {
            var last: android.location.Location? = null
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && (last == null || loc.time > last.time)) last = loc
                } catch (e: Exception) {
                    // 権限・プロバイダ事情で取れない場合は次の候補へ
                }
            }
            val tenMinAgo = System.currentTimeMillis() - 10 * 60 * 1000
            if (last != null && last.time >= tenMinAgo) {
                onGpsFix(last)
                return
            }
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                setPlaceStatus("位置情報が無効です。端末の設定で位置情報をオンにしてください")
                return
            }
            stopGpsUpdates()
            val listener = GpsListener(this)
            gpsListener = listener
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            gpsTimeoutHandler.removeCallbacks(gpsTimeoutTask)
            gpsTimeoutHandler.postDelayed(gpsTimeoutTask, 15_000)
        } catch (e: Exception) {
            setPlaceStatus("現在地を取得できませんでした: " + (e.message ?: ""))
        }
    }

    // 🛑 GPS の更新要求とタイムアウトを取り消す
    private fun stopGpsUpdates() {
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutTask)
        val listener = gpsListener ?: return
        gpsListener = null
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        } catch (e: Exception) {
            // 取り消し失敗は無視する (取得自体は終わっている)
        }
    }

    // 📍 位置が取れたら: 都道府県を推定し、一次細分区域の一覧から選んで登録する
    internal fun onGpsFix(loc: android.location.Location) {
        stopGpsUpdates()
        val pref = Places.nearestPref(loc.latitude, loc.longitude)
        setPlaceStatus("現在地を取得しました。地域区分を取得中…")
        Thread {
            var areas: List<Places.ForecastArea> = emptyList()
            try {
                val json = A2sHttp.fetchOnce(WeatherParse.jmaForecastUrl(pref.code), "")
                areas = Places.parseForecastAreas(json)
            } catch (e: Exception) {
                // 地域一覧が取れなければ都道府県単位で登録する
            }
            runOnUiThread { showGpsAreaChoice(pref, loc.latitude, loc.longitude, areas) }
        }.start()
    }

    // 🗾 一次細分区域の選択ダイアログ (取れなかった場合は県単位でそのまま登録)
    private fun showGpsAreaChoice(pref: Pref, lat: Double, lon: Double, areas: List<Places.ForecastArea>) {
        if (areas.isEmpty()) {
            registerGpsLocation(pref, lat, lon, "", "")
            return
        }
        val names = areas.map { it.name }.distinct().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(pref.name + " の地域区分を選択")
            .setItems(names) { _, which ->
                val area = areas.first { it.name == names[which] }
                registerGpsLocation(pref, lat, lon, area.name, area.code)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ✅ GPS 地点を「現在地 (区域名)」として登録する
    private fun registerGpsLocation(pref: Pref, lat: Double, lon: Double, areaName: String, areaCode: String) {
        val label = if (areaName.isEmpty()) "現在地" else "現在地（" + areaName + "）"
        Settings.addPlace(this, Place(label, lat, lon, pref.code, areaCode, areaName))
        setPlaceStatus("地点「" + label + "」を登録しました (" + pref.name + ")")
        showTab("settings")
    }

    companion object {
        // 📍 位置情報の許可リクエストコード
        private const val REQ_LOCATION = 1002
    }
}

// ========================================
// 📍 GPS の 1 回だけの位置取得リスナー
// ========================================
// 📝 名前付きクラスにする (旧実装と同じ方針: 匿名クラスは dex 化で不利なため)。
// 位置が 1 件届いたら Activity 側の onGpsFix へ渡す
private class GpsListener(private val activity: MainActivity) : android.location.LocationListener {
    override fun onLocationChanged(location: android.location.Location) {
        activity.onGpsFix(location)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
