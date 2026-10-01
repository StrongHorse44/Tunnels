package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogsTest {
    @Test
    fun portCatalogCoversTheBriefAndMarksRiskyPorts() {
        val expected = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 548, 554, 631, 853, 993, 995, 1883, 1900, 3389, 5000, 5353, 5900, 8008, 8080, 8443, 8883, 9100, 32400, 49152)
        assertEquals(expected, PortCatalog.ports)
        assertEquals(PortCatalog.ports.size, PortCatalog.ports.toSet().size)
        assertTrue(setOf(21, 23, 445, 3389, 5900).all { it in PortCatalog.risky })
        assertTrue(setOf(22, 80, 443, 631).none { it in PortCatalog.risky })
        assertEquals(listOf(23, 445), PortCatalog.riskyOf(listOf(445, 80, 23, 22)))
        assertEquals("Telnet remote login (23), Windows file sharing (SMB) (445)", PortCatalog.describe(listOf(445, 23)))
        assertEquals("port 12345", PortCatalog.describe(listOf(12345)))
        assertTrue(PortCatalog.describe((1..10).toList()).endsWith("and 5 more"))
        assertTrue(PortCatalog.all.all { it.service.isNotBlank() && it.note.isNotBlank() && it.tag.isNotBlank() })
    }

    @Test
    fun mdnsTypesNormalizeAndShorten() {
        assertEquals(16, MdnsTypes.types.size)
        assertEquals("_http._tcp", MdnsTypes.normalize("_http._tcp.local."))
        assertEquals("_http._tcp", MdnsTypes.normalize("_http._tcp."))
        assertEquals("http", MdnsTypes.shortName("_http._tcp.local."))
        assertEquals("spotify-connect", MdnsTypes.shortName("_spotify-connect._tcp"))
        assertEquals(HostKind.PRINTER, MdnsTypes.byType("_ipp._tcp.")!!.kindHint)
        assertNull(MdnsTypes.byType("_nothing._udp"))
    }

    @Test
    fun keysParseAndFormatLists() {
        assertEquals(listOf(22, 80, 443), LanKeys.ports("443, 22,80,22"))
        assertEquals(emptyList<Int>(), LanKeys.ports("none"))
        assertEquals(emptyList<Int>(), LanKeys.ports(null))
        assertEquals(emptyList<Int>(), LanKeys.ports("0,70000,abc"))
        assertEquals("none", LanKeys.portList(emptyList()))
        assertEquals("22,80", LanKeys.portList(listOf(80, 22, 80)))
        assertEquals("none", LanKeys.list(emptyList()))
        assertEquals("http,ipp", LanKeys.list(listOf("http", "ipp", "http")))
        assertEquals(listOf("http", "ipp"), LanKeys.items("http, ipp"))
        assertEquals(emptyList<String>(), LanKeys.items("none"))
        assertTrue(LanKeys.isHostSubject("192.168.1.9"))
        assertTrue(!LanKeys.isHostSubject(LanKeys.SUBJECT_ROUTER) && !LanKeys.isHostSubject(LanKeys.SUBJECT_SUMMARY))
    }

    @Test
    fun vendorHints() {
        assertEquals("Sonos", VendorHints.vendorOf("Linux UPnP/1.0 Sonos/70.3-35220 (ZPS1)"))
        assertEquals("AVM FRITZ!Box", VendorHints.vendorOf("FRITZ!Box 7590 UPnP/1.0 AVM FRITZ!Box 7590 154.07.29"))
        assertEquals("MiniUPnPd", VendorHints.vendorOf("Linux/3.10 UPnP/1.1 MiniUPnPd/2.1"))
        assertEquals("Google", VendorHints.vendorOf(null, "Chromecast-Ultra"))
        assertEquals("Apple", VendorHints.vendorOf("Apple TV"))
        assertNull(VendorHints.vendorOf(null, null))
        assertNull(VendorHints.vendorOf("Linux/5.4 UPnP/1.0"))
        assertEquals(HostKind.CAMERA, VendorHints.kindOf("hikvision ds-2cd2"))
        assertEquals(HostKind.ROUTER, VendorHints.kindOf("openwrt/23.05 upnp/1.1 miniupnpd/2.3"))
        assertNull(VendorHints.kindOf("samsung"))
    }
}
