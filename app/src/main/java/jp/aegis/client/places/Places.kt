package jp.aegis.client.places

import jp.aegis.client.json.MiniJson

// ========================================
// 📍 都道府県テーブルと地点まわりの判定
// ========================================
// 📌 気象庁の都道府県コード・正式名称・県庁所在地の一覧と、座標から
// 都道府県を推定する純粋関数群。GPS 地点登録 (最も近い県庁所在地の
// 都道府県を推定) と EEW の対象地域照合 (都道府県名) の土台になる
// 📝 旧実装の AegisDataPlaces / AegisData の都道府県テーブルと同一の内容

// 🗾 都道府県 (気象庁コード・正式名称・県庁所在地の緯度経度)
data class Pref(
    val code: String,   // 気象庁の都道府県コード (例: 群馬県 = "100000")
    val name: String,   // 正式名称 (例: "群馬県")
    val capLat: Double, // 県庁所在地の緯度
    val capLon: Double  // 県庁所在地の経度
)

object Places {

    // 🗾 全 47 都道府県 (コード順)
    val PREFS: List<Pref> = listOf(
        Pref("010000", "北海道", 43.0642, 141.3469), Pref("020000", "青森県", 40.8244, 140.7400),
        Pref("030000", "岩手県", 39.7019, 141.1545), Pref("040000", "宮城県", 38.2682, 140.8694),
        Pref("050000", "秋田県", 39.7186, 140.1024), Pref("060000", "山形県", 38.2404, 140.3633),
        Pref("070000", "福島県", 37.7503, 140.4676), Pref("080000", "茨城県", 36.3418, 140.4468),
        Pref("090000", "栃木県", 36.5657, 139.8836), Pref("100000", "群馬県", 36.3895, 139.0634),
        Pref("110000", "埼玉県", 35.8617, 139.6455), Pref("120000", "千葉県", 35.6074, 140.1065),
        Pref("130000", "東京都", 35.6895, 139.6917), Pref("140000", "神奈川県", 35.4478, 139.6425),
        Pref("150000", "新潟県", 37.9024, 139.0232), Pref("160000", "富山県", 36.6953, 137.2113),
        Pref("170000", "石川県", 36.5947, 136.6256), Pref("180000", "福井県", 36.0652, 136.2216),
        Pref("190000", "山梨県", 35.6642, 138.5684), Pref("200000", "長野県", 36.6513, 138.1810),
        Pref("210000", "岐阜県", 35.3912, 136.7223), Pref("220000", "静岡県", 34.9769, 138.3831),
        Pref("230000", "愛知県", 35.1815, 136.9066), Pref("240000", "三重県", 34.7303, 136.5086),
        Pref("250000", "滋賀県", 35.0045, 135.8686), Pref("260000", "京都府", 35.0210, 135.7556),
        Pref("270000", "大阪府", 34.6863, 135.5200), Pref("280000", "兵庫県", 34.6913, 135.1830),
        Pref("290000", "奈良県", 34.6851, 135.8048), Pref("300000", "和歌山県", 34.2260, 135.1675),
        Pref("310000", "鳥取県", 35.5039, 134.2377), Pref("320000", "島根県", 35.4723, 133.0505),
        Pref("330000", "岡山県", 34.6618, 133.9350), Pref("340000", "広島県", 34.3966, 132.4596),
        Pref("350000", "山口県", 34.1861, 131.4706), Pref("360000", "徳島県", 34.0658, 134.5593),
        Pref("370000", "香川県", 34.3401, 134.0434), Pref("380000", "愛媛県", 33.8416, 132.7661),
        Pref("390000", "高知県", 33.5597, 133.5311), Pref("400000", "福岡県", 33.6064, 130.4183),
        Pref("410000", "佐賀県", 33.2494, 130.2988), Pref("420000", "長崎県", 32.7448, 129.8737),
        Pref("430000", "熊本県", 32.7898, 130.7417), Pref("440000", "大分県", 33.2382, 131.6126),
        Pref("450000", "宮崎県", 31.9111, 131.4202), Pref("460000", "鹿児島県", 31.5602, 130.5581),
        Pref("470000", "沖縄県", 26.2124, 127.6809)
    )

    // 🚨 気象庁の都道府県コード → 正式名称 (EEW の対象地域照合などに使う)。
    // 該当なし・null は ""
    fun prefNameForCode(prefCode: String?): String {
        if (prefCode != null) {
            for (p in PREFS) if (p.code == prefCode) return p.name
        }
        return ""
    }

    // 📝 2 点間の球面距離 (km)
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // 📍 県庁所在地が最も近い都道府県を推定する (GPS 地点登録用)
    fun nearestPref(lat: Double, lon: Double): Pref {
        var best: Pref? = null
        var bestKm = Double.MAX_VALUE
        for (p in PREFS) {
            val d = haversineKm(lat, lon, p.capLat, p.capLon)
            if (d < bestKm) {
                bestKm = d
                best = p
            }
        }
        return best!!
    }

    // 📍 カスタム地点の座標検証 (純粋関数): 範囲外なら理由を返し、範囲内なら "" を返す。
    // NaN / 無限大も範囲外として扱う (誤入力がそのまま別地点の予報になるのを防ぐ)
    fun coordRangeError(lat: Double, lon: Double): String {
        if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) {
            return "緯度・経度が不正です"
        }
        if (lat < -90.0 || lat > 90.0) return "緯度は -90〜90 の範囲で入力してください"
        if (lon < -180.0 || lon > 180.0) return "経度は -180〜180 の範囲で入力してください"
        return ""
    }

    // ⚠️ 日本域外の警告 (純粋関数): 日本のおおよその範囲 (緯度20〜46、経度122〜154) を
    // 外れる場合は警告文を返し、範囲内なら "" を返す。登録自体は妨げない
    fun coordJapanWarning(lat: Double, lon: Double): String {
        if (lat < 20.0 || lat > 46.0 || lon < 122.0 || lon > 154.0) {
            return "日本国外の座標です。気象庁の予報・警報の対象外となる場合があります"
        }
        return ""
    }

    // 📍 予報 JSON の一次細分区域 (名称 + コード)
    data class ForecastArea(val name: String, val code: String)

    // 📝 気象庁予報 JSON から一次細分区域の一覧を取り出す (純粋関数)。
    // 先頭要素の timeSeries[0].areas を読む。解釈できなければ空の一覧
    fun parseForecastAreas(json: String): List<ForecastArea> {
        val root = MiniJson.arr(MiniJson.parseOrNull(json)) ?: return emptyList()
        val first = MiniJson.obj(root.firstOrNull()) ?: return emptyList()
        val series = MiniJson.arr(first["timeSeries"]).orEmpty()
        val firstSeries = MiniJson.obj(series.firstOrNull()) ?: return emptyList()
        val areas = MiniJson.arr(firstSeries["areas"]).orEmpty()
        val out = ArrayList<ForecastArea>()
        for (a in areas) {
            val area = MiniJson.obj(MiniJson.obj(a)?.get("area")) ?: continue
            val name = MiniJson.str(area, "name")
            if (name.isEmpty()) continue
            out.add(ForecastArea(name, MiniJson.str(area, "code")))
        }
        return out
    }

    // 📍 一次細分区域名の一覧 (重複なし、出現順)。GPS 地点登録の地域選択用
    fun forecastAreaNames(json: String): List<String> {
        val names = ArrayList<String>()
        for (a in parseForecastAreas(json)) {
            if (!names.contains(a.name)) names.add(a.name)
        }
        return names
    }
}
