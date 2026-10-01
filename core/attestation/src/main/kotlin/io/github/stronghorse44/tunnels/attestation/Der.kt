package io.github.stronghorse44.tunnels.attestation

import java.math.BigInteger

/** Thrown for malformed, truncated or unsupported DER. The message is meant to be shown to the user. */
class DerException(message: String) : Exception(message)

/**
 * One decoded DER value: its tag and a view onto its content bytes inside the original array.
 * Nothing is copied until [content] or [children] is called.
 */
class DerValue internal constructor(
    val tagClass: Int,
    val constructed: Boolean,
    val tagNumber: Int,
    private val bytes: ByteArray,
    val contentStart: Int,
    val contentEnd: Int,
    /** Index just past this value's last byte, where the next sibling starts. */
    val end: Int,
) {
    val length: Int get() = contentEnd - contentStart

    val isUniversal: Boolean get() = tagClass == Der.CLASS_UNIVERSAL
    val isContextSpecific: Boolean get() = tagClass == Der.CLASS_CONTEXT
    val isSequence: Boolean get() = isUniversal && constructed && tagNumber == Der.TAG_SEQUENCE
    val isSet: Boolean get() = isUniversal && constructed && tagNumber == Der.TAG_SET
    val isInteger: Boolean get() = isUniversal && !constructed && tagNumber == Der.TAG_INTEGER
    val isEnumerated: Boolean get() = isUniversal && !constructed && tagNumber == Der.TAG_ENUMERATED
    val isOctetString: Boolean get() = isUniversal && !constructed && tagNumber == Der.TAG_OCTET_STRING
    val isBoolean: Boolean get() = isUniversal && !constructed && tagNumber == Der.TAG_BOOLEAN
    val isNull: Boolean get() = isUniversal && !constructed && tagNumber == Der.TAG_NULL

    fun content(): ByteArray = bytes.copyOfRange(contentStart, contentEnd)

    /** The values nested inside a constructed value (SEQUENCE, SET or an explicit context tag). */
    fun children(): List<DerValue> {
        if (!constructed) throw DerException("${describe()} is primitive and has no children")
        return Der.readAll(bytes, contentStart, contentEnd)
    }

    /** The single value wrapped by an explicitly tagged context-specific value, e.g. `[705] INTEGER`. */
    fun explicit(): DerValue {
        if (!isContextSpecific || !constructed) throw DerException("${describe()} is not an explicit context tag")
        val inner = children()
        if (inner.size != 1) throw DerException("${describe()} wraps ${inner.size} values, expected 1")
        return inner[0]
    }

    fun asBigInteger(): BigInteger {
        if (!isInteger && !isEnumerated) throw DerException("${describe()} is not an INTEGER")
        if (length == 0) throw DerException("INTEGER with no content")
        return BigInteger(content())
    }

    /** INTEGER or ENUMERATED as a Long; values outside 64 bits fail. */
    fun asLong(): Long {
        val v = asBigInteger()
        if (v.bitLength() > 63) throw DerException("INTEGER too large: ${v.bitLength()} bits")
        return v.toLong()
    }

    /** INTEGER or ENUMERATED as an Int; values outside 32 bits fail. */
    fun asInt(): Int {
        val v = asLong()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw DerException("INTEGER does not fit in 32 bits: $v")
        return v.toInt()
    }

    fun asBoolean(): Boolean {
        if (!isBoolean) throw DerException("${describe()} is not a BOOLEAN")
        if (length != 1) throw DerException("BOOLEAN of length $length")
        return bytes[contentStart] != 0.toByte()
    }

    fun asOctetString(): ByteArray {
        if (!isOctetString) throw DerException("${describe()} is not an OCTET STRING")
        return content()
    }

    fun describe(): String {
        val cls = when (tagClass) {
            Der.CLASS_UNIVERSAL -> ""
            Der.CLASS_APPLICATION -> "APPLICATION "
            Der.CLASS_CONTEXT -> ""
            else -> "PRIVATE "
        }
        val name = if (isUniversal) Der.universalName(tagNumber) else "[$cls$tagNumber]"
        return "$name at offset ${contentStart}"
    }

    override fun toString(): String = describe()
}

