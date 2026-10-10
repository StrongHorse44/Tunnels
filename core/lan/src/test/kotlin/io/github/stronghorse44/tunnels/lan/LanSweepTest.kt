package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException

class LanSweepTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun targetsAreTheSlash24AroundThePhoneWithoutItselfOrKnownHosts() {
        val prefixes = listOf(ip("192.168.1.20") to 24)
        val t = LanSweep.targets(prefixes, known = setOf("192.168.1.1", "192.168.1.30"))
        assertEquals(251, t.size)
        assertEquals("192.168.1.2", t.first())
        assertEquals("192.168.1.254", t.last())
        assertTrue("192.168.1.20" !in t && "192.168.1.0" !in t && "192.168.1.255" !in t && "192.168.1.1" !in t)
    }

    @Test
    fun aWidePrefixIsSweptOnlyAroundThePhone() {
        val t = LanSweep.targets(listOf(ip("10.0.7.9") to 16))
        assertEquals(253, t.size)
        assertTrue(t.all { it.startsWith("10.0.7.") })
    }

    @Test
    fun aNarrowPrefixIsSweptWhole() {
        assertEquals(listOf("192.168.5.129", "192.168.5.131", "192.168.5.132", "192.168.5.133", "192.168.5.134"),
            LanSweep.targets(listOf(ip("192.168.5.130") to 29)))
    }

    @Test
    fun publicOrIpv6PrefixesAreNeverSwept() {
        assertTrue(LanSweep.targets(listOf(ip("8.8.8.8") to 24)).isEmpty())
        assertTrue(LanSweep.targets(listOf(ip("fd00::5") to 64)).isEmpty())
        assertTrue(LanSweep.targets(emptyList()).isEmpty())
    }

    @Test
    fun aRefusedConnectionMeansSomeoneIsThere() {
        assertEquals(LanSweep.Answer.PRESENT, LanSweep.classify(null))
        assertEquals(LanSweep.Answer.PRESENT, LanSweep.classify(java.net.ConnectException("failed to connect to /192.168.1.5 (port 80): connect failed: ECONNREFUSED (Connection refused)")))
        assertEquals(LanSweep.Answer.SILENT, LanSweep.classify(SocketTimeoutException("failed to connect to /192.168.1.5 (port 80) after 350ms")))
        assertEquals(LanSweep.Answer.SILENT, LanSweep.classify(java.net.NoRouteToHostException("EHOSTUNREACH (No route to host)")))
    }

    @Test
    fun ptrQueryNamesTheReversedAddress() {
        assertEquals("5.1.168.192.in-addr.arpa", ReverseDns.questionName("192.168.1.5"))
        assertNull(ReverseDns.questionName("fe80::1"))
        assertNull(ReverseDns.questionName("not an address"))
        val q = ReverseDns.query("192.168.1.5", 0x1234)!!
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(ReverseDns.TYPE_PTR, ((q[q.size - 4].toInt() and 0xFF) shl 8) or (q[q.size - 3].toInt() and 0xFF))
    }

    /** A reply to [query] with one PTR answer naming [target], the answer's name compressed to the question. */
    private fun reply(query: ByteArray, target: String, rcode: Int = 0, answers: Int = 1): ByteArray {
        val out = query.toMutableList()
        out[2] = 0x81.toByte()
        out[3] = (0x80 or rcode).toByte()
        out[7] = answers.toByte()
        if (answers == 0) return out.toByteArray()
        out += listOf(0xC0.toByte(), 0x0C.toByte())
        out += listOf(0, 12, 0, 1, 0, 0, 0, 60).map { it.toByte() }
        val name = target.split('.').flatMap { l -> listOf(l.length.toByte()) + l.map { it.code.toByte() } } + 0.toByte()
        out += listOf((name.size shr 8).toByte(), name.size.toByte())
        out += name
        return out.toByteArray()
    }

    @Test
    fun ptrRepliesAreParsed() {
        val q = ReverseDns.query("192.168.1.5", 77)!!
        assertEquals("Galaxy-S24.lan", ReverseDns.parse(77, reply(q, "Galaxy-S24.lan")))
        assertNull(ReverseDns.parse(78, reply(q, "Galaxy-S24.lan")))
        assertNull(ReverseDns.parse(77, reply(q, "x", rcode = 3)))
        assertNull(ReverseDns.parse(77, reply(q, "x", answers = 0)))
        assertNull(ReverseDns.parse(77, reply(q, "Galaxy-S24.lan").copyOf(q.size + 6)))
        assertNull(ReverseDns.parse(77, ByteArray(5)))
    }

    @Test
    fun onlyARealDeviceNameIsKept() {
        assertEquals("Galaxy-S24", ReverseDns.deviceName("Galaxy-S24.lan."))
        assertEquals("Ring-Doorbell-3F", ReverseDns.deviceName("Ring-Doorbell-3F"))
        assertNull(ReverseDns.deviceName("192-168-1-5.lan"))
        assertNull(ReverseDns.deviceName("host-192-168-1-5"))
        assertNull(ReverseDns.deviceName("192.168.1.5"))
        assertNull(ReverseDns.deviceName(""))
        assertNull(ReverseDns.deviceName(null))
    }

    @Test
    fun aRouterNameGivesASweptHostAnIdentity() {
        val swept = LanHostRecord("192.168.1.40").apply { this.swept = true; routerName = "Galaxy-S24" }
        val ids = DeviceIdentity.tokens("net", swept, HostKind.UNKNOWN, null)
        assertEquals(1, ids.size)
        assertTrue(ids.single().startsWith("h"))
        assertTrue(DeviceIdentity.isToken(ids.single()))
        // Same name, same token; a case or spacing difference does not make a new device.
        val again = LanHostRecord("192.168.1.41").apply { routerName = "galaxy-s24" }
        assertEquals(ids, DeviceIdentity.tokens("net", again, HostKind.UNKNOWN, null))
        // Nothing to go by: still no identity.
        assertTrue(DeviceIdentity.tokens("net", LanHostRecord("192.168.1.42"), HostKind.UNKNOWN, null).isEmpty())
    }

    @Test
    fun anAnnouncingHostKeepsItsPrimaryTokenWhenTheRouterNamesIt() {
        val host = LanHostRecord("192.168.1.50").apply { names += "Living Room TV" }
        val before = DeviceIdentity.tokens("net", host, HostKind.TV, null)
        host.routerName = "lgwebostv"
        val after = DeviceIdentity.tokens("net", host, HostKind.TV, null)
        assertEquals(before.first(), after.first())
        assertEquals(before.size + 1, after.size)
    }
}
