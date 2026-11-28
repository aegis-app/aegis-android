package jp.aegis.client.a2s

// ========================================
// 📦 A.A.S (A2S) イベントのデータモデル
// ========================================
// 📌 A.A.S が配信するイベントの共通封筒 (kind / key / receivedAt / updatedAt) と、
// 種別ごとのペイロード。フィールド名と意味は A.A.S の公開契約そのままで、
// 数値の欠落は null で表す (深さ不明 = null、0 = ごく浅い、のように区別する)
// 📝 旧実装が詳細に扱った 5 種別 (quake / eew / tsunami / warning / typhoon) は
// 専用ペイロードを持ち、残りの 9 種別は表示行 (lines) を中心とした共通
// ペイロードで受ける (A.A.S は全 14 種別を同じ封筒で配信するため)

// 📦 イベント共通封筒
data class A2sEvent(
    val kind: String,        // 種別 (quake | eew | tsunami | warning | typhoon | flood | ...)
    val key: String,         // 同一視キー (種別ごとの規則は A.A.S 側が決める)
    val updatedAt: String,   // 最終更新時刻 (ISO 8601)。重複判定は key + updatedAt
    val receivedAt: String,  // 初回取り込み時刻 (ISO 8601)
    val quake: QuakePayload? = null,
    val eew: EewPayload? = null,
    val tsunami: TsunamiPayload? = null,
    val warning: WarningPayload? = null,
    val typhoon: TyphoonPayload? = null,
    val generic: GenericPayload? = null
)

// 🌍 地震ペイロード。points は観測点・地域ごとの震度、prefScales は県別最大震度
data class QuakePayload(
    val telegramId: String,  // 主電文 ID (通知 ID の基礎。空なら key で代替)
    val time: String,        // 発生時刻 (JST 表記)。国外地震では originTime が入ることもある
    val announcedTime: String, // 発表時刻 (JST 表記)
    val hypocenter: String,  // 震源名
    val magnitude: Double?,  // マグニチュード (不明は null)
    val depthKm: Int?,       // 深さ km (不明は null、0 は「ごく浅い」)
    val maxScale: Int,       // 最大震度コード (不明は -1)
    val tsunamiKind: String, // 津波の見通し種別 (Unknown 等)
    val foreign: Boolean,    // 国外地震かどうか
    val issueType: String,   // 発表種別
    val issueCorrect: String, // 訂正情報
    val lat: Double?,        // 緯度 (座標なしは null)
    val lon: Double?,        // 経度 (座標なしは null)
    val points: List<QuakePoint>,
    val prefScales: List<PrefScale>,
    val originTime: String,  // 国外地震の発生時刻の原表記 (無ければ "")
    val tsunamiText: String  // 津波コメント原文 (無ければ "")
)

// 📍 震度観測点 (isArea = true は地域代表点)
data class QuakePoint(val pref: String, val name: String, val scale: Int, val isArea: Boolean)

// 🗾 都道府県別の最大震度
data class PrefScale(val pref: String, val scale: Int)

// 🚨 緊急地震速報 (EEW) ペイロード
data class EewPayload(
    val baseKey: String,     // 取消でも変わらない同一視キー
    val time: String,        // 発表時刻 (JST 表記)
    val originTime: String,  // 発生時刻 (JST 表記)
    val cancel: Boolean,     // 取消報かどうか
    val isWarn: Boolean,     // 警報 (true) / 予報 (false)
    val serial: Int?,        // 報番号
    val eventId: String?,    // イベント ID
    val isAssumption: Boolean, // 仮定震源要素かどうか
    val isFinal: Boolean,    // 最終報かどうか
    val hypocenter: String,  // 震源名
    val estScale: Int,       // 推定最大震度コード (不明は -1)
    val estMagnitude: Double?, // 推定マグニチュード
    val lat: Double?,
    val lon: Double?,
    val depthKm: Int?,
    val areaScales: List<EewAreaScale>,     // 地域別の予想震度
    val areaArrivals: List<EewAreaArrival>  // 都道府県別の到達予想時刻
)

// 🗾 地域別の予想震度 (scaleTo は予想の上限。null は上限なし)
data class EewAreaScale(val pref: String, val name: String, val scale: Int, val scaleTo: Int?)

