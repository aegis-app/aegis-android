package jp.aegis.client.radar

import jp.aegis.client.json.MiniJson
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ========================================
// 🌧️ 雨雲レーダー (気象庁ナウキャスト) のタイル計算・解析
// ========================================
// 📌 slippy map (XYZ) のタイル番号計算、ナウキャスト / RainViewer の
// フレーム解析、鮮度表示などの純粋関数群。画像の合成は RadarComposer
// 📝 旧実装の AegisRadarImpl と同一の計算 (出典 URL・ズーム・鮮度閾値も同じ)

object Radar {

    // 🕐 鮮度の閾値 (分): これを超えた観測は古い旨を注記する
    const val STALE_MINUTES: Long = 20L

    // 🌦️ 気象庁ナウキャストのフレーム一覧 (先頭が最新)
    const val JMA_NOWCAST_TARGET_TIMES_URL =
        "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json"

    // 📡 RainViewer のフレーム一覧
    const val RAINVIEWER_MAPS_URL = "https://api.rainviewer.com/public/weather-maps.json"

    // 📝 slippy map (XYZ) のタイル番号
    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val x = Math.floor((lon + 180.0) / 360.0 * n).toInt()
        return Math.max(0, Math.min(n - 1, x))
    }

    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)))
        val y = Math.floor((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n).toInt()
        return Math.max(0, Math.min(n - 1, y))
    }

    // 📍 小数タイル座標: 地点がタイル内のどこにあるかを求めるための端数つき座標
    fun tileXFloat(lon: Double, z: Int): Double {
        val n = (1 shl z).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        return Math.max(0.0, Math.min(n, x))
    }

    fun tileYFloat(lat: Double, z: Int): Double {
        val n = (1 shl z).toDouble()
        val rad = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)))
        val y = (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n
        return Math.max(0.0, Math.min(n, y))
    }

    // 📍 合成キャンバス (原点タイル ox, oy の 3×3 タイル) 上での地点のピクセル位置 [x, y]。
    // キャンバス中心固定ではなく、地点のタイル内位置まで反映する
    fun markerPixel(lat: Double, lon: Double, z: Int, ox: Int, oy: Int, tileSize: Int): FloatArray {
        val x = (tileXFloat(lon, z) - ox) * tileSize
        val y = (tileYFloat(lat, z) - oy) * tileSize
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    // 🕐 フレーム時刻から現在までの経過分数 (未来・不正値は 0)
    fun radarAgeMinutes(frameEpochSec: Long, nowMillis: Long): Long {
        if (frameEpochSec <= 0) return 0
        val diff = nowMillis - frameEpochSec * 1000L
        return if (diff <= 0) 0 else diff / 60000L
    }

    // 🖥️ 「約N分前の観測」表示。閾値 (20分) を超えた場合は古い旨の注記を付ける
    fun radarFreshnessText(frameEpochSec: Long, nowMillis: Long): String {
        val age = radarAgeMinutes(frameEpochSec, nowMillis)
        val base = "約" + age + "分前の観測"
        return if (age > STALE_MINUTES) {
            base + "（" + STALE_MINUTES + "分を超えており古い観測です。最新の状況と異なる場合があります）"
        } else {
            base
        }
    }

    // 🌦️ 気象庁ナウキャストのタイル URL
    fun jmaNowcastTileUrl(basetime: String, validtime: String, z: Int, x: Int, y: Int): String {
        return "https://www.jma.go.jp/bosai/jmatile/data/nowc/" + basetime + "/none/" + validtime +
            "/surf/hrpns/" + z + "/" + x + "/" + y + ".png"
    }

    // 🗾 国土地理院 (標準地図) のタイル URL
    fun gsiTileUrl(z: Int, x: Int, y: Int): String {
        return "https://cyberjapandata.gsi.go.jp/xyz/std/" + z + "/" + x + "/" + y + ".png"
    }

    // 📡 RainViewer の雨タイル URL (ズーム7固定)
    fun rainViewerTileUrl(path: String, tx: Int, ty: Int): String {
        return "https://tilecache.rainviewer.com" + path + "/256/7/" + tx + "/" + ty + "/2/1_1.png"
    }

    // 🌦️ ナウキャストのフレーム (basetime / validtime は "yyyyMMddHHmmss")
    data class JmaFrame(val basetime: String, val validtime: String)

    // 📝 targetTimes JSON から最新のフレームを取り出す。配列の先頭が最新 (降順)。
    // 解釈できなければ null
    fun parseJmaNowcastLatest(json: String): JmaFrame? {
        val arr = MiniJson.arr(MiniJson.parseOrNull(json)) ?: return null
        val first = MiniJson.obj(arr.firstOrNull()) ?: return null
        val basetime = MiniJson.str(first, "basetime")
        val validtime = MiniJson.str(first, "validtime")
        if (basetime.length < 14 || validtime.length < 14) return null
        return JmaFrame(basetime, validtime)
    }

    // 📡 RainViewer のフレーム (path と観測時刻 epoch 秒)
    data class RainFrame(val path: String, val epochSec: Long)

    // 📝 RainViewer の weather-maps JSON から最新の過去フレームを取り出す。
    // radar.past の末尾が最新。解釈できなければ null
    fun parseRainViewerLatest(json: String): RainFrame? {
        val root = MiniJson.obj(MiniJson.parseOrNull(json)) ?: return null
        val radar = MiniJson.obj(root["radar"]) ?: return null
        val past = MiniJson.arr(radar["past"]).orEmpty()
        val last = MiniJson.obj(past.lastOrNull()) ?: return null
        val path = MiniJson.str(last, "path")
        if (path.isEmpty()) return null
        return RainFrame(path, MiniJson.numOrNull(last, "time")?.toLong() ?: 0L)
    }

    // 📝 "yyyyMMddHHmmss" (JST) を epoch 秒にする (失敗時 0)
    fun jmaTime14ToEpochSec(s: String): Long {
        if (s.length < 14) return 0
        return try {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)
            fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            val d = fmt.parse(s.substring(0, 14))
            if (d != null) d.time / 1000L else 0
        } catch (e: Exception) {
            0
        }
    }

    // 🌐 タイル部分欠落の注記: 期待枚数の一部しか取得できなかった場合に注記する。
    // 全数取得・期待0枚なら ""、全滅時は呼び出し側の既存注記に任せるため "" を返す
    fun tileMissingNote(label: String, expected: Int, got: Int): String {
        if (expected <= 0 || got <= 0 || got >= expected) return ""
        return "（" + label + "の一部を取得できませんでした：" + got + "/" + expected + "枚）"
    }

    // 🌧️ 雨タイル (RainViewer はズーム7までしか無い) が、ベース地図 (ズーム8・原点 ox,oy の
    // 3×3タイル = 768×768) をカバーする範囲を返す。
    // 各要素は [tx, ty, 描画X, 描画Y] で、z7タイルは 512×512 に拡大して描画する。
    // 描画位置が負になったりキャンバス外にはみ出すのは正常 (描画側でクリッピングされる)
    fun rainTilesFor(ox: Int, oy: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val tx0 = Math.floorDiv(ox, 2)
        val tx1 = Math.floorDiv(ox + 2, 2)
        val ty0 = Math.floorDiv(oy, 2)
        val ty1 = Math.floorDiv(oy + 2, 2)
        for (tx in tx0..tx1) {
            for (ty in ty0..ty1) {
                out.add(intArrayOf(tx, ty, (2 * tx - ox) * 256, (2 * ty - oy) * 256))
            }
        }
        return out
    }
}
