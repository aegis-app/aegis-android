package jp.aegis.client.demo

import jp.aegis.client.TextSection
import jp.aegis.client.a2s.EewAreaArrival
import jp.aegis.client.a2s.EewAreaScale
import jp.aegis.client.a2s.EewPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ========================================
// 🧪 テスト表示 (デモ)
// ========================================
// 📌 実データの代わりにサンプルの警報・EEW を表示し、表示まわりと
// カウントダウンの動きを確認するためのデモ。サンプル EEW は発生が
// 現在時刻・登録地点への到達が約 30 秒後で、カウントダウンが実際に動く
// 📝 旧実装の buildDemoEew / renderDemo のサンプル内容と同一

object Demo {

    // 🧪 デモ中の状態文 (旧実装と同じ文言)
    const val STATUS_TEXT = "テスト表示中（サンプルデータです。実際の警報ではありません）"

    // 🕐 "yyyy/MM/dd HH:mm:ss" (JST) への整形
    fun formatJst(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return fmt.format(Date(millis))
    }

    // 🕐 "MM/dd HH:mm" (JST) への整形 (サンプル地震の発生時刻表示用)
    fun formatShort(millis: Long): String {
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return fmt.format(Date(millis))
    }

    // 🚨 サンプル EEW: 発生は start、登録地点への到達は約 30 秒後
    // (カウントダウンが実際に動く)。推定最大震度は 5弱 (45)
    fun buildDemoEew(start: Long): EewPayload {
        val arrival = formatJst(start + 30 * 1000L)
        return EewPayload(
            baseKey = "demo",
            time = formatJst(start),
            originTime = formatJst(start),
            cancel = false,
            isWarn = false,
            serial = null,
            eventId = null,
            isAssumption = false,
            isFinal = false,
            hypocenter = "テスト震源（サンプル）",
            estScale = 45,
            estMagnitude = null,
            lat = 36.10,
            lon = 139.30,
            depthKm = 40,
            areaScales = listOf(
                EewAreaScale("群馬県", "群馬県南部", -1, null),
                EewAreaScale("埼玉県", "埼玉県南部", -1, null)
            ),
            areaArrivals = listOf(
                EewAreaArrival("群馬県", arrival),
                EewAreaArrival("埼玉県", arrival)
            )
        )
    }

    // 🧪 デモ共通のバナー
    fun banner(): TextSection {
        return TextSection(
            "🧪 テスト表示中",
            "これはサンプルデータです。実際の警報・地震ではありません。\n" +
                "更新ボタンや自動更新で実際のデータに切り替わると、テスト表示は終了します。",
            "warn"
        )
    }

    // 📌 ブリーフィングタブのデモセクション (EEW カードは呼び出し側が
    // 天気カードの直後に差し込むため、ここには含めない)
    fun briefingSections(): List<TextSection> {
        return listOf(
            banner(),
            TextSection(
                "📌 現在のまとめ",
                "⚠ 発表中：大雨警報\n🌊 津波: なし\n🌏 最新の地震: 震度5弱 テスト震源（サンプル）\n☀ 高崎: 雨",
                "warn"
            ),
            TextSection(
                "☀ 天気予報（高崎）",
                "今日： 雨\n⚠ 発表中： 大雨警報（11/17 12:00発表）\n　大雨による土砂災害に警戒してください（サンプル文）。",
                "warn"
            ),
            TextSection(
                "🌏 最新の地震（1件）",
                "🟠 震度5弱 テスト震源（サンプル）",
                "info"
            )
        )
    }

    // 📋 イベントタブのデモセクション (EEW カードは呼び出し側が末尾に差し込む)
    fun eventsSections(now: Long): List<TextSection> {
        return listOf(
            banner(),
            TextSection("🌊 津波情報", "津波の心配はありません（サンプル）。", "ok"),
            TextSection(
                "🌏 テスト震源（サンプル）",
                formatShort(now) + "発生 / M5.8 / 深さ約40km / 津波の心配なし",
                "warn"
            ),
            TextSection(
                "🌏 サンプル震源2",
                formatShort(now - 42 * 60000L) + "発生 / M4.6 / 深さ約60km / 津波の心配なし",
                "info"
            )
        )
    }
}
