package jp.aegis.client.briefing

import jp.aegis.client.a2s.A2sEvent
import jp.aegis.client.a2s.DisplayText
import jp.aegis.client.a2s.scaleLabel
import jp.aegis.client.weather.Place
import java.util.Calendar
import java.util.TimeZone

// ========================================
// 🌅 朝夕ブリーフィング (純粋層)
// ========================================
// 📌 07:00 / 21:00 の定期ブリーフィング通知のための時刻計算と、
// 「現在のまとめ」本文の組み立て。画面のブリーフィングタブと通知で
// 同じ summaryText を使う
// 📝 旧実装の AegisData.nextBriefingMillis / まとめ組み立てと同一

object Briefing {

    // 🌅 朝のブリーフィング時刻
    const val MORNING_HOUR = 7

    // 🌆 夜のブリーフィング時刻
    const val EVENING_HOUR = 21

    // 🕐 次回のブリーフィング時刻 (epoch ミリ秒): 今日の hour:minute が
    // まだ来ていなければ今日、過ぎていれば (ちょうどその時刻も含め) 明日
    fun nextBriefingMillis(hour: Int, minute: Int, now: Long, tz: TimeZone): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    // 🔔 ブリーフィング通知の ID (日付 + 朝夕で一意)
    fun notificationId(date: String, morning: Boolean): String {
        return "briefing:" + date + if (morning) ":am" else ":pm"
    }

    // 🔔 ブリーフィング通知のタイトル
    fun title(morning: Boolean): String {
        return if (morning) "🌅 朝のブリーフィング" else "🌆 夜のブリーフィング"
    }

    // 📝 「現在のまとめ」の本文: 警報 / 津波 / 最新地震 / 台風 を 1 枚に集約する。
    // 警報は登録地点 (都道府県 + 一次細分区域) に一致するものだけを地点名つきで出す
    fun summaryText(events: List<A2sEvent>, places: List<Place>): String {
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
            if (tsunami?.tsunami?.cancelled == false && tsunami.tsunami.areas.isNotEmpty()) {
                "津波警報・注意報が発表されています\n"
            } else {
                "津波警報・注意報はありません\n"
            }
        )
        val quake = events.firstOrNull { it.kind == "quake" }
        if (quake?.quake != null) {
            sb.append("最新地震: ").append(quake.quake.hypocenter)
                .append(" 最大震度 ").append(scaleLabel(quake.quake.maxScale))
                .append('\n')
        }
        val typhoon = events.firstOrNull { it.kind == "typhoon" }
        if (typhoon?.typhoon != null) {
            sb.append("台風: ").append(DisplayText.typhoonBody(typhoon.typhoon)).append('\n')
        }
        return sb.toString().trim()
    }
}
