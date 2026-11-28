package jp.aegis.client.a2s

import jp.aegis.client.places.Places
import jp.aegis.client.weather.Place

// ========================================
// ⏱️ EEW 到達カウントダウン
// ========================================
// 📌 登録地点ごとの主要動到達予想と残り秒数の表示。本文の土台は
// DisplayText.eewBody (静的表示) で、ここは到達予想の計算と
// カウントダウン行の追加だけを担う純粋関数群
// 📝 旧実装の AegisEewImpl の到達推定と同一: 到達予想時刻があればそれを
// 使い、無ければ震源距離と深さから S 波 3.5km/s で推定する

object EewCountdown {

    // 🚨 登録地点に対応する EEW 対象地域: 細分区域名の一致を優先し、無ければ
    // 都道府県一致の区域 (複数ある場合は予想震度が最大の区域 = 安全側) を返す
    fun eewAreaForLocation(e: EewPayload, place: Place): EewAreaScale? {
        // 📝 まず都道府県で絞る (「南部」等は他県にも同名区域があるため、県を跨いだ区域名照合はしない)
        val prefName = Places.prefNameForCode(place.prefCode)
        val inPref = e.areaScales.filter { it.pref.isNotEmpty() && it.pref == prefName }
        if (place.areaName.isNotEmpty()) {
            for (a in inPref) {
                if (a.name.contains(place.areaName)) return a
            }
        }
        if (inPref.size == 1) return inPref[0]
        if (inPref.size > 1) return inPref.maxByOrNull { it.scale }
        return null
    }

    // 🌏 地点ごとの地域別予想震度の表示文: 予想であることを明示する。
    // 対象地域が無い / 予想震度が不明な地点は出さない
    fun localForecastText(e: EewPayload, places: List<Place>): String {
        val lines = ArrayList<String>()
        for (place in places) {
            val a = eewAreaForLocation(e, place) ?: continue
            if (a.scale < 0) continue
            lines.add(place.name + ": 予想震度 " + scaleLabel(a.scale) + "（推定・対象地域「" + a.name + "」）")
        }
        return lines.joinToString("\n")
    }

    // 📍 地点への主要動到達予想 (epoch ミリ秒)。該当なしは -1。
    // estimatedOut[0] に推定 (S 波計算) かどうかを返す
    fun arrivalMillis(e: EewPayload, place: Place, estimatedOut: BooleanArray?): Long {
        if (estimatedOut != null && estimatedOut.isNotEmpty()) estimatedOut[0] = false
        val prefName = Places.prefNameForCode(place.prefCode)
        var matchPref: String? = null
        if (prefName.isNotEmpty()) {
            for (a in e.areaArrivals) {
                if (prefName == a.pref) {
                    matchPref = a.pref
                    break
                }
            }
        }
        if (matchPref == null && place.areaName.isNotEmpty()) {
            // 📝 都道府県別の到達予想が無い場合は、区域名が一致する地域の都道府県で引く
            for (a in e.areaScales) {
                if (a.name.contains(place.areaName)) {
                    matchPref = a.pref
                    break
                }
            }
        }
        if (matchPref != null) {
            val arr = e.areaArrivals.firstOrNull { it.pref == matchPref }
            if (arr != null && arr.arrivalTime.isNotEmpty()) {
                val t = A2sParse.parseJstMillis(arr.arrivalTime)
                if (t > 0) return t
            }
        }
        val lat = e.lat
        val lon = e.lon
        val depth = e.depthKm
        val originMillis = A2sParse.parseJstMillis(e.originTime)
        if (lat != null && lon != null && lat != 0.0 && lon != 0.0 &&
            depth != null && depth >= 0 && originMillis > 0
        ) {
            val epi = Places.haversineKm(place.lat, place.lon, lat, lon)
            val hypo = Math.sqrt(epi * epi + depth * depth)
            if (estimatedOut != null && estimatedOut.isNotEmpty()) estimatedOut[0] = true
            return originMillis + (hypo / 3.5 * 1000.0).toLong()
        }
        return -1
    }

    // 📝 カウントダウンがまだ意味を持つか (到達前、または到達後 30 秒以内)。
    // 取消報では常に false
    fun countdownActive(e: EewPayload?, places: List<Place>, now: Long): Boolean {
        if (e == null || e.cancel) return false
        val est = BooleanArray(1)
        for (place in places) {
            val arr = arrivalMillis(e, place, est)
            if (arr > 0 && now < arr + 30 * 1000L) return true
        }
        return false
    }

    // 🖥️ カウントダウンカードの本文 (1 秒ごとに再計算される):
    // 静的本文 + 地点別の予想震度 + 地点別の到達カウントダウン行
    fun countdownBody(e: EewPayload, places: List<Place>, now: Long): String {
        val sb = StringBuilder(DisplayText.eewBody(e, emptyList()))
        val fc = localForecastText(e, places)
        if (fc.isNotEmpty()) sb.append("\n").append(fc)
        val est = BooleanArray(1)
        for (place in places) {
            val arr = arrivalMillis(e, place, est)
            if (arr <= 0) continue
            if (now < arr) {
                val sec = (arr - now + 999) / 1000
                sb.append("\n⏱ ").append(place.name).append(": あと約").append(sec).append("秒")
                if (est[0]) sb.append("（推定）")
            } else if (now < arr + 30 * 1000L) {
                sb.append("\n⏱ ").append(place.name).append(": 到達した可能性が高いです")
            }
        }
        return sb.toString()
    }
}
