package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScanHostsTest {
    private val prefixes = listOf(LanScope.parseLiteral("192.168.1.0")!! to 24)
    private val own = listOf(LanScope.parseLiteral("192.168.1.2")!!)

    private fun hosts(max: Int) = ScanHosts(prefixes, own, max)

    @Test
    fun gatewayReservedBeforeDiscoverySurvivesTheCap() {
        // B03 follow-up 2: the gateway is reserved first, so a crowded network cannot crowd it out.
        val h = hosts(3)
        assertNotNull(h.reserve("192.168.1.1"))
        for (n in 10..12) assertNotNull("192.168.1.$n", h.record("192.168.1.$n"))
        assertNull(h.record("192.168.1.13"))
        assertEquals(4, h.hosts.size)
        assertTrue("192.168.1.1" in h.hosts)
        assertEquals("the gateway answering again is the same record, not a new host", h.hosts["192.168.1.1"], h.record("192.168.1.1"))
        assertEquals(1, h.overCap)
        // Without a reservation the cap applies to everyone, which is how the gateway used to be lost.
        val late = hosts(3)
        for (n in 10..12) late.record("192.168.1.$n")
        assertNull(late.record("192.168.1.1"))
    }

    @Test
    fun capCountsOverflowOnce() {
        val h = hosts(2)
        h.record("192.168.1.10")
        h.record("192.168.1.11")
        repeat(3) { assertNull(h.record("192.168.1.20")) }
        assertNull(h.record("192.168.1.21"))
        assertEquals("distinct addresses, not attempts", 2, h.overCap)
        assertNotNull("a host already in keeps answering", h.record("192.168.1.10"))
        assertEquals(2, h.overCap)
        assertEquals(0, h.dropped.count)
        // An address beyond the cap is not remembered anywhere in the table.
        assertTrue(h.hosts.keys.none { it.endsWith(".20") || it.endsWith(".21") })
    }

    @Test
    fun outOfScopeIsDropped() {
        val h = hosts(5)
        assertNull(h.record("10.0.0.5"))
        assertNull(h.record("10.0.0.5"))
        assertNull(h.record("8.8.8.8"))
        assertNull(h.record("not an address"))
        assertNull(h.record("192.168.1.255.1"))
        assertEquals("distinct, and an address that does not parse counts once", 4, h.dropped.count)
        assertNull("the phone itself is neither recorded nor counted", h.record("192.168.1.2"))
        assertEquals(4, h.dropped.count)
        assertNull(h.reserve("172.16.0.1"))
        assertNull(h.reserve(null))
        assertTrue(h.hosts.isEmpty())
        assertEquals(0, h.overCap)
        assertSame(h.record("192.168.1.9"), h.record("192.168.1.9"))
    }

    @Test
    fun recordsFromManyThreadsNeverPassTheCap() {
        val h = hosts(10)
        h.reserve("192.168.1.1")
        val pool = Executors.newFixedThreadPool(8)
        for (n in 10..99) pool.execute { h.record("192.168.1.$n") }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(11, h.hosts.size)
        assertEquals(80, h.overCap)
    }

    @Test
    fun recordKeepsTheTwoSmallestUuids() {
        val r = LanHostRecord("192.168.1.9")
        listOf("cccccccc-1", "aaaaaaaa-1", "bbbbbbbb-1", "aaaaaaaa-1").forEach(r::addUuid)
        assertEquals(listOf("aaaaaaaa-1", "bbbbbbbb-1"), r.ssdpUuids)
    }
}

class MdnsAddressTest {
    private fun ip(text: String): InetAddress = LanScope.parseLiteral(text)!!
    private val prefixes = listOf(ip("192.168.1.0") to 24, ip("fe80::") to 64, ip("fd00::") to 64)
    private val own = listOf(ip("192.168.1.2"))
    private val inScope: (InetAddress) -> Boolean = { LanScope.accepts(it, prefixes, own) }

    @Test
    fun linkLocalOnlyIsNotOutOfScope() {
        // B03 follow-up 3: a service that offers only link-local addresses is not "outside this network".
        assertEquals(MdnsPick.LinkLocalOnly, MdnsAddress.pick(listOf(ip("fe80::1234")), inScope))
        assertEquals(MdnsPick.LinkLocalOnly, MdnsAddress.pick(listOf(ip("fe80::1234"), ip("169.254.7.7")), { false }))
        assertEquals(MdnsPick.LinkLocalOnly, MdnsAddress.pick(listOf(ip("169.254.7.7")), { false }))
        // Link-local plus an address outside the network is outside, not link-local only.
        val mixed = MdnsAddress.pick(listOf(ip("fe80::1234"), ip("10.9.9.9")), inScope)
        assertEquals(MdnsPick.OutOfScope(ip("10.9.9.9")), mixed)
    }

    @Test
    fun prefersInScopeIpv4() {
        assertEquals(MdnsPick.Use("192.168.1.30"), MdnsAddress.pick(listOf(ip("fd00::30"), ip("192.168.1.30")), inScope))
        assertEquals(MdnsPick.Use("192.168.1.30"), MdnsAddress.pick(listOf(ip("10.1.1.1"), ip("192.168.1.30")), inScope))
        // No IPv4 inside: a unique-local IPv6 one, never the link-local one.
        assertEquals(MdnsPick.Use("fd00:0:0:0:0:0:0:30"), MdnsAddress.pick(listOf(ip("fe80::30"), ip("fd00::30")), inScope))
        // The phone's own address and loopback are not a device.
        assertEquals(MdnsPick.OutOfScope(ip("192.168.1.2")), MdnsAddress.pick(listOf(ip("192.168.1.2")), inScope))
    }

    @Test
    fun outsideOnlyIsOutOfScope() {
        assertEquals(MdnsPick.OutOfScope(ip("10.9.9.9")), MdnsAddress.pick(listOf(ip("10.9.9.9"), ip("8.8.8.8")), inScope))
        assertEquals(MdnsPick.OutOfScope(null), MdnsAddress.pick(emptyList(), inScope))
        assertEquals(MdnsPick.OutOfScope(ip("2001:db8::1")), MdnsAddress.pick(listOf(ip("2001:db8::1")), inScope))
    }
}
