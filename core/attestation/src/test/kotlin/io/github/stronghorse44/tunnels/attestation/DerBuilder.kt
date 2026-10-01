package io.github.stronghorse44.tunnels.attestation

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Hand-builds DER for tests, including high tag numbers and long-form lengths. */
object DerBuilder {
    fun tlv(tagClass: Int, constructed: Boolean, tagNumber: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val first = (tagClass shl 6) or (if (constructed) 0x20 else 0)
        if (tagNumber < 0x1F) {
            out.write(first or tagNumber)
        } else {
            out.write(first or 0x1F)
            val groups = ArrayList<Int>()
            var n = tagNumber
            do {
                groups.add(0, n and 0x7F)
                n = n ushr 7
            } while (n > 0)
            groups.forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        val len = content.size
        if (len < 0x80) {
            out.write(len)
        } else {
            val bytes = BigInteger.valueOf(len.toLong()).toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            out.write(0x80 or bytes.size)
            out.write(bytes)
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(Der.CLASS_UNIVERSAL, true, Der.TAG_SEQUENCE, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(Der.CLASS_UNIVERSAL, true, Der.TAG_SET, concat(*parts))

    fun int(value: Long): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_INTEGER, BigInteger.valueOf(value).toByteArray())

    fun int(value: BigInteger): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_INTEGER, value.toByteArray())

    fun enum(value: Int): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_ENUMERATED, BigInteger.valueOf(value.toLong()).toByteArray())

    fun octet(bytes: ByteArray): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_OCTET_STRING, bytes)

    fun octet(text: String): ByteArray = octet(text.toByteArray())

    fun bool(value: Boolean): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0))

    fun nul(): ByteArray = tlv(Der.CLASS_UNIVERSAL, false, Der.TAG_NULL, ByteArray(0))

    /** Explicit context-specific tag `[n]` wrapping one value. */
    fun ctx(tag: Int, inner: ByteArray): ByteArray = tlv(Der.CLASS_CONTEXT, true, tag, inner)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    fun bytes(seed: Int, size: Int): ByteArray = ByteArray(size) { ((seed * 31 + it * 7) and 0xFF).toByte() }
}
