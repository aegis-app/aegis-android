package jp.aegis.client.a2s

// ========================================
// 🔔 NotifyPlanner (通知にするかどうかの判定)
// ========================================
// 📌 受信イベントを表示だけでなく通知にもするかを決める純粋関数。
// 規則の要点 (旧実装からの継承事項):
// - SSE 再生分 (replay) と重複分は絶対に通知しない (再接続のたびに鳴らさない)
// - 地震は設定した震度しきい値以上だけ、警報は重要度モードに従う
// - EEW は鮮度 (15分) を満たし、警報または推定震度4以上、取消は常に対象
// - 津波は発表・解除とも常に対象、台風と共通種別は表示のみ
object NotifyPlanner {

    // ⚙️ 警報の通知モード
    const val WARN_OFF = 0        // 通知しない
    const val WARN_ALERTS_ONLY = 1 // 警報以上 (severity >= 3)
    const val WARN_ALL = 2         // 注意報から (severity >= 2)

    // 📦 通知 1 件分の計画 (ID は再通知防止のため種別ごとに安定させる)
    data class Plan(
        val id: String,      // 論理 ID (同じ情報の更新は同じ ID で上書きされる)
        val urgent: Boolean, // 緊急チャンネル (EEW・津波) かどうか
        val title: String,   // 通知タイトル
        val body: String     // 通知本文
    )

    // 🔔 判定の入口。通知すべきなら Plan、そうでなければ null を返す
    // replay: SSE 接続時の再生分かどうか / fresh: ストア取り込みで新規または更新だったか
    fun plan(
        ev: A2sEvent,
        replay: Boolean,
        fresh: Boolean,
        nowMillis: Long,
        quakeThresholdScale: Int,
        warnMode: Int
    ): Plan? {
        if (replay || !fresh) return null
        return when (ev.kind) {
            "quake" -> quakePlan(ev, quakeThresholdScale)
            "eew" -> eewPlan(ev, nowMillis)
            "tsunami" -> tsunamiPlan(ev)
            "warning" -> warningPlan(ev, warnMode)
            else -> null // 台風・共通種別は表示のみ
        }
    }

    // 🌍 地震: 最大震度がしきい値以上のときだけ通知する。
    // ID は主電文 ID 基準 (旧経路と同じ規則で、移行直後の二重通知を防ぐ)
    private fun quakePlan(ev: A2sEvent, thresholdScale: Int): Plan? {
        val q = ev.quake ?: return null
        if (q.maxScale < thresholdScale) return null
        return Plan(
            id = "quake:" + q.telegramId,
            urgent = false,
            title = DisplayText.title(ev),
            body = DisplayText.quakeBody(q)
        )
    }

    // 🚨 EEW: 鮮度を満たすものだけ。取消は鮮度内なら常に通知する
    private fun eewPlan(ev: A2sEvent, nowMillis: Long): Plan? {
        val e = ev.eew ?: return null
        if (!DisplayText.isEewFresh(e, nowMillis)) return null
        if (!e.cancel && !e.isWarn && e.estScale < 40) return null
        return Plan(
            id = "eew:" + e.originTime + ":" + e.estScale + ":" + e.cancel,
            urgent = true,
            title = DisplayText.eewTitle(e),
            body = DisplayText.eewBody(e, emptyList())
        )
    }

    // 🌊 津波: 発表も解除も通知する (対象地域がある場合のみ)
    private fun tsunamiPlan(ev: A2sEvent): Plan? {
        val t = ev.tsunami ?: return null
        if (!t.cancelled && t.areas.isEmpty()) return null
        return Plan(
            id = "tsunami:" + ev.key.removePrefix("552:"),
            urgent = true,
            title = DisplayText.title(ev),
            body = DisplayText.tsunamiBody(t)
        )
    }

    // ⚠️ 警報・注意報: 重要度モードに従う。解除は通知しない (ストア側で除去済み)
    private fun warningPlan(ev: A2sEvent, warnMode: Int): Plan? {
        val w = ev.warning ?: return null
        if (w.released) return null
        val minSeverity = when (warnMode) {
            WARN_ALERTS_ONLY -> 3
            WARN_ALL -> 2
            else -> return null
        }
        if (w.maxSeverity < minSeverity) return null
        val codes = w.warnings.joinToString(",") { it.code }
        return Plan(
            id = "warning:" + w.officeCode + ":" + w.reportDatetime + ":" + codes,
            urgent = w.maxSeverity >= 4,
            title = DisplayText.title(ev),
            body = DisplayText.warningBody(w)
        )
    }
}
