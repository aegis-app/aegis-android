package jp.aegis.client.a2s

// ========================================
// 📡 SSE フレーム解析 (Server-Sent Events)
// ========================================
// 📝 A.A.S の /v1/stream と同じ規則: 空行でフレームが完成し、":" で始まる行
// (heartbeat 等のコメント) は読み飛ばす。event: が種別、data: が本文。
// 接続直後は保持イベントが古い順に再生され、最後に event: ready が届く。
// ready より前のフレームは「再生分」であり、新規通知として扱わない
// (再接続のたびに通知が鳴らないようにするため。判定は NotifyPlanner 側)

// 📦 完成した SSE フレーム 1 件
data class SseFrame(val type: String, val data: String)

// 🔧 テキスト断片を逐次取り込む状態機械 (ネットワークから切り離してテスト可能)
class SseParser {
    private var buffer = ""              // 行に満たない残りテキスト
    private var eventType = ""           // 現在のフレームの event: の値
    private val dataLines = ArrayList<String>() // 現在のフレームの data: 行群

    // 📥 テキストの断片を取り込み、完成したフレームを返す
    fun push(chunk: String): List<SseFrame> {
        buffer += chunk
        val frames = ArrayList<SseFrame>()
        var idx: Int
        while (buffer.indexOf('\n').also { idx = it } >= 0) {
            var line = buffer.substring(0, idx)
            buffer = buffer.substring(idx + 1)
            if (line.endsWith("\r")) line = line.substring(0, line.length - 1)
            val frame = line(line)
            if (frame != null) frames.add(frame)
        }
        return frames
    }

    // 📥 1 行を取り込む。空行でフレームが完成したらそれを返す
    private fun line(line: String): SseFrame? {
        if (line.isEmpty()) {
            if (dataLines.isEmpty()) {
                eventType = ""
                return null
            }
            val frame = SseFrame(
                if (eventType.isNotEmpty()) eventType else "message",
                dataLines.joinToString("\n")
            )
            eventType = ""
            dataLines.clear()
            return frame
        }
        if (line.startsWith(":")) return null // heartbeat 等のコメント行
        if (line.startsWith("event:")) {
            eventType = line.substring(6).trim()
            return null
        }
        if (line.startsWith("data:")) {
            var d = line.substring(5)
            if (d.startsWith(" ")) d = d.substring(1)
            dataLines.add(d)
            return null
        }
        return null // id: / retry: 等は使わない
    }
}

// 🔄 再接続バックオフ: 5秒 → 10秒 → 30秒 → 60秒 (以降は 60 秒で頭打ち)
fun reconnectBackoffMs(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 5_000L
    consecutiveFailures == 1 -> 10_000L
    consecutiveFailures == 2 -> 30_000L
    else -> 60_000L
}
