package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanAddressesTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun prefixArithmetic() {
        assertTrue(LanAddresses.isWithin(ip("192.168.1.77"), ip("192.168.1.0"), 24))
        assertFalse(LanAddresses.isWithin(ip("192.168.2.77"), ip("192.168.1.0"), 24))
        assertTrue(LanAddresses.isWithin(ip("192.168.2.77"), ip("192.168.1.0"), 22))
        assertTrue(LanAddresses.isWithin(ip("10.255.3.9"), ip("10.0.0.0"), 8))
        assertFalse("family mismatch", LanAddresses.isWithin(ip("fd00::1"), ip("192.168.1.0"), 24))
        assertTrue(LanAddresses.isWithin(ip("fd12:3456::abcd"), ip("fd12:3456::"), 64))
        assertFalse(LanAddresses.isWithin(ip("fd12:3457::abcd"), ip("fd12:3456::"), 64))
        assertTrue("/0 matches everything", LanAddresses.isWithin(ip("8.8.8.8"), ip("0.0.0.0"), 0))

        assertEquals("192.168.1.0/24", LanAddresses.network(ip("192.168.1.77"), 24))
        assertEquals("10.4.0.0/14", LanAddresses.network(ip("10.7.200.1"), 14))
        assertEquals("fd12:3456:0:0:0:0:0:0/64", LanAddresses.network(ip("fd12:3456::abcd"), 64))
        assertNull(LanAddresses.network(ip("192.168.1.77"), 33))
    }

    @Test
    fun privateRanges() {
        for (p in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1", "169.254.1.1", "fd00::1", "fc00::1", "fe80::1")) {
            assertTrue(p, LanAddresses.isPrivate(ip(p)))
        }
        for (p in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.0.1", "100.64.0.1", "2001:4860:4860::8888", "9.9.9.9")) {
            assertFalse(p, LanAddresses.isPrivate(ip(p)))
        }
    }

    @Test
    fun resolverScopeKeepsTheProbeOnTheLan() {
        val gw = ip("192.168.1.1")
        val prefixes = listOf(ip("192.168.1.23") to 24)
        assertEquals(ResolverScope.GATEWAY, LanAddresses.resolverScope(ip("192.168.1.1"), gw, prefixes))
        assertEquals(ResolverScope.LAN, LanAddresses.resolverScope(ip("192.168.1.53"), gw, prefixes))
        assertEquals("Pi-hole on another private subnet", ResolverScope.LAN, LanAddresses.resolverScope(ip("10.0.0.2"), gw, prefixes))
        assertEquals("public resolver via DHCP", ResolverScope.OFF_LAN, LanAddresses.resolverScope(ip("8.8.8.8"), gw, prefixes))
        assertEquals(ResolverScope.OFF_LAN, LanAddresses.resolverScope(ip("1.1.1.1"), null, emptyList()))
        assertEquals(ResolverScope.OFF_LAN, LanAddresses.resolverScope(ip("2606:4700:4700::1111"), gw, prefixes))
        assertEquals("router's link-local IPv6", ResolverScope.LAN, LanAddresses.resolverScope(ip("fe80::1"), gw, prefixes))
        assertEquals(ResolverScope.LAN, LanAddresses.resolverScope(ip("192.168.1.1"), null, emptyList()))
    }

    @Test
    fun fingerprintHashesNothingReadable() {
        val f = NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("8.8.8.8", "192.168.1.1"), "192.168.1.0/24", ssid = "Home")!!
        assertEquals("gw=192.168.1.1;dhcp=192.168.1.1;dns=192.168.1.1,8.8.8.8;net=192.168.1.0/24", f.canonical)
        assertEquals(64, f.hash.length)
        assertTrue(f.hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(NetworkFingerprint.PREFIX_LENGTH, f.prefixTag.length)
        assertTrue(f.hash.startsWith(f.prefixTag))
        assertEquals("router 192.168.1.1 · 192.168.1.0/24 · DNS 192.168.1.1, 8.8.8.8", f.label)
        assertEquals("same setup, no SSID: same hash", f.hash, NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("192.168.1.1", "8.8.8.8"), "192.168.1.0/24")!!.hash)
        assertNotEquals(f.hash, NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("192.168.1.1"), "192.168.1.0/24")!!.hash)
        assertNotEquals(f.hash, NetworkFingerprint.of("192.168.0.1", "192.168.0.1", listOf("192.168.1.1", "8.8.8.8"), "192.168.0.0/24")!!.hash)

        assertNull("nothing to tell networks apart", NetworkFingerprint.of(null, "192.168.1.1", listOf("1.1.1.1"), null))
        val v6only = NetworkFingerprint.of(null, null, emptyList(), "fd12:3456:0:0:0:0:0:0/64")!!
        assertEquals("fd12:3456:0:0:0:0:0:0/64", v6only.label)
        val dhcpElsewhere = NetworkFingerprint.of("192.168.1.1", "192.168.1.2", emptyList(), null)!!
        assertEquals("router 192.168.1.1 · DHCP 192.168.1.2", dhcpElsewhere.label)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", NetworkFingerprint.sha256Hex(""))
    }
}
