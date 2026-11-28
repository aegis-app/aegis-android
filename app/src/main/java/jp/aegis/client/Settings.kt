package jp.aegis.client

import android.content.Context
import android.content.SharedPreferences
import jp.aegis.client.a2s.NotifyPlanner
import jp.aegis.client.json.MiniJson
import jp.aegis.client.weather.DEFAULT_PLACES
import jp.aegis.client.weather.Place

// ========================================
// ⚙️ Settings (アプリ設定の読み書き)
// ========================================
// 📌 SharedPreferences `aegis_prefs` に集約する。A.A.S の URL とトークンは
// ここ (端末内の設定) からのみ読む。既定値は空であり、実値をコードに
// 埋め込むことは決してしない (URL とトークンの両方が入ったときだけ有効)
object Settings {

    private const val PREFS = "aegis_prefs" // 設定ファイル名
    private const val KEY_A2S_URL = "a2s_url"         // A.A.S のベース URL (空なら無効)
    private const val KEY_A2S_TOKEN = "a2s_token"     // A.A.S のクライアントトークン
    private const val KEY_BG_ENABLED = "bg_sync_enabled"   // バックグラウンド同期の有効化
    private const val KEY_BG_INTERVAL = "bg_sync_interval" // 同期間隔 (分)
    private const val KEY_QUAKE_THRESHOLD = "notify_quake_threshold" // 地震通知の震度しきい値 (コード)
    private const val KEY_WARN_MODE = "notify_warn_mode"   // 警報の通知モード (NotifyPlanner.WARN_*)
    private const val KEY_CUSTOM_PLACES = "custom_places"  // 追加地点 (JSON 配列)
    private const val KEY_NOTIFIED_IDS = "notified_ids"    // 通知済み ID の集合 (改行区切り)
    private const val KEY_LAST_SINCE = "a2s_last_since"    // since= 回収の起点 (ISO 文字列)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ----------------------------------------
    // 📡 A.A.S 接続設定
    // ----------------------------------------

    fun a2sUrl(ctx: Context): String = prefs(ctx).getString(KEY_A2S_URL, "") ?: ""

    fun a2sToken(ctx: Context): String = prefs(ctx).getString(KEY_A2S_TOKEN, "") ?: ""

    fun setA2s(ctx: Context, url: String, token: String) {
        prefs(ctx).edit()
            .putString(KEY_A2S_URL, url.trim())
            .putString(KEY_A2S_TOKEN, token.trim())
            .apply()
    }

    // ----------------------------------------
    // 🔔 同期・通知設定
    // ----------------------------------------

    fun isBgSyncEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BG_ENABLED, true)

    fun setBgSyncEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BG_ENABLED, on).apply()
    }

    fun intervalMinutes(ctx: Context): Int = prefs(ctx).getInt(KEY_BG_INTERVAL, 30)

    fun setIntervalMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_BG_INTERVAL, minutes).apply()
    }

    fun quakeThresholdScale(ctx: Context): Int = prefs(ctx).getInt(KEY_QUAKE_THRESHOLD, 30)

    fun setQuakeThresholdScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_QUAKE_THRESHOLD, scale).apply()
    }

    fun warnMode(ctx: Context): Int = prefs(ctx).getInt(KEY_WARN_MODE, NotifyPlanner.WARN_ALERTS_ONLY)

    fun setWarnMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_WARN_MODE, mode).apply()
    }

    // ----------------------------------------
    // 📍 地点 (初期地点 + ユーザー追加地点)
    // ----------------------------------------

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>(DEFAULT_PLACES)
        val raw = prefs(ctx).getString(KEY_CUSTOM_PLACES, "") ?: ""
        val arr = MiniJson.arr(MiniJson.parseOrNull(raw)).orEmpty()
        for (item in arr) {
            val m = MiniJson.obj(item) ?: continue
            val name = MiniJson.str(m, "name")
            val lat = MiniJson.numOrNull(m, "lat") ?: continue
            val lon = MiniJson.numOrNull(m, "lon") ?: continue
            if (name.isEmpty()) continue
            out.add(
                Place(
                    name, lat, lon, MiniJson.str(m, "prefCode"),
                    MiniJson.str(m, "areaCode"), MiniJson.str(m, "areaName")
                )
            )
        }
        return out
    }

    fun addPlace(ctx: Context, place: Place) {
        val current = places(ctx).drop(DEFAULT_PLACES.size).toMutableList()
        current.add(place)
        val sb = StringBuilder("[")
        current.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(jsonString(p.name))
                .append(",\"lat\":").append(p.lat)
                .append(",\"lon\":").append(p.lon)
                .append(",\"prefCode\":").append(jsonString(p.prefCode))
                .append(",\"areaCode\":").append(jsonString(p.areaCode))
                .append(",\"areaName\":").append(jsonString(p.areaName))
                .append('}')
        }
        sb.append(']')
        prefs(ctx).edit().putString(KEY_CUSTOM_PLACES, sb.toString()).apply()
    }

    // 🔧 JSON 文字列リテラルの最小エスケープ
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ----------------------------------------
    // 🔔 通知済み ID と since= 起点 (同期の状態)
    // ----------------------------------------

    fun notifiedIds(ctx: Context): MutableSet<String> {
        val raw = prefs(ctx).getString(KEY_NOTIFIED_IDS, "") ?: ""
        return raw.split('\n').filter { it.isNotEmpty() }.toMutableSet()
    }

    fun saveNotifiedIds(ctx: Context, ids: Set<String>) {
        // 📝 肥大化防止に直近 300 件だけ残す
        val kept = ids.toList().takeLast(300)
        prefs(ctx).edit().putString(KEY_NOTIFIED_IDS, kept.joinToString("\n")).apply()
    }

    fun lastSince(ctx: Context): String = prefs(ctx).getString(KEY_LAST_SINCE, "") ?: ""

    fun setLastSince(ctx: Context, iso: String) {
        prefs(ctx).edit().putString(KEY_LAST_SINCE, iso).apply()
    }
}
