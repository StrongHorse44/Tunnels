package io.github.stronghorse44.tunnels.lan

import kotlin.random.Random

/** How a resolver answered the probe for a name that cannot exist. */
enum class DnsVerdict {
    /** Honest: the name does not exist. */
    NXDOMAIN,
    /** An A record for a nonexistent name: the resolver rewrites failures (hijack). */
    ANSWERED,
    /** NOERROR without an A record (e.g. a bare CNAME or an empty answer). */
    EMPTY,
    SERVFAIL,
    REFUSED,
    /** A reply that is not for our query id, or is not a response. */
    MISMATCH,
    MALFORMED,
    ;

    /** Maps to the router:dnsHijack observation value. */
    val hijackValue: String
        get() = when (this) {
            ANSWERED -> LanKeys.TRUE
            NXDOMAIN, EMPTY -> LanKeys.FALSE
            else -> LanKeys.UNKNOWN
        }
}

/**
 * The DNS-hijack probe: a query for a random name under a domain that does not exist, and a classifier for
 * the reply. Independent of core/dns on purpose so the two modules stay separate.
 */
object DnsProbe {
    const val PROBE_DOMAIN = "invalid-tunnels-probe.net"
    const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val LABEL_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** "k3x9q2mzp0wa.invalid-tunnels-probe.net": a fresh random label every call so caches cannot answer. */
    fun probeName(random: Random = Random.Default, labelLength: Int = 12): String =
        buildString(labelLength) { repeat(labelLength) { append(LABEL_CHARS[random.nextInt(LABEL_CHARS.length)]) } } + "." + PROBE_DOMAIN

    /** A standard recursive A query for [name] with transaction id [id] (0..65535). */
    fun buildQuery(name: String, id: Int): ByteArray {
        val labels = name.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        require(labels.isNotEmpty() && labels.all { it.length <= 63 }) { "bad name" }
        val out = ArrayList<Byte>(32 + name.length)
        fun u16(v: Int) {
            out.add(((v shr 8) and 0xFF).toByte())
            out.add((v and 0xFF).toByte())
        }
        u16(id and 0xFFFF)
        u16(0x0100) // standard query, recursion desired
        u16(1) // QDCOUNT
        u16(0)
        u16(0)
        u16(0)
        for (label in labels) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        u16(TYPE_A)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /** Classifies a reply to the query with transaction id [id]. */
    fun classify(id: Int, response: ByteArray, length: Int = response.size): DnsVerdict {
        if (length < 12 || length > response.size) return DnsVerdict.MALFORMED
        fun u8(i: Int) = response[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (id and 0xFFFF)) return DnsVerdict.MISMATCH
        val flags = u16(2)
        if (flags and 0x8000 == 0) return DnsVerdict.MISMATCH
        when (flags and 0xF) {
            3 -> return DnsVerdict.NXDOMAIN
            2 -> return DnsVerdict.SERVFAIL
            5 -> return DnsVerdict.REFUSED
            0 -> Unit
            else -> return DnsVerdict.MALFORMED
        }
        val qd = u16(4)
        val an = u16(6)
        if (an == 0) return DnsVerdict.EMPTY
        var pos = 12
        repeat(qd) {
            pos = skipName(response, pos, length) ?: return DnsVerdict.MALFORMED
            pos += 4
            if (pos > length) return DnsVerdict.MALFORMED
        }
        var sawA = false
        repeat(an.coerceAtMost(32)) {
            pos = skipName(response, pos, length) ?: return DnsVerdict.MALFORMED
            if (pos + 10 > length) return DnsVerdict.MALFORMED
            val type = u16(pos)
            val rdLength = u16(pos + 8)
            if (type == TYPE_A && rdLength == 4) sawA = true
            pos += 10 + rdLength
            if (pos > length) return DnsVerdict.MALFORMED
        }
        return if (sawA) DnsVerdict.ANSWERED else DnsVerdict.EMPTY
    }

    /** Position after a (possibly compressed) name, or null when it runs off the end. */
    private fun skipName(buf: ByteArray, start: Int, length: Int): Int? {
        var pos = start
        var hops = 0
        while (true) {
            if (pos >= length) return null
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= length) pos + 2 else null
                len and 0xC0 != 0 -> return null
                else -> {
                    pos += 1 + len
                    if (++hops > 128) return null
                }
            }
        }
    }
}
