package jp.aegis.client.weather

import jp.aegis.client.json.MiniJson

// ========================================
// 🌤️ 天気予報の解析 (気象庁 bosai / Open-Meteo)
// ========================================
// 📌 天気予報は A.A.S の対象外のため、旧実装と同じく気象庁と Open-Meteo から
// 直接取得する。ここは応答 JSON を表示用データへ変換する純粋関数のみ

// 📍 監視地点
data class Place(
    val name: String,      // 表示名
    val lat: Double,       // 緯度
    val lon: Double,       // 経度
    val prefCode: String,  // 都道府県コード (気象庁の予報・警報の照合に使う)
    val areaCode: String,  // 一次細分区域コード (警報の照合に使う。空なら県単位)
    val areaName: String = "" // 一次細分区域名 (EEW の対象地域照合・GPS 登録で使う)
)

// 📍 初期地点 (旧実装と同じ: 高崎・大宮、区域名はどちらも「南部」)
val DEFAULT_PLACES: List<Place> = listOf(
    Place("高崎", 36.32, 139.00, "100000", "100010", "南部"),
    Place("大宮", 35.91, 139.63, "110000", "110010", "南部")
)

// 🌤️ 1 地点分の天気サマリ
data class WeatherSummary(
    val placeName: String,     // 地点名
    val weatherText: String,   // 今日の天気文字列
    val maxTemp: String?,      // 最高気温 (取得できなければ null)
    val minTemp: String?,      // 最低気温
    val popText: String?,      // 降水確率の要約 (例 "午前10% / 午後30%")
    val currentText: String?   // 現在の気象 (Open-Meteo。例 "18.2℃ / 湿度65%")
)

object WeatherParse {

    // 🔗 気象庁の天気予報 JSON の URL (都道府県コード単位)
    fun jmaForecastUrl(prefCode: String): String =
        "https://www.jma.go.jp/bosai/forecast/data/forecast/$prefCode.json"

    // 🔗 Open-Meteo の現在気象 URL
    fun openMeteoUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=Asia%2FTokyo"

    // 📦 気象庁予報から取り出した今日の天気
    data class JmaForecast(
        val weatherText: String, // 今日の天気文字列
        val maxTemp: String?,    // 最高気温
        val minTemp: String?,    // 最低気温
        val popText: String?     // 降水確率の要約
    )

    // 📥 気象庁予報 JSON から、指定の一次細分区域の今日の天気を取り出す。
    // 応答は [府県予報, 週間予報] の配列で、先頭要素の timeSeries を使う
    fun parseJmaForecast(body: String, areaCode: String): JmaForecast? {
        val root = MiniJson.arr(MiniJson.parseOrNull(body)) ?: return null
        val first = MiniJson.obj(root.firstOrNull()) ?: return null
        val series = MiniJson.arr(first["timeSeries"]).orEmpty()
        var weatherText: String? = null
        var popText: String? = null
        var maxTemp: String? = null
        var minTemp: String? = null
        for (s in series) {
            val sm = MiniJson.obj(s) ?: continue
            val areas = MiniJson.arr(sm["areas"]).orEmpty()
            for (a in areas) {
                val am = MiniJson.obj(a) ?: continue
                val code = MiniJson.obj(am["area"])?.let { MiniJson.str(it, "code") } ?: ""
                if (areaCode.isNotEmpty() && code != areaCode) continue
                val weathers = MiniJson.arr(am["weathers"])
                if (weathers != null && weatherText == null) {
                    weatherText = (weathers.firstOrNull() as? String)?.replace("　", " ")
                }
                val pops = MiniJson.arr(am["pops"])
                if (pops != null && popText == null && pops.isNotEmpty()) {
                    val vals = pops.mapNotNull { it as? String }.filter { it.isNotEmpty() }
                    if (vals.isNotEmpty()) popText = "降水確率 " + vals.joinToString(" / ") + "%"
                }
                val temps = MiniJson.arr(am["temps"])
                if (temps != null && temps.size >= 2 && maxTemp == null) {
                    minTemp = temps[0] as? String
                    maxTemp = temps[1] as? String
                }
            }
        }
        val w = weatherText ?: return null
        return JmaForecast(w, maxTemp, minTemp, popText)
    }

    // 📥 Open-Meteo の現在気象を短い文字列にする
    fun parseOpenMeteoCurrent(body: String): String? {
        val root = MiniJson.obj(MiniJson.parseOrNull(body)) ?: return null
        val cur = MiniJson.obj(root["current"]) ?: return null
        val temp = MiniJson.numOrNull(cur, "temperature_2m") ?: return null
        val hum = MiniJson.intOrNull(cur, "relative_humidity_2m")
        val sb = StringBuilder()
        sb.append(temp).append("℃")
        if (hum != null) sb.append(" / 湿度").append(hum).append("%")
        return sb.toString()
    }
}