// 🕐 都道府県別の主要動到達予想時刻 (JST 表記)
data class EewAreaArrival(val pref: String, val arrivalTime: String)

// 🌊 津波ペイロード
data class TsunamiPayload(
    val time: String,        // 発表時刻 (JST 表記)
    val cancelled: Boolean,  // 解除かどうか
    val grade: String,       // 全体の格付け
    val areas: List<TsunamiArea>
)

// 🏖️ 津波予報区ごとの情報
data class TsunamiArea(
    val name: String,                    // 予報区名
    val grade: String,                   // 警報・注意報の種別
    val maxHeightLabel: String?,         // 予想高さのラベル
    val maxHeightValue: Double?,         // 予想高さの数値 (m)
    val arrivalLabel: String?,           // 到達予想のラベル
    val firstHeightCondition: String?    // 第1波の状況
)

// ⚠️ 気象警報・注意報ペイロード (一次細分区域ごとに 1 件)
data class WarningPayload(
    val officeCode: String,  // 発表官署コード (= 都道府県コードで照合する)
    val prefName: String,    // 都道府県名
    val areaCode: String,    // 一次細分区域コード
    val areaName: String,    // 一次細分区域名
    val reportDatetime: String, // 発表日時
    val headline: String,    // 見出し文
    val warnings: List<WarningItem>,
    val maxSeverity: Int,    // 最大の重要度 (特別警報=4 / 警報=3 / 注意報=2)
    val time: String,        // 発表時刻
    val released: Boolean    // 解除イベント (SSE でのみ届く) かどうか
)

// ⚠️ 警報・注意報の個別項目
data class WarningItem(
    val code: String,        // 警報コード
    val name: String,        // 名称
    val status: String,      // 状態 (発表 / 継続 / 解除 等)
    val severity: Int        // 重要度 (特別警報=4 / 警報=3 / 注意報=2)
)

// 🌀 台風ペイロード (台風ごとに 1 件)。表示は lines を基準にする
data class TyphoonPayload(
    val number: String?,             // 台風番号
    val name: String,                // 名称
    val englishName: String,         // 英語名
    val lines: List<String>,         // 表示行 (A.A.S が組み立てた文章)
    val fields: Map<String, String>, // データ源の全スカラー値 (素データとして保持)
    val time: String                 // 発表時刻
)

// 📄 詳細ペイロードを持たない種別 (flood / sediment / tornado / record-rain /
// linear-rain / volcano / river / dam / heat) の共通ペイロード
data class GenericPayload(
    val title: String,          // 見出し (title / headline のどちらか。無ければ "")
    val lines: List<String>,    // 表示行
    val time: String,           // 発表時刻
    val released: Boolean       // 解除イベントかどうか
)

// ========================================
// 🔢 震度コードの表記
// ========================================
// 📝 気象庁の震度コード (10=震度1 … 45=5弱、50=5強、55=6弱、60=6強、70=7) を
// 表示文字列へ変換する。コード体系外・不明 (-1) は「不明」
fun scaleLabel(code: Int): String = when (code) {
    10 -> "1"
    20 -> "2"
    30 -> "3"
    40 -> "4"
    45 -> "5弱"
    50 -> "5強"
    55 -> "6弱"
    60 -> "6強"
    70 -> "7"
    else -> "不明"
}

// 📝 種別の表示名 (日本語)。未知の種別はそのまま返す
fun kindLabel(kind: String): String = when (kind) {
    "quake" -> "地震"
    "eew" -> "緊急地震速報"
    "tsunami" -> "津波"
    "warning" -> "気象警報・注意報"
    "typhoon" -> "台風"
    "flood" -> "洪水"
    "sediment" -> "土砂災害"
    "tornado" -> "竜巻注意情報"
    "record-rain" -> "記録的短時間大雨"
    "linear-rain" -> "顕著な大雨"
    "volcano" -> "噴火"
    "river" -> "河川"
    "dam" -> "ダム放流"
    "heat" -> "熱中症"
    else -> kind
}

// 📝 A.A.S が配信する全 14 種別
val A2S_KINDS: List<String> = listOf(
    "quake", "eew", "tsunami", "warning", "typhoon", "flood", "sediment",
    "tornado", "record-rain", "linear-rain", "volcano", "river", "dam", "heat"
)
