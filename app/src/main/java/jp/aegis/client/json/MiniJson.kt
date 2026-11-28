package jp.aegis.client.json

// ========================================
// 🧩 MiniJson (依存ゼロの JSON パーサ)
// ========================================
// 📌 このアプリは外部ライブラリを使わない方針のため、JSON の読み取りは
// この小さな再帰下降パーサで行う。org.json は Android 同梱だが JVM 単体
// テストではスタブしか無く実行できないため、データ層を純粋関数として
// テストできるように自前パーサへ置き換えた (判断記録は DESIGN.md)
// 📦 値の表現: オブジェクト = Map<String, Any?>、配列 = List<Any?>、
// 数値 = Double、文字列 = String、真偽 = Boolean、null = null
object MiniJson {

    // 📥 JSON テキストを値へ変換する。形式が崩れている場合は例外を投げる
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.readValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw IllegalArgumentException("trailing characters at ${p.pos}")
        return v
    }

    // 📥 壊れた入力を例外ではなく null として扱いたい場合の入口
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: Exception) {
        null
    }

    // 🔍 型つきアクセサ群 (Map / List への安全な取り出し)
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(m: Map<String, Any?>?, key: String, default: String = ""): String {
        val v = m?.get(key) ?: return default
        return v as? String ?: default
    }

    fun strOrNull(m: Map<String, Any?>?, key: String): String? {
        val v = m?.get(key) ?: return null
        return (v as? String)?.takeIf { it.isNotEmpty() }
    }

    fun numOrNull(m: Map<String, Any?>?, key: String): Double? {
        val v = m?.get(key) ?: return null
        return v as? Double
    }

    fun intOrNull(m: Map<String, Any?>?, key: String): Int? = numOrNull(m, key)?.toInt()

    fun int(m: Map<String, Any?>?, key: String, default: Int): Int = intOrNull(m, key) ?: default

    fun bool(m: Map<String, Any?>?, key: String, default: Boolean = false): Boolean {
        val v = m?.get(key) ?: return default
        return v as? Boolean ?: default
    }

    // 🔧 再帰下降パーサ本体
    private class Parser(private val text: String) {
        var pos = 0 // 現在の読み取り位置

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw IllegalArgumentException("unexpected end of input")
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.regionMatches(pos, word, 0, word.length)) {
                throw IllegalArgumentException("invalid literal at $pos")
            }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw IllegalArgumentException("expected ':' at $pos")
                pos++
                skipWhitespace()
                out[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // '['
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                out.add(readValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            if (atEnd() || text[pos] != '"') throw IllegalArgumentException("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalArgumentException("bad \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape: \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            while (!atEnd() && text[pos].isDigit()) pos++
            if (!atEnd() && text[pos] == '.') {
                pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            if (pos == start) throw IllegalArgumentException("invalid value at $pos")
            return text.substring(start, pos).toDouble()
        }
    }
}
