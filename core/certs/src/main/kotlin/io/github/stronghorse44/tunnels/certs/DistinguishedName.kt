package io.github.stronghorse44.tunnels.certs

import javax.security.auth.x500.X500Principal

/**
 * A parsed X.500 name: attribute type/value pairs in the order the RFC 2253 string lists them
 * (most specific first). Android has no `javax.naming`, so the string is parsed here.
 */
class DistinguishedName(val attributes: List<Pair<String, String>>) {
    /** First value for [type] (case-insensitive), e.g. `get("CN")`. */
    operator fun get(type: String): String? = attributes.firstOrNull { it.first.equals(type, ignoreCase = true) }?.second

    val commonName: String? get() = this["CN"]
    val organization: String? get() = this["O"]

    companion object {
        fun of(principal: X500Principal): DistinguishedName = parse(principal.getName(X500Principal.RFC2253))

        /** Parses an RFC 2253 string such as `CN=Foo\, Inc,O=Bar+OU=Baz,C=NL`. Never throws; odd input yields fewer attributes. */
        fun parse(rfc2253: String): DistinguishedName {
            val attributes = mutableListOf<Pair<String, String>>()
            for (rdn in splitUnescaped(rfc2253, ',')) {
                for (attribute in splitUnescaped(rdn, '+')) {
                    val eq = indexOfUnescaped(attribute, '=')
                    if (eq <= 0) continue
                    val type = attribute.substring(0, eq).trim()
                    val value = unescape(attribute.substring(eq + 1).trim())
                    if (type.isNotEmpty()) attributes += type to value
                }
            }
            return DistinguishedName(attributes)
        }

        private fun indexOfUnescaped(s: String, c: Char): Int {
            var i = 0
            var quoted = false
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '\\' -> i++
                    ch == '"' -> quoted = !quoted
                    ch == c && !quoted -> return i
                }
                i++
            }
            return -1
        }

        private fun splitUnescaped(s: String, separator: Char): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            var quoted = false
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '\\' && i + 1 < s.length -> { current.append(ch).append(s[i + 1]); i++ }
                    ch == '"' -> { quoted = !quoted; current.append(ch) }
                    ch == separator && !quoted -> { parts += current.toString(); current.setLength(0) }
                    else -> current.append(ch)
                }
                i++
            }
            parts += current.toString()
            return parts.filter { it.isNotBlank() }
        }

        /** Undoes RFC 2253 escaping: `\,` `\\` `\"` etc., `\XX` hex pairs (UTF-8 bytes), quoted strings, and `#hex` BER values. */
        internal fun unescape(raw: String): String {
            if (raw.startsWith("#")) return decodeBerString(raw.substring(1)) ?: raw
            val s = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw
            val out = StringBuilder()
            val bytes = java.io.ByteArrayOutputStream()
            fun flushBytes() {
                if (bytes.size() > 0) {
                    out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                    bytes.reset()
                }
            }
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\') {
                    if (i + 1 >= s.length) break // a trailing lone backslash escapes nothing
                    val next = s[i + 1]
                    val hex = if (i + 2 < s.length) s.substring(i + 1, i + 3) else ""
                    if (hex.length == 2 && hex.all { it.isHexDigit() }) {
                        bytes.write(hex.toInt(16))
                        i += 3
                        continue
                    }
                    flushBytes()
                    out.append(next)
                    i += 2
                    continue
                }
                flushBytes()
                out.append(ch)
                i++
            }
            flushBytes()
            return out.toString()
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        /** Decodes a `#`-prefixed BER value when it is a simple string type; null otherwise. */
        private fun decodeBerString(hex: String): String? {
            if (hex.length < 4 || hex.length % 2 != 0 || !hex.all { it.isHexDigit() }) return null
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val tag = bytes[0].toInt() and 0xFF
            val length = bytes[1].toInt() and 0xFF
            if (length >= 0x80 || length != bytes.size - 2) return null
            val content = bytes.copyOfRange(2, bytes.size)
            return when (tag) {
                0x0C, 0x13, 0x16, 0x1A -> content.toString(Charsets.UTF_8) // UTF8String, PrintableString, IA5String, VisibleString
                0x1E -> content.toString(Charsets.UTF_16BE) // BMPString
                else -> null
            }
        }
    }
}
