package jp.aegis.client.a2s

// ========================================
// 📝 イベントの表示文・通知文の組み立て
// ========================================
// 📌 A2sEvent から画面表示と通知に使うタイトル・本文を作る純粋関数群。
// データ源に無い項目は作らない (不明な数値は「不明」と明示する)
object DisplayText {

    // 🏷️ 一覧表示用の短いタイトル
    fun title(ev: A2sEvent): String = when (ev.kind) {
        "quake" -> "🌍 地震 " + (ev.quake?.hypocenter ?: "") +
            " 最大震度 " + scaleLabel(ev.quake?.maxScale ?: -1)
        "eew" -> eewTitle(ev.eew)
        "tsunami" -> if (ev.tsunami?.cancelled == true) "🌊 津波情報 (解除)" else "🌊 津波情報"
        "warning" -> "⚠️ " + (ev.warning?.prefName ?: "") + " " + (ev.warning?.areaName ?: "") +
            " 警報・注意報"
        "typhoon" -> "🌀 台風 " + (ev.typhoon?.name ?: "")
        else -> {
            val g = ev.generic
            val t = if (g != null && g.title.isNotEmpty()) g.title else kindLabel(ev.kind)
            "📢 " + t
        }
    }

    // 📄 詳細表示用の本文
    fun body(ev: A2sEvent): String = when (ev.kind) {
        "quake" -> quakeBody(ev.quake)
        "eew" -> eewBody(ev.eew, emptyList())
        "tsunami" -> tsunamiBody(ev.tsunami)
        "warning" -> warningBody(ev.warning)
        "typhoon" -> typhoonBody(ev.typhoon)
        else -> genericBody(ev.generic)
    }

    // ----------------------------------------
    // 🌍 地震
    // ----------------------------------------

    fun quakeBody(q: QuakePayload?): String {
        if (q == null) return ""
        val sb = StringBuilder()
        sb.append("発生: ").append(q.time).append('\n')
        sb.append("震源: ").append(q.hypocenter).append('\n')
        sb.append("最大震度: ").append(scaleLabel(q.maxScale)).append('\n')
        sb.append("規模: ").append(
            if (q.magnitude != null) "M" + trimNum(q.magnitude) else "不明"
        ).append('\n')
        sb.append("深さ: ").append(
            when {
                q.depthKm == null -> "不明"
                q.depthKm == 0 -> "ごく浅い"
                else -> "約" + q.depthKm + "km"
            }
        ).append('\n')
        if (q.tsunamiText.isNotEmpty()) sb.append(q.tsunamiText).append('\n')
        // 🗾 県別の最大震度 (地域点があればそちらを優先し、無ければ prefScales)
        val areaPoints = q.points.filter { it.isArea }
        if (areaPoints.isNotEmpty()) {
            sb.append("各地の震度: ")
            sb.append(areaPoints.take(12).joinToString("、") { it.pref + " " + scaleLabel(it.scale) })
            sb.append('\n')
        } else if (q.prefScales.isNotEmpty()) {
            sb.append("各地の震度: ")
            sb.append(q.prefScales.take(12).joinToString("、") { it.pref + " " + scaleLabel(it.scale) })
            sb.append('\n')
        }
        sb.append("発表: ").append(q.announcedTime)
        return sb.toString()
    }

    // ----------------------------------------
    // 🚨 緊急地震速報 (EEW)
    // ----------------------------------------

    fun eewTitle(e: EewPayload?): String {
        if (e == null) return "🚨 緊急地震速報"
        return when {
            e.cancel -> "🚨 緊急地震速報 (取消)"
            e.isWarn -> "🚨 緊急地震速報 (警報)"
            else -> "🚨 緊急地震速報 (予報)"
        }
    }

    // 📝 prefs は監視地点の都道府県名。到達予想がある地点だけ併記する
    fun eewBody(e: EewPayload?, prefs: List<String>): String {
        if (e == null) return ""
        if (e.cancel) return "先ほどの緊急地震速報は取り消されました。"
        val sb = StringBuilder()
        sb.append("発生: ").append(e.originTime).append('\n')
        sb.append("震源: ").append(e.hypocenter.ifEmpty { "不明" }).append('\n')
        sb.append("推定最大震度: ").append(scaleLabel(e.estScale)).append('\n')
        if (e.serial != null) {
            sb.append("第").append(e.serial).append("報")
            if (e.isFinal) sb.append(" (最終報)")
            sb.append('\n')
        }
        if (e.isAssumption) sb.append("仮定震源要素による速報です\n")
        // 🗾 地点のある都道府県の到達予想
        for (pref in prefs) {
            val arr = e.areaArrivals.firstOrNull { it.pref == pref } ?: continue
            if (arr.arrivalTime.isNotEmpty()) {
                sb.append(pref).append(" 到達予想: ").append(arr.arrivalTime).append('\n')
            }
        }
        // 📌 行動文言は推定最大震度と警報かどうかで出し分ける (過剰な促しを避ける)
        sb.append(eewActionText(e.estScale, e.isWarn))
        return sb.toString()
    }

