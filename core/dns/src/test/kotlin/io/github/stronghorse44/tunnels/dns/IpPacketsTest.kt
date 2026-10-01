package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class IpPacketsTest {
    private val v4Client = InetAddress.getByName("10.111.0.2")
    private val v4Dns = InetAddress.getByName("10.111.0.1")
    private val v6Client = InetAddress.getByName("fd00:7a7a::2")
    private val v6Dns = InetAddress.getByName("fd00:7a7a::1")
    private val payload = DnsMessage.query(0xBEEF, "example.com")

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    @Test
    fun buildsAndParsesIpv4Udp() {
        val p = IpPackets.buildUdp(v4Client, v4Dns, 40000, 53, payload)
        assertEquals(20 + 8 + payload.size, p.size)
        assertEquals(0x45, p[0].toInt() and 0xFF)
        assertEquals(p.size, u16(p, 2))
        assertEquals(17, p[9].toInt())
        assertEquals(0, IpPackets.finish(IpPackets.sum(p, 0, 20)))
        val r = IpPackets.parse(p) as IpPacket.Udp
        assertEquals(4, r.version)
        assertEquals(v4Client, r.src)
        assertEquals(v4Dns, r.dst)
        assertEquals(40000, r.srcPort)
        assertEquals(53, r.dstPort)
        assertArrayEquals(payload, r.payload)
        assertTrue(r.ipChecksumOk)
        assertTrue(r.udpChecksumOk)
        assertEquals("example.com", DnsMessage.parse(r.payload).queryName)
    }

    @Test
    fun buildsAndParsesIpv6Udp() {
        val p = IpPackets.buildUdp(v6Dns, v6Client, 53, 51000, payload)
        assertEquals(40 + 8 + payload.size, p.size)
        assertEquals(0x60, p[0].toInt() and 0xFF)
        assertEquals(8 + payload.size, u16(p, 4))
        assertEquals(17, p[6].toInt())
        val r = IpPackets.parse(p) as IpPacket.Udp
        assertEquals(6, r.version)
        assertEquals(v6Dns, r.src)
        assertEquals(v6Client, r.dst)
        assertEquals(53, r.srcPort)
        assertEquals(51000, r.dstPort)
        assertArrayEquals(payload, r.payload)
        assertTrue(r.udpChecksumOk)
    }

    @Test
    fun knownIpv4HeaderChecksum() {
        // Textbook example (RFC 1071 / Wikipedia): header checksum must be 0xB861.
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xC0.toByte(), 0xA8.toByte(), 0x00, 0x01, 0xC0.toByte(), 0xA8.toByte(), 0x00, 0xC7.toByte(),
        )
        assertEquals(0xB861, IpPackets.finish(IpPackets.sum(header, 0, header.size)))
    }

    @Test
    fun detectsCorruptedChecksums() {
        val p = IpPackets.buildUdp(v4Client, v4Dns, 40000, 53, payload)
        val ipBad = p.copyOf().also { it[15] = (it[15] + 1).toByte() }
        val r1 = IpPackets.parse(ipBad) as IpPacket.Udp
        assertFalse(r1.ipChecksumOk)
        val udpBad = p.copyOf().also { it[p.size - 1] = (it[p.size - 1] + 1).toByte() }
        val r2 = IpPackets.parse(udpBad) as IpPacket.Udp
        assertTrue(r2.ipChecksumOk)
        assertFalse(r2.udpChecksumOk)
        // IPv4 allows no UDP checksum at all.
        val none = p.copyOf().also { it[26] = 0; it[27] = 0 }
        assertTrue((IpPackets.parse(none) as IpPacket.Udp).udpChecksumOk)
        // IPv6 does not.
        val p6 = IpPackets.buildUdp(v6Client, v6Dns, 1, 2, payload).also { it[46] = 0; it[47] = 0 }
        assertFalse((IpPackets.parse(p6) as IpPacket.Udp).udpChecksumOk)
    }

    @Test
    fun zeroChecksumIsSentAsAllOnes() {
        // Find a payload whose UDP checksum folds to zero by brute force over a small space; the
        // builder must write 0xFFFF then, since 0 means "no checksum" on IPv4.
        var found = false
        for (a in 0..255) for (b in 0..255) {
            val p = IpPackets.buildUdp(v4Client, v4Dns, 1, 1, byteArrayOf(a.toByte(), b.toByte()))
            val cs = u16(p, 26)
            assertTrue(cs != 0)
            if (cs == 0xFFFF) found = true
            if (found) break
        }
        assertTrue("some payload yields the all-ones checksum", found)
    }

    @Test
    fun reportsTcpAndOtherProtocolsWithPort() {
        val tcp = IpPackets.buildUdp(v4Client, v4Dns, 40000, 853, payload).also { it[9] = 6 }
        val r = IpPackets.parse(tcp) as IpPacket.Other
        assertEquals(6, r.protocol)
        assertEquals(40000, r.srcPort)
        assertEquals(853, r.dstPort)
        assertEquals(v4Client, r.src)
        assertEquals(v4Dns, r.dst)
        val icmp = tcp.copyOf().also { it[9] = 1 }
        assertEquals(IpPacket.Other(4, 1, v4Client, v4Dns, null, null), IpPackets.parse(icmp))
        val tcp6 = IpPackets.buildUdp(v6Client, v6Dns, 1, 853, payload).also { it[6] = 6 }
        assertEquals(IpPacket.Other(6, 6, v6Client, v6Dns, 1, 853), IpPackets.parse(tcp6))
        val hopByHop = tcp6.copyOf().also { it[6] = 0 }
        assertEquals(IpPacket.Other(6, 0, v6Client, v6Dns, null, null), IpPackets.parse(hopByHop))
        // A TCP header cut short still reports the protocol, without ports.
        val stub = tcp.copyOf(22).also { it[2] = 0; it[3] = 22 }
        assertEquals(IpPacket.Other(4, 6, v4Client, v4Dns, null, null), IpPackets.parse(stub))
    }

    @Test
    fun malformedPacketsAreReportedNotThrown() {
        fun bad(p: ByteArray, length: Int = p.size) = IpPackets.parse(p, length) as? IpPacket.Malformed ?: fail("expected Malformed")
        bad(ByteArray(0))
        bad(ByteArray(20)) // version 0
        bad(byteArrayOf(0x45) + ByteArray(10)) // truncated v4 header
        bad(byteArrayOf(0x60) + ByteArray(20)) // truncated v6 header
        val p = IpPackets.buildUdp(v4Client, v4Dns, 40000, 53, payload)
        bad(p, p.size - 1) // total length larger than the buffer
        bad(p.copyOf().also { it[0] = 0x44 }) // IHL below 20
        bad(p.copyOf().also { it[2] = 0; it[3] = 10 }) // total length below header
        bad(p.copyOf().also { it[24] = 0xFF.toByte(); it[25] = 0xFF.toByte() }) // UDP length past the packet
        bad(p.copyOf().also { it[24] = 0; it[25] = 4 }) // UDP length below 8
        bad(p.copyOf(21)) // IPv4 with 1 byte of UDP header, total length says more
        val shortUdp = IpPackets.buildUdp(v4Client, v4Dns, 1, 1, ByteArray(0)).copyOf(24).also { it[2] = 0; it[3] = 24 }
        bad(shortUdp)
        val p6 = IpPackets.buildUdp(v6Client, v6Dns, 1, 1, payload)
        bad(p6, p6.size - 1)
        try {
            IpPackets.parse(p, p.size + 1)
            // parse clamps instead of throwing
        } catch (e: Exception) {
            fail("must not throw: $e")
        }
    }

    @Test
    fun builderRejectsBadArguments() {
        try {
            IpPackets.buildUdp(v4Client, v6Dns, 1, 1, payload)
            fail("mixed families")
        } catch (_: IllegalArgumentException) {
        }
        try {
            IpPackets.buildUdp(v4Client, v4Dns, 70000, 1, payload)
            fail("port")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun responsePacketRoundTrip() {
        // The service builds the reply by swapping addresses and ports of the query it saw.
        val query = IpPackets.parse(IpPackets.buildUdp(v4Client, v4Dns, 40000, 53, payload)) as IpPacket.Udp
        val answer = payload.copyOf().also { it[2] = 0x81.toByte() }
        val reply = IpPackets.buildUdp(query.dst, query.src, query.dstPort, query.srcPort, answer)
        val r = IpPackets.parse(reply) as IpPacket.Udp
        assertEquals(v4Dns, r.src)
        assertEquals(v4Client, r.dst)
        assertEquals(53, r.srcPort)
        assertEquals(40000, r.dstPort)
        assertTrue(DnsMessage.parse(r.payload).isResponse)
        assertTrue(r.udpChecksumOk && r.ipChecksumOk)
    }
}
