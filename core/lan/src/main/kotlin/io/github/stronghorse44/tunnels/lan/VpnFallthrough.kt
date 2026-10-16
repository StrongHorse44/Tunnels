package io.github.stronghorse44.tunnels.lan

import java.net.InetAddress

/**
 * Reaching the Wi-Fi under a VPN that Tunnels may not bypass (0.1.17 hotfix).
 *
 * When a VPN that is not bypassable covers Tunnels (Tailscale, for one: its VpnService never calls `allowBypass`),
 * Android refuses `Network.bindSocket` to the Wi-Fi with EPERM: netd lets an app covered by a secure VPN use only that
 * VPN. The app's own traffic still reaches the LAN, because a destination the VPN has no route for falls through to the
 * phone's default network. So a probe socket may be left unbound, but only when this says the kernel will send it over
 * the Wi-Fi:
 *
 * - the Wi-Fi is known: its own addresses and prefixes;
 * - the VPN's routes could be read, and no unicast route of any VPN overlaps a Wi-Fi prefix (a default route, an exit
 *   node or a subnet route over the LAN would take the packets into the tunnel); `throw` and `unreachable` routes
 *   (excluded ranges) are not passed in;
 * - and for every probe destination class (the gateway for unicast inside the network, the SSDP group for multicast),
 *   the source address the kernel chose for an unbound UDP socket connected to it (which sends nothing) is one of the
 *   Wi-Fi's own addresses: the route goes out on the Wi-Fi.
 *
 * Anything missing reads as "not allowed" and the socket is refused, as before. Plain Kotlin, unit-tested.
 */
object VpnFallthrough {
    /** True when the two prefixes (same family) share an address: the shorter one contains the other's start. */
    fun overlaps(a: Pair<InetAddress, Int>, b: Pair<InetAddress, Int>): Boolean {
        if (a.first.address.size != b.first.address.size) return false
        val shorter = if (a.second <= b.second) a else b
        val longer = if (shorter === a) b else a
        return LanAddresses.isWithin(longer.first, shorter.first, shorter.second)
    }

    /**
     * [vpnRoutes] are the unicast routes of every VPN network (null when one could not be read); [sources] the source
     * addresses the kernel chose for the gateway and the SSDP group (null for one it would not route).
     */
    fun allows(
        wifiOwn: Collection<InetAddress>,
        wifiPrefixes: List<Pair<InetAddress, Int>>,
        vpnRoutes: List<Pair<InetAddress, Int>>?,
        sources: List<InetAddress?>,
    ): Boolean {
        if (wifiOwn.isEmpty() || wifiPrefixes.isEmpty() || vpnRoutes == null || sources.isEmpty()) return false
        if (vpnRoutes.any { route -> wifiPrefixes.any { overlaps(route, it) } }) return false
        return sources.all { it != null && LanScope.isOwn(it, wifiOwn) }
    }
}