    // 📌 EEW の行動文言: 震度5弱以上は強い文言、震度4は中間、震度3以下は
    // 注意喚起のみ。震度不明の場合は警報なら強い文言、予報なら注意喚起のみ
    fun eewActionText(estScale: Int, isWarn: Boolean): String = when {
        estScale >= 45 -> "強い揺れに備え、頭を守り安全な場所へ移動してください。"
        estScale == 40 -> "やや強い揺れに備えてください。落下物に注意し、頭を守ってください。"
        estScale in 0..39 -> "揺れに注意してください。"
        else -> if (isWarn) "強い揺れに備え、頭を守り安全な場所へ移動してください。" else "揺れに注意してください。"
    }

    // 🚨 EEW が表示・通知に値する新鮮さか: 発表から 15 分以内、かつ未来すぎないこと。
    // 古い EEW を遅れて表示・通知しないための鮮度ガード
    fun isEewFresh(e: EewPayload, nowMillis: Long): Boolean {
        val report = A2sParse.parseJstMillis(e.time)
        if (report <= 0) return false
        val age = nowMillis - report
        return age in -60_000L..(15 * 60_000L)
    }

    // ----------------------------------------
    // 🌊 津波
    // ----------------------------------------

    fun tsunamiBody(t: TsunamiPayload?): String {
        if (t == null) return ""
        if (t.cancelled) return "津波警報・注意報は解除されました。"
        if (t.areas.isEmpty()) return "現在、発表されている津波警報・注意報はありません。"
        val sb = StringBuilder()
        for (a in t.areas) {
            sb.append(a.name)
            if (a.grade.isNotEmpty()) sb.append(" ").append(a.grade)
            val height = a.maxHeightLabel ?: a.maxHeightValue?.let { "予想 " + trimNum(it) + "m" }
            if (height != null) sb.append(" / ").append(height)
            if (a.arrivalLabel != null) sb.append(" / 到達 ").append(a.arrivalLabel)
            sb.append('\n')
        }
        sb.append("発表: ").append(t.time)
        return sb.toString()
    }

    // ----------------------------------------
    // ⚠️ 警報・注意報 / 🌀 台風 / 📄 共通
    // ----------------------------------------

    fun warningBody(w: WarningPayload?): String {
        if (w == null) return ""
        if (w.released) return "警報・注意報は解除されました。"
        val sb = StringBuilder()
        if (w.headline.isNotEmpty()) sb.append(w.headline).append('\n')
        for (item in w.warnings) {
            sb.append(item.name)
            if (item.status.isNotEmpty()) sb.append(" (").append(item.status).append(")")
            sb.append('\n')
        }
        sb.append("発表: ").append(w.time.ifEmpty { w.reportDatetime })
        return sb.toString()
    }

    fun typhoonBody(t: TyphoonPayload?): String {
        if (t == null) return ""
        if (t.lines.isNotEmpty()) return t.lines.joinToString("\n")
        // 📝 lines が無い場合だけ、名称・英語名・番号から組み立てる
        val parts = ArrayList<String>()
        if (t.name.isNotEmpty()) parts.add(t.name)
        if (t.englishName.isNotEmpty()) parts.add(t.englishName)
        if (t.number != null) parts.add("第" + t.number + "号")
        return parts.joinToString(" ")
    }

    fun genericBody(g: GenericPayload?): String {
        if (g == null) return ""
        if (g.released) return "解除されました。"
        val sb = StringBuilder()
        if (g.title.isNotEmpty()) sb.append(g.title).append('\n')
        if (g.lines.isNotEmpty()) sb.append(g.lines.joinToString("\n")).append('\n')
        if (g.time.isNotEmpty()) sb.append("発表: ").append(g.time)
        return sb.toString().trim()
    }

    // 🔢 数値の表示 (5.0 → "5"、5.25 → "5.25" のように末尾ゼロを落とす)
    fun trimNum(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
