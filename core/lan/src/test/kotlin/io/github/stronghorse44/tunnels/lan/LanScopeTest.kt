package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanScopeTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    private val home = listOf(ip("192.168.1.23") to 24)
    private val own = listOf(ip("192.168.1.23"))

    @Test
    fun acceptsPrivateAddressInsideThePrefix() {
        assertTrue(LanScope.accepts(ip("192.168.1.77"), home, own))
        assertTrue(LanScope.accepts(ip("192.168.1.1"), home, own))
    }

    @Test
    fun rejectsAddressOutsideThePrefix() {
        assertFalse(LanScope.accepts(ip("192.168.2.77"), home, own))
        assertFalse(LanScope.accepts(ip("10.0.0.5"), home, own))
    }

    @Test
    fun rejectsPrivateAddressOutsideThePrefix() {
        // A device on the Wi-Fi can advertise another private network (a VPN peer, a neighbouring subnet).
        assertFalse(LanScope.accepts(ip("192.168.0.1"), home, own))
        assertFalse(LanScope.accepts(ip("172.16.5.5"), home, own))
        assertFalse(LanScope.accepts(ip("169.254.1.1"), home, own))
    }

    @Test
    fun rejectsPublicAddressesEvenWhenThePrefixCoversThem() {
        assertFalse(LanScope.accepts(ip("8.8.8.8"), home, own))
        assertFalse(LanScope.accepts(ip("1.1.1.1"), listOf(ip("0.0.0.0") to 0), own))
        // The prefix check passes (/8 around a public address) but the private guard stops it.
        assertFalse(LanScope.accepts(ip("44.1.2.3"), listOf(ip("44.9.9.9") to 8), emptyList()))
        assertFalse(LanScope.accepts(ip("2001:db8::5"), listOf(ip("2001:db8::1") to 64), emptyList()))
    }

    @Test
    fun rejectsTheDevicesOwnAddresses() {
        assertFalse(LanScope.accepts(ip("192.168.1.23"), home, own))
        val ownV6 = listOf(ip("fd12:3456::23"), ip("fe80::1234"))
        val v6 = listOf(ip("fd12:3456::23") to 64, ip("fe80::1234") to 64)
        assertFalse(LanScope.accepts(ip("fd12:3456::23"), v6, ownV6))
        assertFalse(LanScope.accepts(ip("fe80::1234"), v6, ownV6))
        // The same address is fine when it is not ours.
        assertTrue(LanScope.accepts(ip("fd12:3456::99"), v6, ownV6))
    }

    @Test
    fun rejectsLoopbackWildcardAndMulticast() {
        val wide = listOf(ip("0.0.0.0") to 0, ip("::") to 0)
        assertFalse(LanScope.accepts(ip("127.0.0.1"), wide, emptyList()))
        assertFalse(LanScope.accepts(ip("::1"), wide, emptyList()))
        assertFalse(LanScope.accepts(ip("0.0.0.0"), wide, emptyList()))
        assertFalse(LanScope.accepts(ip("::"), wide, emptyList()))
        assertFalse(LanScope.accepts(ip("239.255.255.250"), listOf(ip("239.0.0.0") to 8), emptyList()))
        assertFalse(LanScope.accepts(ip("ff02::fb"), wide, emptyList()))
    }

    @Test
    fun ipv6UlaFollowsIsWithin() {
        val v6 = listOf(ip("fd12:3456:789a:1::23") to 64)
        assertTrue(LanScope.accepts(ip("fd12:3456:789a:1::beef"), v6, emptyList()))
        assertFalse("other /64", LanScope.accepts(ip("fd12:3456:789a:2::beef"), v6, emptyList()))
        assertFalse("other ULA site", LanScope.accepts(ip("fd99::1"), v6, emptyList()))
    }

    @Test
    fun ipv6LinkLocalFollowsIsWithin() {
        val v6 = listOf(ip("fe80::1234") to 64)
        assertTrue(LanScope.accepts(ip("fe80::abcd"), v6, emptyList()))
        assertTrue("zone ids do not change the bytes", LanScope.accepts(ip("fe80::abcd%1"), v6, emptyList()))
        assertFalse("different /64 of fe80::/10", LanScope.accepts(ip("fe80:0:0:1::abcd"), v6, emptyList()))
    }

    @Test
    fun addressFamiliesDoNotCross() {
        val v4Only = listOf(ip("192.168.1.23") to 24)
        assertFalse(LanScope.accepts(ip("fd12:3456::1"), v4Only, emptyList()))
        val v6Only = listOf(ip("fd12:3456::23") to 64)
        assertFalse(LanScope.accepts(ip("192.168.1.77"), v6Only, emptyList()))
    }

    @Test
    fun anyOfSeveralPrefixesIsEnough() {
        val both = listOf(ip("192.168.1.23") to 24, ip("fd12:3456::23") to 64)
        assertTrue(LanScope.accepts(ip("192.168.1.9"), both, emptyList()))
        assertTrue(LanScope.accepts(ip("fd12:3456::9"), both, emptyList()))
        assertFalse(LanScope.accepts(ip("192.168.9.9"), both, emptyList()))
    }

    @Test
    fun failsClosedWithoutPrefixes() {
        assertFalse(LanScope.accepts(ip("192.168.1.77"), emptyList(), emptyList()))
        assertFalse(LanScope.acceptsLiteral("192.168.1.77", emptyList()))
    }

    @Test
    fun literalsOnly() {
        assertTrue(LanScope.acceptsLiteral("192.168.1.77", home, own))
        assertTrue(LanScope.acceptsLiteral(" 192.168.1.77 ", home, own))
        assertFalse(LanScope.acceptsLiteral("192.168.2.77", home, own))
        assertFalse(LanScope.acceptsLiteral("192.168.1.23", home, own))
        assertTrue(LanScope.acceptsLiteral("fe80::abcd%wlan0", listOf(ip("fe80::1") to 64)))
        assertNull("names are never resolved", LanScope.parseLiteral("router.local"))
        assertNull(LanScope.parseLiteral("example.com"))
        assertNull(LanScope.parseLiteral(""))
        assertNull(LanScope.parseLiteral("192.168.1"))
        assertFalse(LanScope.acceptsLiteral("router.local", home, own))
        assertEquals(ip("10.0.0.1"), LanScope.parseLiteral("10.0.0.1"))
    }
}
