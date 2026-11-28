package jp.aegis.client.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import jp.aegis.client.a2s.A2sHttp
import jp.aegis.client.weather.Place
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// ========================================
// 🌧️ レーダー画像の合成 (Android 層)
// ========================================
// 📌 選択地点を中心にズーム8・3×3タイル (地理院地図) へレーダーを合成する。
// 第一ソースは気象庁 降水ナウキャスト (z8 原寸)。取得できない場合は
// RainViewer にフォールバックする (z7 タイルを 512×512 に拡大描画し、
// 実効解像度が低下する旨をキャプションに注記する)
// 📝 旧実装の AegisRadarImpl.radarImage と同一の合成手順・キャプション

// 🖼️ 合成結果 (画像 + 説明文)
data class RadarResult(val bitmap: Bitmap, val caption: String)

object RadarComposer {

    // 🧵 タイル取得用の並行プール (合成のたびに使い回す)
    private val pool = Executors.newCachedThreadPool()

    // 📡 バイナリ用取得 (レーダータイル等の画像): テキスト化せず、リトライもしない
    fun fetchBytes(urlStr: String): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "AEGIS-Android/1.8")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode)
            var ins = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) ins = GZIPInputStream(ins)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var r = ins.read(buf)
            while (r != -1) {
                bos.write(buf, 0, r)
                r = ins.read(buf)
            }
            return bos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    // 🖼️ タイル 1 枚の取得 (失敗時は null)
    private fun fetchTile(url: String): Bitmap? {
        return try {
            val bytes = fetchBytes(url)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // 🌧️ 地点を中心にレーダー画像を合成する (通信するのでワーカースレッドで呼ぶこと)
    fun radarImage(place: Place): RadarResult {
        val z = 8
        val tile = 256
        val cx = Radar.tileX(place.lon, z)
        val cy = Radar.tileY(place.lat, z)
        val ox = cx - 1
        val oy = cy - 1
        // 🌦️ 気象庁ナウキャストの最新フレーム (失敗時は null のまま RainViewer にフォールバック)
        var jmaFrame: Radar.JmaFrame? = null
        try {
            jmaFrame = Radar.parseJmaNowcastLatest(A2sHttp.fetchOnce(Radar.JMA_NOWCAST_TARGET_TIMES_URL, ""))
        } catch (e: Exception) {
            // フォールバック側で処理する
        }
        val futures = HashMap<String, Future<Bitmap?>>()
        var baseExpected = 0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
                baseExpected++
                futures["b$dx,$dy"] = pool.submit(Callable { fetchTile(Radar.gsiTileUrl(z, x, y)) })
            }
        }
        if (jmaFrame != null) {
            val bt = jmaFrame.basetime
            val vt = jmaFrame.validtime
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
                    futures["j$dx,$dy"] = pool.submit(Callable { fetchTile(Radar.jmaNowcastTileUrl(bt, vt, z, x, y)) })
                }
            }
        }
        val canvas = Bitmap.createBitmap(tile * 3, tile * 3, Bitmap.Config.ARGB_8888)
        val cv = Canvas(canvas)
        cv.drawColor(Color.parseColor("#0B1220"))
        // 🌧️ ベースタイルを全て描いてから、雨タイルを重ねる
        var baseGot = 0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val px = (dx + 1) * tile
                val py = (dy + 1) * tile
                try {
                    val b = futures["b$dx,$dy"]?.get(30, TimeUnit.SECONDS)
                    if (b != null) {
                        cv.drawBitmap(b, px.toFloat(), py.toFloat(), null)
                        baseGot++
                    }
                } catch (e: Exception) {
                    // 欠けたタイルは背景色のまま
                }
            }
        }
        // 🌦️ 気象庁ナウキャスト (z8 原寸) を重ねる
        var radarOk = 0
        var radarExpected = 0
        var useJma = false
        var epochSec = 0L
        if (jmaFrame != null) {
            var jmaGot = 0
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
                    radarExpected++
                    try {
                        val b = futures["j$dx,$dy"]?.get(30, TimeUnit.SECONDS)
                        if (b != null) {
                            cv.drawBitmap(b, (dx + 1) * tile.toFloat(), (dy + 1) * tile.toFloat(), null)
                            jmaGot++
                        }
                    } catch (e: Exception) {
                        // 欠けたタイルはそのまま
                    }
                }
            }
            if (jmaGot > 0) {
                useJma = true
                radarOk = jmaGot
                epochSec = Radar.jmaTime14ToEpochSec(jmaFrame.validtime)
            } else {
                radarExpected = 0 // フォールバック側で数え直す
            }
        }
        // 📝 フォールバック: RainViewer (z7 を 512×512 に拡大)
        if (!useJma) {
            var rainFrame: Radar.RainFrame? = null
            try {
                rainFrame = Radar.parseRainViewerLatest(A2sHttp.fetchOnce(Radar.RAINVIEWER_MAPS_URL, ""))
            } catch (e: Exception) {
                // フレームが無ければレーダーなしで返す
            }
            if (rainFrame != null) {
                epochSec = rainFrame.epochSec
                val rainTiles = Radar.rainTilesFor(ox, oy)
                val rainFutures = HashMap<Int, Future<Bitmap?>>()
                for (i in rainTiles.indices) {
                    val tx = rainTiles[i][0]
                    val ty = rainTiles[i][1]
                    if (tx < 0 || ty < 0 || tx >= (1 shl 7) || ty >= (1 shl 7)) continue
                    radarExpected++
                    val pathF = rainFrame.path
                    rainFutures[i] = pool.submit(Callable { fetchTile(Radar.rainViewerTileUrl(pathF, tx, ty)) })
                }
                val rainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (i in rainTiles.indices) {
                    val rt = rainTiles[i]
                    if (rt[0] < 0 || rt[1] < 0 || rt[0] >= (1 shl 7) || rt[1] >= (1 shl 7)) continue
                    try {
                        val r = rainFutures[i]?.get(30, TimeUnit.SECONDS)
                        if (r != null) {
                            cv.drawBitmap(r, null, Rect(rt[2], rt[3], rt[2] + 512, rt[3] + 512), rainPaint)
                            radarOk++
                        }
                    } catch (e: Exception) {
                        // 欠けたタイルはそのまま
                    }
                }
            }
        }
        // 📍 地点マーカー (二重丸): 小数タイル座標から算出した地点の実ピクセル位置に描く
        val marker = Radar.markerPixel(place.lat, place.lon, z, ox, oy, tile)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.WHITE
        cv.drawCircle(marker[0], marker[1], 26f, paint)
        paint.strokeWidth = 4f
        paint.color = Color.parseColor("#FF5252")
        cv.drawCircle(marker[0], marker[1], 17f, paint)
        paint.style = Paint.Style.FILL
        cv.drawCircle(marker[0], marker[1], 5f, paint)
        val fmt = SimpleDateFormat("M月d日 H:mm", Locale.JAPAN)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        // 🌦️ 出典と解像度の注記: 気象庁は z8 原寸、RainViewer は z7 の2倍拡大で解像度が低下する
        val sourceNote = if (useJma) {
            "\nレーダー: 気象庁 降水ナウキャスト（ズーム8原寸） / 地図: 国土地理院"
        } else {
            "\nレーダー: RainViewer（気象庁データ） / 地図: 国土地理院" +
                "\n（気象庁ナウキャストを取得できなかったため RainViewer で表示しています。" +
                "RainViewer はズーム7のタイルを2倍拡大するため、実効解像度が低下します）"
        }
        // 📝 タイル部分欠落の注記: 全滅時だけでなく一部欠落でも明示する
        val missingNote = Radar.tileMissingNote("地図タイル", baseExpected, baseGot) +
            Radar.tileMissingNote("レーダータイル", radarExpected, radarOk)
        val caption = (if (epochSec > 0) {
            "レーダー観測時刻： " + fmt.format(Date(epochSec * 1000L)) +
                "（" + Radar.radarFreshnessText(epochSec, System.currentTimeMillis()) + "）"
        } else {
            "レーダー観測時刻： 不明"
        }) +
            sourceNote +
            (if (radarOk == 0) "\n（レーダー画像を取得できませんでした）" else "") +
            (if (missingNote.isNotEmpty()) "\n" + missingNote else "")
        return RadarResult(canvas, caption)
    }
}
