package jp.aegis.client.a2s

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

// ========================================
// 🌐 A.A.S への HTTP 接続 (REST / SSE)
// ========================================
// 📌 認証は Authorization: Bearer。トークンは呼び出し側が設定から読んで
// 渡すだけで、この層は保持しない (既定値に実値を入れることは決してしない)
// 📝 HTTP の失敗は HttpFetchException (ステータスコード保持) で表す

// ❌ HTTP 取得の失敗
class HttpFetchException(val code: Int) : Exception("HTTP $code")

// 📡 REST 取得まわり
object A2sHttp {

    // 📥 認証ヘッダつきで 1 回だけ取得する (200 以外は HttpFetchException)
    fun fetchOnce(urlStr: String, token: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "AEGIS-Android/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code != 200) throw HttpFetchException(code)
            var ins = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                ins = GZIPInputStream(ins)
            }
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    sb.append(line)
                    line = br.readLine()
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    // 📥 GET /v1/events を取得してイベント一覧へ変換する (失敗は例外)
    fun fetchEvents(baseUrl: String, token: String, kind: String = "", sinceIso: String = "", limit: Int = 50): List<A2sEvent> {
        val body = fetchOnce(A2sParse.eventsUrl(baseUrl, kind, sinceIso, limit), token)
        return A2sParse.eventsFromJson(body)
    }

    // 🔍 接続テスト用: GET /v1/status が 200 で返るか (失敗は例外)
    fun checkStatus(baseUrl: String, token: String) {
        fetchOnce(A2sParse.statusUrl(baseUrl), token)
    }
}

// ========================================
// 📡 SSE クライアント (フォアグラウンド専用)
// ========================================
// 📝 アプリ表示中のみ接続する (電池・Doze 配慮のためバックグラウンドでは
// 常時接続せず、取りこぼし回収は REST の since= に任せる)。
// 切断時は 5秒→10秒→30秒→60秒のバックオフで再接続する
class A2sStreamClient(
    private val urlProvider: () -> String,     // ベース URL の供給元 (設定の再読込に追従)
    private val tokenProvider: () -> String,   // トークンの供給元
    private val store: EventStore,             // 取り込み先のストア
    // 📨 新規・更新イベントの受け渡し先。replay は接続時の再生分かどうか
    private val onEvent: (A2sEvent, Boolean, EventStore.IngestResult) -> Unit
) {
    @Volatile
    private var running = false // 接続ループを回し続けるか
    private var thread: Thread? = null // 接続スレッド

    // ▶️ 接続開始 (未設定なら何もしない)
    fun start() {
        if (running) return
        if (!A2sParse.isEnabled(urlProvider(), tokenProvider())) return
        running = true
        val t = Thread { runLoop() }
        t.isDaemon = true
        thread = t
        t.start()
    }

    // ⏹️ 接続停止
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    // 🔄 接続を維持するループ (切断時はバックオフで再接続)
    private fun runLoop() {
        var failures = 0
        while (running) {
            try {
                connectAndRead()
                failures = 0
            } catch (e: Exception) {
                if (!running) return
            }
            if (!running) return
            try {
                Thread.sleep(reconnectBackoffMs(failures))
            } catch (e: InterruptedException) {
                return
            }
            failures++
        }
    }

    // 🔌 1 回の接続を張り、ストリームを読み切る
    private fun connectAndRead() {
        val base = A2sParse.normalizeBaseUrl(urlProvider())
        val token = tokenProvider()
        val conn = URL(A2sParse.streamUrl(base)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000 // heartbeat は 25 秒ごとのため、その倍以上で切断検知する
            conn.setRequestProperty("User-Agent", "AEGIS-Android/1.0")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code != 200) throw HttpFetchException(code)
            val parser = SseParser()
            // 📌 ready が届くまでは接続時の再生分として扱う
            var replay = true
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    if (!running) return
                    for (frame in parser.push(line + "\n")) {
                        if (frame.type == "ready") {
                            replay = false
                            continue
                        }
                        if (frame.type !in A2S_KINDS) continue
                        val ev = A2sParse.eventFromJsonString(frame.data) ?: continue
                        val result = store.ingest(ev)
                        try {
                            onEvent(ev, replay, result)
                        } catch (e: Exception) {
                            // 📝 受け渡し先の例外で接続処理自体は止めない
                        }
                    }
                    line = br.readLine()
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
