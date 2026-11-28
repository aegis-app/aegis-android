package jp.aegis.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
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
        for ((id, label) in listOf("briefing" to "📌 ブリーフィング", "events" to "📋 イベント", "settings" to "⚙️ 設定")) {
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

    // 🗾 地点の都道府県名 (EEW 到達予想の照合用)。地点名そのものを既定にする
    private fun prefNameOf(place: Place): String = when (place.prefCode) {
        "100000" -> "群馬県"
        "110000" -> "埼玉県"
        else -> place.name
    }

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
        content.addView(nameInput); content.addView(latInput); content.addView(lonInput)
        content.addView(prefInput); content.addView(areaInput)
        val addBtn = Button(this)
        addBtn.text = "地点を追加"
        addBtn.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull()
            val lon = lonInput.text.toString().toDoubleOrNull()
            if (nameInput.text.isNotEmpty() && lat != null && lon != null) {
                Settings.addPlace(
                    this,
                    Place(nameInput.text.toString(), lat, lon, prefInput.text.toString(), areaInput.text.toString())
                )
                showTab("settings")
            }
        }
        content.addView(addBtn)
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
}
