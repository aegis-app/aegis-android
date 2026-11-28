package jp.aegis.client.server

import jp.aegis.client.TextSection
import jp.aegis.client.a2s.A2sHttp
import jp.aegis.client.json.MiniJson

// ========================================
// 🌐 AEGIS サーバーモード
// ========================================
// 📌 PC 等で動いている AEGIS (Python 版の serve) の /api/briefing を
// 取得し、表示用セクションへ変換する。直接取得モードとは設定で切り替える
// 📝 旧実装の AegisDataBriefing.serverSections と同一の変換規則

object ServerMode {

    // 🌐 サーバー URL の正規化 (前後の空白と末尾のスラッシュを取り除く)
    fun normalizeBase(raw: String?): String {
        return (raw ?: "").trim().trimEnd('/')
    }

    // 📥 ブリーフィング取得先
    fun briefingUrl(base: String): String = normalizeBase(base) + "/api/briefing"

    // 📥 接続テスト先 (履歴 API で応答の有無を見る。旧実装と同じ)
    fun historyUrl(base: String): String = normalizeBase(base) + "/api/history"

    // 🚨 深刻度 → 絵文字 (旧実装の severityEmoji と同じ)
    fun severityEmoji(sev: String?): String {
        return when (sev ?: "") {
            "CRITICAL" -> "🔴"
            "HIGH" -> "🟠"
            "MEDIUM" -> "🟡"
            "LOW" -> "🟢"
            else -> "ℹ️"
        }
    }

    // 🚨 深刻度 → 表示レベル (CRITICAL は danger、HIGH / MEDIUM は warn、それ以外は info)
    fun levelOf(sev: String?): String {
        return when (sev ?: "") {
            "CRITICAL" -> "danger"
            "HIGH", "MEDIUM" -> "warn"
            else -> "info"
        }
    }

    // 📝 /api/briefing の JSON を表示用セクションへ変換する。
    // events が無い・空の場合は、その旨の 1 セクションだけを返す
    fun parseSections(json: String): List<TextSection> {
        val root = MiniJson.obj(MiniJson.parseOrNull(json))
        val events = MiniJson.arr(root?.get("events")).orEmpty()
        if (events.isEmpty()) {
            return listOf(
                TextSection("🛡️ AEGIS ブリーフィング", "サーバーからイベントが返りませんでした。", "warn")
            )
        }
        val out = ArrayList<TextSection>()
        for (ev in events) {
            val m = MiniJson.obj(ev) ?: continue
            val sev = MiniJson.str(m, "severity", "INFO")
            val body = StringBuilder(MiniJson.str(m, "body"))
            val guide = MiniJson.str(m, "action_guide")
            if (guide.isNotEmpty()) body.append("\n▶ 行動ガイド： ").append(guide)
            out.add(
                TextSection(
                    severityEmoji(sev) + " " + MiniJson.str(m, "title"),
                    body.toString(),
                    levelOf(sev)
                )
            )
        }
        return out
    }

    // 📡 サーバーからブリーフィングを取得して変換する (通信するのでワーカースレッドで呼ぶこと)
    fun fetchSections(base: String): List<TextSection> {
        return parseSections(A2sHttp.fetchOnce(briefingUrl(base), ""))
    }
}