/** A bounds-checked DER reader covering what X.509 extensions and the key attestation record use. */
object Der {
    const val CLASS_UNIVERSAL = 0
    const val CLASS_APPLICATION = 1
    const val CLASS_CONTEXT = 2
    const val CLASS_PRIVATE = 3

    const val TAG_BOOLEAN = 1
    const val TAG_INTEGER = 2
    const val TAG_BIT_STRING = 3
    const val TAG_OCTET_STRING = 4
    const val TAG_NULL = 5
    const val TAG_OBJECT_IDENTIFIER = 6
    const val TAG_ENUMERATED = 10
    const val TAG_UTF8_STRING = 12
    const val TAG_SEQUENCE = 16
    const val TAG_SET = 17

    /** Longest tag number encoding accepted (base-128 bytes). 4 bytes cover 28 bits, far beyond any real tag. */
    private const val MAX_TAG_BYTES = 4
    /** Longest length encoding accepted. 4 bytes cover 4 GiB; the extension is a few hundred bytes. */
    private const val MAX_LENGTH_BYTES = 4

    /** Reads the single value starting at [start]; it must end at or before [end]. */
    fun read(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): DerValue {
        if (end > bytes.size || start < 0 || start > end) throw DerException("read range $start..$end outside ${bytes.size} bytes")
        if (start >= end) throw DerException("truncated: expected a value at offset $start but the data ends")
        var pos = start
        val first = bytes[pos++].toInt() and 0xFF
        val tagClass = first ushr 6
        val constructed = (first and 0x20) != 0
        var tagNumber = first and 0x1F
        if (tagNumber == 0x1F) {
            tagNumber = 0
            var count = 0
            while (true) {
                if (pos >= end) throw DerException("truncated: tag number at offset $start runs past the data")
                if (++count > MAX_TAG_BYTES) throw DerException("tag number at offset $start is too long")
                val b = bytes[pos++].toInt() and 0xFF
                tagNumber = (tagNumber shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
        }
        if (pos >= end) throw DerException("truncated: length of value at offset $start is missing")
        val lengthByte = bytes[pos++].toInt() and 0xFF
        val length: Int
        if (lengthByte < 0x80) {
            length = lengthByte
        } else {
            val count = lengthByte and 0x7F
            if (count == 0) throw DerException("indefinite length at offset $start is not allowed in DER")
            if (count > MAX_LENGTH_BYTES) throw DerException("length of value at offset $start uses $count bytes")
            if (pos + count > end) throw DerException("truncated: length of value at offset $start runs past the data")
            var l = 0L
            repeat(count) { l = (l shl 8) or (bytes[pos++].toLong() and 0xFF) }
            if (l > Int.MAX_VALUE) throw DerException("length $l at offset $start is too large")
            length = l.toInt()
        }
        val contentStart = pos
        // Compared as a difference so a length near Int.MAX_VALUE cannot overflow past the bounds check.
        if (length > end - contentStart) {
            throw DerException("truncated: value at offset $start claims $length bytes but only ${end - contentStart} remain")
        }
        val contentEnd = contentStart + length
        return DerValue(tagClass, constructed, tagNumber, bytes, contentStart, contentEnd, contentEnd)
    }

    /** Reads consecutive values until [end] is reached exactly. */
    fun readAll(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<DerValue> {
        val out = ArrayList<DerValue>()
        var pos = start
        while (pos < end) {
            val v = read(bytes, pos, end)
            out += v
            pos = v.end
        }
        return out
    }

    fun universalName(tag: Int): String = when (tag) {
        TAG_BOOLEAN -> "BOOLEAN"
        TAG_INTEGER -> "INTEGER"
        TAG_BIT_STRING -> "BIT STRING"
        TAG_OCTET_STRING -> "OCTET STRING"
        TAG_NULL -> "NULL"
        TAG_OBJECT_IDENTIFIER -> "OBJECT IDENTIFIER"
        TAG_ENUMERATED -> "ENUMERATED"
        TAG_UTF8_STRING -> "UTF8String"
        TAG_SEQUENCE -> "SEQUENCE"
        TAG_SET -> "SET"
        else -> "UNIVERSAL $tag"
    }
}
