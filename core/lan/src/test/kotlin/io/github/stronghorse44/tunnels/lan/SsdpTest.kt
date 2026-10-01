package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpTest {
    @Test
    fun mSearchIsWellFormed() {
        val m = Ssdp.mSearch(mx = 2)
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(m.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.contains("MX: 2\r\n"))
        assertTrue(m.contains("ST: ssdp:all\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
        assertTrue(Ssdp.mSearch(mx = 99).contains("MX: 5\r\n"))
    }

    @Test
    fun parsesAResponseCaseInsensitively() {
        val text = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://192.168.1.1:49152/igd.xml\r\n" +
            "Server: Linux/3.10 UPnP/1.1 MiniUPnPd/2.1\r\nST: urn:schemas-upnp-org:service:WANIPConnection:1\r\n" +
            "USN: uuid:1234::urn:schemas-upnp-org:service:WANIPConnection:1\r\n\r\n"
        val r = Ssdp.parseResponse(text)!!
        assertEquals("http://192.168.1.1:49152/igd.xml", r.location)
        assertEquals("Linux/3.10 UPnP/1.1 MiniUPnPd/2.1", r.server)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", r.st)
        assertEquals("WANIPConnection", r.shortType)
        assertTrue(Ssdp.isIgdType(r.st))
        assertTrue(r.usn!!.startsWith("uuid:1234"))
    }

    @Test
    fun parsesNotifyAndRejectsJunk() {
        val notify = "NOTIFY * HTTP/1.1\nHOST: 239.255.255.250:1900\nNT: upnp:rootdevice\nNTS: ssdp:alive\nLOCATION: http://10.0.0.5/desc.xml\n\n"
        val r = Ssdp.parseResponse(notify)!!
        assertEquals("upnp:rootdevice", r.st)
        assertEquals("rootdevice", r.shortType)
        assertEquals("http://10.0.0.5/desc.xml", r.location)
        assertFalse(Ssdp.isIgdType(r.st))

        assertNull(Ssdp.parseResponse("HTTP/1.1 404 Not Found\r\n\r\n"))
        assertNull(Ssdp.parseResponse("hello world"))
        assertNull(Ssdp.parseResponse(""))
        assertNull(Ssdp.parseResponse("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun headersStopAtBlankLineAndStayBounded() {
        val lines = listOf("A: 1", "B:2", "nocolon", ": empty", "A: dup", "", "C: after blank")
        val h = Ssdp.parseHeaders(lines)
        assertEquals(mapOf("a" to "1", "b" to "2"), h)

        val many = (1..200).map { "H$it: v" }
        assertEquals(Ssdp.MAX_LINES, Ssdp.parseHeaders(many).size)
        val long = "L: " + "x".repeat(5000)
        assertTrue(Ssdp.parseHeaders(listOf(long))["l"]!!.length <= Ssdp.MAX_LINE_LENGTH)
    }

    @Test
    fun shortTypes() {
        assertEquals("MediaRenderer", Ssdp.shortType("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertEquals("dial", Ssdp.shortType("urn:dial-multiscreen-org:service:dial:1"))
        assertEquals("rootdevice", Ssdp.shortType("upnp:rootdevice"))
        assertEquals("ssdp:all", Ssdp.shortType("ssdp:all").let { "ssdp:all".takeIf { _ -> it == "all" } ?: it })
        assertTrue(Ssdp.isIgdType("urn:schemas-upnp-org:device:InternetGatewayDevice:2"))
        assertTrue(Ssdp.isIgdType("urn:schemas-upnp-org:service:WANPPPConnection:1"))
        assertFalse(Ssdp.isIgdType(null))
    }
}
