package io.github.stronghorse44.tunnels.pairing

/**
 * RFC 9285 Base45: bytes as QR alphanumeric characters, which a QR code stores at 5.5 bits each. Denser and more
 * robust to scan than binary mode, whose decoders guess a character set.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private val INDEX = IntArray(128) { -1 }.also { a -> ALPHABET.forEachIndexed { i, c -> a[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 1) / 2 * 3)
        var i = 0
        while (i + 1 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            out.append(ALPHABET[n % 45]).append(ALPHABET[n / 45 % 45]).append(ALPHABET[n / 2025])
            i += 2
        }
        if (i < bytes.size) {
            val n = bytes[i].toInt() and 0xFF
            out.append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
        }
        return out.toString()
    }

    /** Throws [IllegalArgumentException] on characters outside the alphabet, a dangling character or an overflow. */
    fun decode(text: String): ByteArray {
        require(text.length % 3 != 1) { "base45 length ${text.length} is not valid" }
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        fun v(c: Char): Int = (if (c.code < 128) INDEX[c.code] else -1).also { require(it >= 0) { "'$c' is not base45" } }
        while (i + 2 < text.length) {
            val n = v(text[i]) + v(text[i + 1]) * 45 + v(text[i + 2]) * 2025
            require(n <= 0xFFFF) { "base45 triple overflows" }
            out[o++] = (n shr 8).toByte()
            out[o++] = n.toByte()
            i += 3
        }
        if (i < text.length) {
            val n = v(text[i]) + v(text[i + 1]) * 45
            require(n <= 0xFF) { "base45 pair overflows" }
            out[o] = n.toByte()
        }
        return out
    }
}
