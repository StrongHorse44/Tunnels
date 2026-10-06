package io.github.stronghorse44.tunnels.breaches

/**
 * A strict, bounded JSON reader for the one document this module reads (HIBP's breach array). Objects become
 * [Map]s, arrays [List]s, numbers [Long] when integral and in range else [Double], plus [String], [Boolean] and
 * null. No dependency. Anything outside RFC 8259 is refused (no comments, no trailing commas, no leading zeros, no
 * unescaped control characters, no duplicate keys, nothing after the value), and every size is capped
 * (specs/B11-linx.md section 10.2) so a hostile or broken answer cannot exhaust memory or the stack.
 */
object BreachJson {
    /** Nested containers: array, object, array of strings is 3; one spare. */
    const val MAX_DEPTH = 4
    const val MAX_ITEMS = 20_000
    const val MAX_FIELDS = 40
    const val MAX_NODES = 1_500_000
    const val MAX_STRING_CHARS = 65_536

    class JsonException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.value(1)
        p.skipWhitespace()
        if (p.pos != text.length) throw JsonException("trailing data at ${p.pos}")
        return value
    }

    private class Parser(val s: String) {
        var pos = 0
        private var nodes = 0

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\r' || s[pos] == '\n')) pos++
        }

        private fun count() {
            if (++nodes > MAX_NODES) throw JsonException("more than $MAX_NODES values")
        }

        /** [depth] is the nesting level a container met here would have (the top value's is 1). */
        fun value(depth: Int): Any? {
            if (pos >= s.length) throw JsonException("unexpected end")
            count()
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected character at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            if (depth > MAX_DEPTH) throw JsonException("nested deeper than $MAX_DEPTH")
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("object key expected at $pos")
                if (out.size >= MAX_FIELDS) throw JsonException("more than $MAX_FIELDS fields in one object")
                val key = str()
                if (out.containsKey(key)) throw JsonException("duplicate key at $pos")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw JsonException("',' or '}' expected at $pos")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            if (depth > MAX_DEPTH) throw JsonException("nested deeper than $MAX_DEPTH")
            pos++ // [
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWhitespace()
                if (out.size >= MAX_ITEMS) throw JsonException("more than $MAX_ITEMS items in one array")
                out += value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw JsonException("',' or ']' expected at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("unterminated escape")
                        when (s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("short \\u escape")
                                var code = 0
                                for (i in 0 until 4) {
                                    val d = Character.digit(s[pos + i], 16)
                                    if (d < 0 || s[pos + i].code > 0x7f) throw JsonException("bad \\u escape")
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("bad escape at ${pos - 1}")
                        }
                    }
                    c < ' ' -> throw JsonException("control character in string")
                    else -> sb.append(c)
                }
                if (sb.length > MAX_STRING_CHARS) throw JsonException("a string is longer than $MAX_STRING_CHARS characters")
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') {
                pos++
                if (peek() in '0'..'9') throw JsonException("leading zero at $start")
            } else digits()
            var integral = true
            if (peek() == '.') { integral = false; pos++; digits() }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                pos++
                if (peek() == '+' || peek() == '-') pos++
                digits()
            }
            val text = s.substring(start, pos)
            return if (integral) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }

        private fun digits() {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == start) throw JsonException("digit expected at $pos")
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("'$c' expected at $pos")
            pos++
        }
    }
}
