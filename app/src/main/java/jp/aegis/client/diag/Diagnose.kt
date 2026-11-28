package jp.aegis.client.diag

import jp.aegis.client.a2s.A2sHttp
import jp.aegis.client.a2s.A2sParse
import jp.aegis.client.news.News
import jp.aegis.client.radar.Radar
import jp.aegis.client.server.ServerMode
import jp.aegis.client.weather.Place
import jp.aegis.client.weather.WeatherParse
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ========================================
// 🩺 接続テスト (自己診断)
// ========================================
// 📌 各データソースへ実際にリクエストし、ソースごとの結果行を返す。
// 整形・エラー文言・対象一覧は純粋関数で、取得関数を差し替えて
// JVM 単体テストで検証できる
// 📝 旧実装の AegisData.diagnose / diagLine / friendlyError と同一

object Diagnose {

    // 📝 結果 1 行の整形: 成功は「○ 名前：接続OK（Nms）」、失敗は理由つき
    fun diagLine(name: String, ok: Boolean, ms: Long, reason: String?): String {
        if (ok) return "○ " + name + "：接続OK（" + ms + "ms）"
        return "× " + name + "：失敗（" + (if (reason.isNullOrEmpty()) "不明なエラー" else reason) + "）"
    }

    // 📝 例外を人が読める理由文にする (旧実装の friendlyError と同じ判定順)
    fun friendlyError(e: Exception): String {
        val m = e.message.toString()
        if (e is SocketTimeoutException || m.contains("timed out")) return "通信がタイムアウトしました"
        if (m.startsWith("HTTP ")) return "サーバーエラー（" + m + "）"
        if (e is UnknownHostException) return "サーバーに接続できませんでした"
        if (m.contains("abort") || m.contains("reset") || m.contains("ECONN")) return "通信が切断されました"
        return "通信エラーが発生しました"
    }

    // 🌤️ Open-Meteo の診断用 URL (現在気温のみ・モデル指定つき。旧実装と同じ)
    fun openMeteoDiagUrl(lat: Double, lon: Double): String {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
            "&current=temperature_2m&timezone=Asia%2FTokyo&models=jma_seamless,ecmwf_ifs025"
    }

    // 🎯 診断対象 1 件 (名前 + URL)。A.A.S はトークンが要るため別枠で扱う
    data class Target(val name: String, val url: String)

    // 🎯 診断対象の一覧 (旧実装と同じ並び)。AEGIS サーバーはサーバーモード時のみ末尾に足す
    fun targets(firstPlace: Place, serverMode: Boolean, serverUrl: String?): List<Target> {
        val out = ArrayList<Target>()
        out.add(Target("気象庁 予報", WeatherParse.jmaForecastUrl(firstPlace.prefCode)))
        out.add(Target("Open-Meteo", openMeteoDiagUrl(firstPlace.lat, firstPlace.lon)))
        out.add(Target("RainViewer レーダー", Radar.RAINVIEWER_MAPS_URL))
        out.add(Target("気象庁 ナウキャスト", Radar.JMA_NOWCAST_TARGET_TIMES_URL))
        out.add(Target("NHK ニュース RSS", News.CAT0_URL))
        if (serverMode) {
            out.add(Target("AEGISサーバー", ServerMode.briefingUrl(serverUrl ?: "")))
        }
        return out
    }

    // 🩺 診断の実行 (取得関数を差し替え可能)。対象を並行に叩き、60 秒で見切る。
    // 並びは旧実装と同じ: 5 ソース → A.A.S (有効なときだけ、/v1/status を
    // トークンつきで確認) → AEGIS サーバー (サーバーモード時のみ)
    fun diagnose(
        firstPlace: Place,
        serverMode: Boolean,
        serverUrl: String?,
        a2sUrl: String,
        a2sToken: String,
        fetch: (String) -> String,
        fetchA2s: (String, String) -> Unit
    ): List<String> {
        fun probe(name: String, block: () -> Unit): Callable<String> {
            return Callable {
                val start = System.currentTimeMillis()
                try {
                    block()
                    diagLine(name, true, System.currentTimeMillis() - start, "")
                } catch (e: Exception) {
                    diagLine(name, false, System.currentTimeMillis() - start, friendlyError(e))
                }
            }
        }
        val tasks = ArrayList<Callable<String>>()
        for (t in targets(firstPlace, false, null)) {
            tasks.add(probe(t.name) { fetch(t.url) })
        }
        if (A2sParse.isEnabled(a2sUrl, a2sToken)) {
            tasks.add(probe("A.A.S (Aegis Alert System)") { fetchA2s(a2sUrl, a2sToken) })
        }
        if (serverMode) {
            val serverTarget = targets(firstPlace, true, serverUrl).last()
            tasks.add(probe(serverTarget.name) { fetch(serverTarget.url) })
        }
        val pool = Executors.newCachedThreadPool()
        try {
            val futures = tasks.map { pool.submit(it) }
            val lines = ArrayList<String>()
            for (f in futures) {
                try {
                    lines.add(f.get(60, TimeUnit.SECONDS))
                } catch (e: Exception) {
                    lines.add(diagLine("（不明なソース）", false, 0, friendlyError(e)))
                }
            }
            return lines
        } finally {
            pool.shutdown()
        }
    }

    // 🩺 実通信版: 取得は 1 回だけリトライする (旧実装の fetch と同じ)。
    // ワーカースレッドで呼ぶこと
    fun diagnose(
        firstPlace: Place,
        serverMode: Boolean,
        serverUrl: String?,
        a2sUrl: String,
        a2sToken: String
    ): List<String> {
        return diagnose(
            firstPlace, serverMode, serverUrl, a2sUrl, a2sToken,
            fetch = { url -> fetchWithRetry(url) },
            fetchA2s = { url, token -> A2sHttp.checkStatus(url, token) }
        )
    }

    // 🔁 1 回だけリトライする取得 (失敗時に 1 秒待って再試行する)
    private fun fetchWithRetry(url: String): String {
        return try {
            A2sHttp.fetchOnce(url, "")
        } catch (e: Exception) {
            Thread.sleep(1000)
            A2sHttp.fetchOnce(url, "")
        }
    }
}
