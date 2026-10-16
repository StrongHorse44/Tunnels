package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** When a VPN Tunnels cannot bypass refuses the Wi-Fi bind, may a probe go out unbound? Only when the LAN route is the Wi-Fi. */
class VpnFallthroughTest {
    private fun ip(s: String) = LanScope.parseLiteral(s)!!
    private fun p(s: String, len: Int) = ip(s) to len

    private val own = listOf(ip("192.168.8.123"), ip("fe80::1234"))
    private val prefixes = listOf(p("192.168.8.123", 24), p("fe80::1234", 64))
    private val tailnet = listOf(p("100.64.0.0", 10), p("fd7a:115c:a1e0::", 48))
    private val viaWifi = listOf<InetAddress?>(ip("192.168.8.123"), ip("192.168.8.123"))

    @Test
    fun aTailnetWithoutAnExitNodeFallsThroughToTheWifi() {
        assertTrue(VpnFallthrough.allows(own, prefixes, tailnet, viaWifi))
        assertTrue("no VPN at all", VpnFallthrough.allows(own, prefixes, emptyList(), viaWifi))
    }

    @Test
    fun anExitNodeOrAnyRouteOverTheLanKeepsTheRefusal() {
        assertFalse("IPv4 default route", VpnFallthrough.allows(own, prefixes, tailnet + p("0.0.0.0", 0), viaWifi))
        assertFalse("IPv6 default route", VpnFallthrough.allows(own, prefixes, tailnet + p("::", 0), viaWifi))
        assertFalse("a subnet route over the LAN", VpnFallthrough.allows(own, prefixes, listOf(p("192.168.8.0", 24)), viaWifi))
        assertFalse("part of the LAN", VpnFallthrough.allows(own, prefixes, listOf(p("192.168.8.128", 25)), viaWifi))
        assertFalse("a wider route", VpnFallthrough.allows(own, prefixes, listOf(p("192.168.0.0", 16)), viaWifi))
        assertTrue("a neighbouring range", VpnFallthrough.allows(own, prefixes, listOf(p("192.168.9.0", 24), p("192.168.0.0", 21)), viaWifi))
    }

    @Test
    fun theKernelMustPickTheWifisOwnAddressForEveryDestination() {
        assertFalse("into the tunnel", VpnFallthrough.allows(own, prefixes, tailnet, listOf(ip("100.101.102.103"), ip("192.168.8.123"))))
        assertFalse("multicast elsewhere", VpnFallthrough.allows(own, prefixes, tailnet, listOf(ip("192.168.8.123"), ip("10.20.30.40"))))
        assertFalse("not routed", VpnFallthrough.allows(own, prefixes, tailnet, listOf(ip("192.168.8.123"), null)))
        assertFalse("nothing checked", VpnFallthrough.allows(own, prefixes, tailnet, emptyList()))
    }

    @Test
    fun anythingUnknownKeepsTheRefusal() {
        assertFalse("the VPN's routes unreadable", VpnFallthrough.allows(own, prefixes, null, viaWifi))
        assertFalse("no Wi-Fi addresses", VpnFallthrough.allows(emptyList(), prefixes, tailnet, viaWifi))
        assertFalse("no Wi-Fi prefixes", VpnFallthrough.allows(own, emptyList(), tailnet, viaWifi))
    }

    @Test
    fun overlapIsSymmetricAndPerFamily() {
        assertTrue(VpnFallthrough.overlaps(p("192.168.8.0", 24), p("192.168.8.77", 32)))
        assertTrue(VpnFallthrough.overlaps(p("192.168.8.77", 32), p("192.168.8.0", 24)))
        assertTrue(VpnFallthrough.overlaps(p("10.0.0.0", 8), p("10.1.2.0", 24)))
        assertFalse(VpnFallthrough.overlaps(p("192.168.8.0", 24), p("192.168.9.0", 24)))
        assertFalse("IPv4 and IPv6 never overlap", VpnFallthrough.overlaps(p("0.0.0.0", 0), p("::", 0)))
        assertFalse(VpnFallthrough.overlaps(p("fe80::", 64), p("fd7a:115c:a1e0::", 48)))
    }
}
