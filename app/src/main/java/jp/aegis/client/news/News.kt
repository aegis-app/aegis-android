package jp.aegis.client.news

import jp.aegis.client.a2s.A2sHttp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ========================================
// 🗞️ ニュース (NHK RSS) の取得・整形
// ========================================
// 📌 NHK の主要 (cat0) + 社会 (cat1) RSS を取得し、防災関連を優先して
// 最大 8 件に絞る。RSS の解析は XmlPullParser (Android 専用) を使わず
// 自前の走査で行い、JVM 単体テストで検証できるようにしている
// 📝 旧実装の AegisNewsImpl と同一の選別規則・要約整形

// 📰 ニュース 1 件
data class NewsItem(
    val title: String,   // 見出し
    val timeText: String, // 配信時刻 "H:mm" (JST、解釈できなければ "")
    val summary: String, // 要約 (HTML 除去 + 80 字切り詰め)
    val link: String     // 記事 URL (重複排除のキー)
)

object News {

    // 📡 NHK RSS の取得先 (主要 + 社会)
    const val CAT0_URL = "https://www3.nhk.or.jp/rss/news/cat0.xml"
    const val CAT1_URL = "https://www3.nhk.or.jp/rss/news/cat1.xml"

    // 🚨 防災関連とみなすキーワード (見出し・要約のどちらかに含めば優先)
    private val KEYWORDS = listOf(
        "地震", "津波", "警報", "特別警報", "台風", "大雨", "洪水", "土砂", "避難", "震度",
        "噴火", "熱中症", "暴風", "大雪", "竜巻", "線状降水帯"
    )

    // 📝 description の HTML を除去し、約80字に切り詰める (途中で切れたら「…」)
    fun truncateSummary(raw: String?, maxLen: Int): String {
        if (raw == null) return ""
        val s = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.length <= maxLen) return s
        return s.substring(0, maxLen) + "…"
    }

    // 📝 XML の実体参照を戻す (&amp; は最後に処理する)
    private fun unescapeXml(s: String): String {
        var out = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return out.replace("&amp;", "&")
    }

    // 📝 タグの中身を取り出す (CDATA は剥がし、実体参照は戻す)。無ければ ""
    private fun tagContent(block: String, tag: String): String {
        val m = Regex("<" + tag + "(\\s[^>]*)?>(.*?)</" + tag + ">", RegexOption.DOT_MATCHES_ALL)
            .find(block) ?: return ""
        var content = m.groupValues[2].trim()
        if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
            content = content.substring("<![CDATA[".length, content.length - "]]>".length)
        }
        return unescapeXml(content).trim()
    }

    // 🕐 RSS の pubDate (RFC822) を JST の "H:mm" にする (解釈できなければ "")
    fun pubDateToTimeText(pub: String): String {
        return try {
            val inFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            val d = inFmt.parse(pub.trim()) ?: return ""
            val outFmt = SimpleDateFormat("H:mm", Locale.JAPAN)
            outFmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            outFmt.format(d)
        } catch (e: Exception) {
            ""
        }
    }

    // 📝 NHK RSS (XML) を走査し、<item> ごとに NewsItem を作る。
    // 解釈に失敗した場合は途中までの結果を返す
    fun parseRss(xml: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        var pos = 0
        while (true) {
            val start = xml.indexOf("<item", pos)
            if (start < 0) break
            val end = xml.indexOf("</item>", start)
            if (end < 0) break
            val block = xml.substring(start, end)
            val title = tagContent(block, "title")
            val link = tagContent(block, "link")
            val desc = tagContent(block, "description")
            val pub = tagContent(block, "pubDate")
            out.add(NewsItem(title, pubDateToTimeText(pub), truncateSummary(desc, 80), link))
            pos = end + "</item>".length
        }
        return out
    }

    // 🚨 防災関連かどうか (見出し・要約のどちらかにキーワードを含む)
    fun isDisaster(item: NewsItem): Boolean {
        for (kw in KEYWORDS) {
            if (item.title.contains(kw) || item.summary.contains(kw)) return true
        }
        return false
    }

    // 📝 2 つのフィードをマージし、防災関連を優先して最大 8 件返す。
    // リンクが空の記事は落とし、リンクで重複排除する。
    // 一般記事は「防災が 4 件以上ある場合は合計 4 件まで」という旧規則で混ぜる
    fun selectItems(first: List<NewsItem>, second: List<NewsItem>): List<NewsItem> {
        val merged = ArrayList<NewsItem>()
        val seen = HashSet<String>()
        for (n in first + second) {
            if (n.link.isNotEmpty() && seen.add(n.link)) merged.add(n)
        }
        val disaster = ArrayList<NewsItem>()
        val general = ArrayList<NewsItem>()
        for (n in merged) {
            if (isDisaster(n)) disaster.add(n) else general.add(n)
        }
        val out = ArrayList(disaster)
        // 📰 防災関連が少ない場合は主要ニュースも少し混ぜる (合計 最大8件)
        for (n in general) {
            if (out.size >= 8) break
            if (disaster.size >= 4 && out.size >= 4) break
            out.add(n)
        }
        return if (out.size > 8) out.subList(0, 8) else out
    }

    // 📡 NHK 主要 + 社会を取得して選別する (通信するのでワーカースレッドで呼ぶこと)。
    // どちらも取れなければ IllegalStateException
    fun fetchItems(): List<NewsItem> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val f0 = pool.submit(Callable { A2sHttp.fetchOnce(CAT0_URL, "") })
            val f1 = pool.submit(Callable { A2sHttp.fetchOnce(CAT1_URL, "") })
            var items0: List<NewsItem> = emptyList()
            var items1: List<NewsItem> = emptyList()
            try {
                items0 = parseRss(f0.get(30, TimeUnit.SECONDS))
            } catch (e: Exception) {
                // 片方だけでも続けられる
            }
            try {
                items1 = parseRss(f1.get(30, TimeUnit.SECONDS))
            } catch (e: Exception) {
                // 片方だけでも続けられる
            }
            val selected = selectItems(items0, items1)
            if (selected.isEmpty()) throw IllegalStateException("ニュースを取得できませんでした")
            return selected
        } finally {
            pool.shutdown()
        }
    }
}
