package io.github.stronghorse44.tunnels.lan

import java.net.InetAddress

/**
 * Decides whether a discovered address may be probed at all. mDNS and SSDP answers are attacker-influenced
 * (any device on the Wi-Fi can claim any address), so the scanner connects only to addresses that lie inside
 * a prefix the own-network gate confirmed *and* are private or link-scoped. Fails closed: no prefixes, no
 * scan. Plain JVM, unit-tested.
 */
object LanScope {
    /**
     * True when a TCP connect or UDP probe to [address] is allowed: inside one of [prefixes] (the confirmed
     * link's address/length pairs), private or link-scoped, not loopback, wildcard or multicast, and not one of
     * the phone's [ownAddresses].
     */
    fun accepts(
        address: InetAddress,
        prefixes: List<Pair<InetAddress, Int>>,
        ownAddresses: Collection<InetAddress> = emptyList(),
    ): Boolean {
        if (prefixes.isEmpty()) return false
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return false
        if (isOwn(address, ownAddresses)) return false
        if (!LanAddresses.isPrivate(address)) return false
        return prefixes.any { (prefix, length) -> LanAddresses.isWithin(address, prefix, length) }
    }

    /** True when [address] is one of the phone's own addresses (compared by bytes; a zone id does not matter). */
    fun isOwn(address: InetAddress, ownAddresses: Collection<InetAddress>): Boolean =
        ownAddresses.any { it.address.contentEquals(address.address) }

    /** [accepts] for an address kept as text; false for anything that is not an IP literal. */
    fun acceptsLiteral(
        text: String,
        prefixes: List<Pair<InetAddress, Int>>,
        ownAddresses: Collection<InetAddress> = emptyList(),
    ): Boolean {
        val address = parseLiteral(text) ?: return false
        return accepts(address, prefixes, ownAddresses)
    }

    /**
     * Parses an IPv4 or IPv6 literal strictly, byte by byte, and builds the address with
     * [InetAddress.getByAddress]. It never calls `getByName`, which resolves anything it cannot parse as a
     * literal (a hosts-file entry, a DNS lookup), so no input can cause a lookup. IPv4 is four dotted decimal
     * octets of ASCII digits without leading zeros; IPv6 is hex groups of 1-4 ASCII digits with at most one
     * "::" and no embedded IPv4 part (what `hostAddress` produces); a "%zone" suffix is ignored. Null for
     * anything else.
     */
    fun parseLiteral(text: String): InetAddress? {
        val host = text.trim().substringBefore('%')
        val bytes = (if (host.contains(':')) parseV6(host) else parseV4(host)) ?: return null
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun parseV4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(::isDigit)) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun parseV6(host: String): ByteArray? {
        if (host.length < 2 || host.length > 39) return null
        val doubleColon = host.indexOf("::")
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4 || g.any { hexValue(it) < 0 }) return null
                g.fold(0) { acc, c -> acc * 16 + hexValue(c) }
            }
        }
        val values: List<Int> = if (doubleColon < 0) {
            val all = groups(host) ?: return null
            if (all.size != 8) return null
            all
        } else {
            val head = groups(host.substring(0, doubleColon)) ?: return null
            val tail = groups(host.substring(doubleColon + 2)) ?: return null
            if (head.size + tail.size > 7) return null
            head + List(8 - head.size - tail.size) { 0 } + tail
        }
        val out = ByteArray(16)
        for ((i, v) in values.withIndex()) {
            out[i * 2] = (v shr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }
}
