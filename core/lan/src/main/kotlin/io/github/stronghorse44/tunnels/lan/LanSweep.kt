package io.github.stronghorse44.tunnels.lan

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/**
 * The opt-in address sweep (RJ, 2026-10-10): finds hosts that announce nothing on mDNS or SSDP (most phones, many
 * cameras) by trying a TCP connection to a few common ports on every address of the confirmed network around the phone.
 * A connection that is accepted or refused means a host is there; one that times out or finds no route means nothing
 * answered. Only IPv4, only the /24 around the phone (or the confirmed prefix when it is smaller), and every address
 * still passes [LanScope]. Android hides the ARP table and other devices' hardware addresses from apps, so the sweep
 * learns that an address answers, not what it is; [ReverseDns] asks the router for the name the device gave it.
 * Plain JVM, unit-tested.
 */
object LanSweep {
    /** The widest block swept: the /24 around the phone, 254 addresses. */
    const val MAX_PREFIX_BITS = 24

    /**
     * Ports tried in this order until one answers: web, secure web, SSH, RTSP (cameras), the iPhone sync port, the
     * alternate web port. A refused connection answers as well as an accepted one.
     */
    val PORTS: List<Int> = listOf(80, 443, 22, 554, 62078, 8080)

    const val CONNECT_TIMEOUT_MS = 350
    const val CONCURRENCY = 48
    const val BUDGET_MS = 30_000L

    enum class Answer { PRESENT, SILENT }

    /**
     * The addresses to sweep, in numeric order: for each confirmed IPv4 prefix, the /24 around the phone's own address
     * there (or the whole prefix when it is smaller), without its network and broadcast addresses, the phone itself,
     * addresses already in [known], and anything [LanScope] refuses. [prefixes] are the gate's (own address, length).
     */
    fun targets(prefixes: List<Pair<InetAddress, Int>>, known: Set<String> = emptySet()): List<String> {
        val own = prefixes.map { it.first }
        val out = sortedSetOf<Long>()
        for ((address, length) in prefixes) {
            if (address !is Inet4Address || length !in 1..32) continue
            val bits = maxOf(length, MAX_PREFIX_BITS)
            val hostBits = 32 - bits
            if (hostBits < 2) continue
            val mine = toLong(address)
            val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
            val network = mine and mask
            val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
            for (n in network + 1 until broadcast) {
                if (n == mine) continue
                val candidate = fromLong(n)
                if (candidate.hostAddress in known) continue
                if (LanScope.accepts(candidate, prefixes, own)) out += n
            }
        }
        return out.map { fromLong(it).hostAddress!! }
    }

    /**
     * What one connection attempt says: [Answer.PRESENT] when it connected ([failure] null) or was refused (the host sent a
     * reset: Android words it "ECONNREFUSED (Connection refused)"), [Answer.SILENT] for a timeout, no route or anything else.
     */
    fun classify(failure: Throwable?): Answer {
        if (failure == null) return Answer.PRESENT
        val text = failure.message.orEmpty()
        return if (text.contains("ECONNREFUSED") || text.contains("Connection refused", ignoreCase = true)) Answer.PRESENT else Answer.SILENT
    }

    private fun toLong(address: Inet4Address): Long = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    private fun fromLong(n: Long): InetAddress =
        InetAddress.getByAddress(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
}

/**
 * Reverse DNS (PTR) for an IPv4 address, asked of the router only: many routers answer with the host name a device gave
 * them when it joined (DHCP), such as "Galaxy-S24" or "Ring-Doorbell". The query names `d.c.b.a.in-addr.arpa`, a DNS
 * zone, never a host anything connects to. Plain JVM, unit-tested.
 */
object ReverseDns {
    const val PTR_ZONE = "in-addr.arpa"
    const val TYPE_PTR = 12
    private const val CLASS_IN = 1
    const val MAX_NAME = 63

    /** The PTR question name for an IPv4 literal, or null for anything else. */
    fun questionName(ip: String): String? {
        val address = LanScope.parseLiteral(ip) as? Inet4Address ?: return null
        val b = address.address.map { it.toInt() and 0xFF }
        return "${b[3]}.${b[2]}.${b[1]}.${b[0]}.$PTR_ZONE"
    }

    /** A recursive PTR query for [ip] with transaction id [id]; null when [ip] is not an IPv4 literal. */
    fun query(ip: String, id: Int): ByteArray? {
        val name = questionName(ip) ?: return null
        val out = ArrayList<Byte>(48)
        fun u16(v: Int) {
            out.add(((v shr 8) and 0xFF).toByte())
            out.add((v and 0xFF).toByte())
        }
        u16(id and 0xFFFF)
        u16(0x0100)
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        u16(TYPE_PTR)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /** The name in the first PTR answer of the reply to transaction [id], or null (no answer, error, mismatch, malformed). */
    fun parse(id: Int, response: ByteArray, length: Int = response.size): String? {
        if (length < 12 || length > response.size) return null
        fun u8(i: Int) = response[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (id and 0xFFFF)) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0 || flags and 0xF != 0) return null
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        repeat(qd) {
            pos = readName(response, pos, length)?.second ?: return null
            pos += 4
            if (pos > length) return null
        }
        repeat(an.coerceAtMost(16)) {
            pos = readName(response, pos, length)?.second ?: return null
            if (pos + 10 > length) return null
            val type = u16(pos)
            val rdLength = u16(pos + 8)
            val rdata = pos + 10
            if (rdata + rdLength > length) return null
            if (type == TYPE_PTR) return readName(response, rdata, length)?.first
            pos = rdata + rdLength
        }
        return null
    }

    /** A (possibly compressed) name at [start]: its text and the position after it where it sits; null when malformed. */
    private fun readName(buf: ByteArray, start: Int, length: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var hops = 0
        while (true) {
            if (pos >= length) return null
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (end < 0) end = pos + 1
                    return labels.joinToString(".") to end
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= length) return null
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                    if (++hops > 32) return null
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > length) return null
                    labels += String(buf, pos + 1, len, Charsets.ISO_8859_1)
                    pos += 1 + len
                    if (labels.size > 64) return null
                }
            }
        }
    }

    private val ipLike = Regex("""^(ip|host|dhcp|client|unknown)?[-_]?\d{1,3}([-_.]\d{1,3}){3}$""")

    /**
     * The device's own part of a PTR answer: the first label, trimmed and kept as written, or null when there is none
     * or it is only the address spelled out (routers answer "192-168-1-5" or "host-192-168-1-5" for devices that sent
     * no name), so a made-up name never becomes an identity.
     */
    fun deviceName(ptr: String?): String? {
        val first = ptr?.trim()?.trimEnd('.')?.substringBefore('.')?.trim().orEmpty()
        if (first.isEmpty() || first.length > MAX_NAME) return null
        if (ipLike.matches(first.lowercase(Locale.ROOT))) return null
        if (first.all { it.isDigit() || it == '-' || it == '_' }) return null
        return first
    }
}
