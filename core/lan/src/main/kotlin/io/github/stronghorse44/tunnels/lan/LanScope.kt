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
     * Parses an IPv4 or IPv6 literal (an IPv6 zone like "%wlan0" is ignored). Never resolves a name, so it
     * cannot cause a DNS lookup; null for anything else.
     */
    fun parseLiteral(text: String): InetAddress? {
        val host = text.trim().substringBefore('%')
        val looksV4 = host.isNotEmpty() && host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3
        val looksV6 = host.contains(':') && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!looksV4 && !looksV6) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }
}
