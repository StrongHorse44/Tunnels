package io.github.stronghorse44.tunnels.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Where a resolver the phone was handed lives, relative to the Wi-Fi it is on. */
enum class ResolverScope {
    /** The default gateway itself: the usual home router setup. */
    GATEWAY,
    /** Another address on the local network (a Pi-hole, a NAS, the router's second address). */
    LAN,
    /** A server outside the network, e.g. a public resolver the router hands out via DHCP. */
    OFF_LAN,
}

/** Address arithmetic the scanner needs to stay on the local network. Plain JVM, unit-tested. */
object LanAddresses {
    /** True when [address] lies inside the network [prefixAddress]/[prefixLength] (same address family only). */
    fun isWithin(address: InetAddress, prefixAddress: InetAddress, prefixLength: Int): Boolean {
        val a = address.address
        val p = prefixAddress.address
        if (a.size != p.size) return false
        val bits = prefixLength.coerceIn(0, a.size * 8)
        val fullBytes = bits / 8
        for (i in 0 until fullBytes) if (a[i] != p[i]) return false
        val rest = bits % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (a[fullBytes].toInt() and mask) == (p[fullBytes].toInt() and mask)
    }

    /** Private or link-scoped: RFC 1918, 169.254/16, IPv6 ULA (fc00::/7) and link-local (fe80::/10). */
    fun isPrivate(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> {
            val b = address.address.map { it.toInt() and 0xFF }
            b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
        }
        is Inet6Address -> address.isLinkLocalAddress || (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    /** "192.168.1.0/24" style network of [address]/[prefixLength]; null for a nonsensical prefix. */
    fun network(address: InetAddress, prefixLength: Int): String? {
        val bytes = address.address
        if (prefixLength !in 0..bytes.size * 8) return null
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            val bitsLeft = prefixLength - i * 8
            out[i] = when {
                bitsLeft >= 8 -> bytes[i]
                bitsLeft <= 0 -> 0
                else -> (bytes[i].toInt() and ((0xFF shl (8 - bitsLeft)) and 0xFF)).toByte()
            }
        }
        val host = runCatching { InetAddress.getByAddress(out).hostAddress }.getOrNull() ?: return null
        return "${host.substringBefore('%')}/$prefixLength"
    }

    /**
     * Classifies [dns]: the gateway, something else on the local network (inside one of the link's [prefixes],
     * or any private/link-scoped address, which never routes to the internet), or off the LAN. The hijack
     * probe only ever goes to the first two.
     */
    fun resolverScope(dns: InetAddress, gateway: InetAddress?, prefixes: List<Pair<InetAddress, Int>>): ResolverScope {
        if (gateway != null && dns == gateway) return ResolverScope.GATEWAY
        if (prefixes.any { (addr, len) -> isWithin(dns, addr, len) }) return ResolverScope.LAN
        if (isPrivate(dns)) return ResolverScope.LAN
        return ResolverScope.OFF_LAN
    }
}
