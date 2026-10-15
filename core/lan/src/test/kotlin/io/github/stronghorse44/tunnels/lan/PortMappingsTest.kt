package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PortMappingsTest {
    private val description = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
        <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/ctl/L3F</controlURL></service></serviceList>
        <deviceList><device><deviceList><device><serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>ctl/PPP</controlURL></service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId>
            <controlURL>/ctl/IPConn</controlURL>
          </service>
        </serviceList></device></deviceList></device></deviceList></device></root>
    """.trimIndent()

    private fun entry(ext: Int, client: String, internal: Int, proto: String = "TCP", desc: String = "Plex Media Server", remote: String = "") = """
        <?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:GetGenericPortMappingEntryResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
        <NewRemoteHost>$remote</NewRemoteHost><NewExternalPort>$ext</NewExternalPort><NewProtocol>$proto</NewProtocol>
        <NewInternalPort>$internal</NewInternalPort><NewInternalClient>$client</NewInternalClient><NewEnabled>1</NewEnabled>
        <NewPortMappingDescription>$desc</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>
        </u:GetGenericPortMappingEntryResponse></s:Body></s:Envelope>
    """.trimIndent()

    @Test
    fun controlUrlsAreFoundAndMadeAbsolute() {
        val controls = PortMappings.controlsOf(description, "http://192.168.1.1:5000/rootDesc.xml")
        assertEquals(
            listOf(
                IgdControl("http://192.168.1.1:5000/ctl/IPConn", "urn:schemas-upnp-org:service:WANIPConnection:1"),
                IgdControl("http://192.168.1.1:5000/ctl/PPP", "urn:schemas-upnp-org:service:WANPPPConnection:1"),
            ),
            controls,
        )
        val withBase = PortMappings.controlsOf("<URLBase>http://192.168.1.1:49000/</URLBase>$description", "http://192.168.1.1:5000/desc/root.xml")
        assertEquals("http://192.168.1.1:49000/ctl/IPConn", withBase.first().url)
        assertEquals("http://192.168.1.1:5000/desc/x", PortMappings.resolve("http://192.168.1.1:5000/desc/root.xml", "x"))
        assertNull(PortMappings.resolve("http://192.168.1.1/", "https://evil.example/x"))
        assertTrue(PortMappings.controlsOf("<root/>", "http://192.168.1.1/").isEmpty())
    }

    @Test
    fun requestsAreSoapPostsToTheControlPath() {
        val target = HttpLite.parseUrl("http://192.168.1.1:5000/ctl/IPConn")!!
        val req = PortMappings.entryRequest(target, "urn:schemas-upnp-org:service:WANIPConnection:1", 3)
        assertTrue(req.startsWith("POST /ctl/IPConn HTTP/1.0\r\nHost: 192.168.1.1:5000\r\n"))
        assertTrue(req.contains("SOAPAction: \"urn:schemas-upnp-org:service:WANIPConnection:1#GetGenericPortMappingEntry\"\r\n"))
        assertTrue(req.contains("<NewPortMappingIndex>3</NewPortMappingIndex>"))
        val body = req.substringAfter("\r\n\r\n")
        assertEquals(body.toByteArray().size, Regex("Content-Length: (\\d+)").find(req)!!.groupValues[1].toInt())
        val m = PortMapping("UDP", 3074, "192.168.1.40", 3074, true, "", null)
        val del = PortMappings.deleteRequest(target, "urn:schemas-upnp-org:service:WANIPConnection:1", m)
        assertTrue(del.contains("#DeletePortMapping\"") && del.contains("<NewRemoteHost></NewRemoteHost><NewExternalPort>3074</NewExternalPort><NewProtocol>UDP</NewProtocol>"))
    }

    @Test
    fun entriesAndErrorsAreRead() {
        val m = PortMappings.parseEntry(entry(32400, "192.168.1.20", 32400, desc = "Plex &amp; co"))!!
        assertEquals(PortMapping("TCP", 32400, "192.168.1.20", 32400, true, "", "Plex & co"), m)
        assertNull(PortMappings.parseEntry(entry(0, "192.168.1.20", 1)))
        assertNull(PortMappings.parseEntry(entry(80, "", 80)))
        assertNull(PortMappings.parseEntry(entry(80, "192.168.1.20", 80, proto = "SCTP")))
        val fault = "<s:Fault><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>713</errorCode><errorDescription>SpecifiedArrayIndexInvalid</errorDescription></UPnPError></detail></s:Fault>"
        assertEquals(713, PortMappings.errorCode(fault))
        assertTrue(PortMappings.endsList(500, 713))
        assertFalse(PortMappings.endsList(500, 606))
        assertFalse(PortMappings.endsList(200, null))

        val list = listOf(m, PortMapping("UDP", 3074, "192.168.1.40", 3074, false, "203.0.113.9", null), m.copy(description = "a,b;c=%"))
        assertEquals(list, PortMappings.parse(PortMappings.encode(list)))
        assertEquals(LanKeys.NONE, PortMappings.encode(emptyList()))
        assertTrue(PortMappings.parse(LanKeys.NONE).isEmpty())
        assertEquals("TCP 8443 → its port 443", PortMappings.ports(m.copy(externalPort = 8443, internalPort = 443)))
        assertTrue(PortMappings.closeResult(m, 200, null).startsWith("Closed: the router no longer forwards TCP 32400 to 192.168.1.20."))
        assertTrue(PortMappings.closeResult(m, 500, 606).contains("admin page"))
    }

    @Test
    fun eachOpeningIsAFindingRiskyOnesAreWarnings() {
        fun o(subject: String, key: String, value: String) = Observation(LanKeys.TUNNEL_ID, subject, key, value)
        val cam = PortMapping("TCP", 8554, "192.168.1.250", 554, true, "", "IPCam")
        val plex = PortMapping("TCP", 32400, "192.168.1.20", 32400, true, "", "Plex Media Server")
        val off = PortMapping("UDP", 9999, "192.168.1.20", 9999, false, "", null)
        val game = PortMapping("UDP", 3074, "192.168.1.40", 3074, true, "203.0.113.9", null)
        val current = listOf(
            o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED),
            o(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_IP, "192.168.1.1"),
            o(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_PORT_MAPPINGS, PortMappings.encode(listOf(cam, plex, off, game))),
            o("192.168.1.250", LanKeys.HOST_KIND, HostKind.CAMERA.label),
            o("192.168.1.250", LanKeys.HOST_LABEL, "Garage cam"),
            o("192.168.1.20", LanKeys.HOST_KIND, HostKind.NAS.label),
            o("192.168.1.20", LanKeys.HOST_NAME, "DiskStation"),
        )
        val drafts = LanRules.portOpened.evaluate(RuleContext(LanKeys.TUNNEL_ID, current, emptyList(), isFirstScan = false))
        assertEquals(
            listOf("192.168.1.250 · TCP 8554 to the internet", "192.168.1.20 · TCP 32400 to the internet", "192.168.1.40 · UDP 3074 to the internet"),
            drafts.map { it.subject },
        )
        assertEquals(listOf(Severity.WARN, Severity.NOTICE, Severity.NOTICE), drafts.map { it.severity })
        assertEquals(
            "Garage cam (192.168.1.250) asked your router to open TCP 8554 → its port 554 to the internet for \"IPCam\" (UPnP). " +
                "Anyone on the internet can reach its Video stream (RTSP): Live video, often from a camera.",
            drafts[0].evidence,
        )
        assertTrue(drafts[2].evidence.startsWith("The device at 192.168.1.40 asked your router to open UDP 3074 to the internet (UPnP)."))
        assertTrue(drafts[2].evidence.endsWith("The router limits it to the internet address 203.0.113.9."))
        assertTrue(drafts.none { it.sticky })
        assertEquals(Triple("192.168.1.250", "TCP", 8554), PortMappings.parseSubject(drafts[0].subject))
        assertNull(PortMappings.parseSubject("192.168.1.250"))
        assertTrue(LanGuides.wantsRouterAdmin(LanRules.PORT_OPENED))
        assertTrue(LanGuides.fix(LanRules.PORT_OPENED).contains("turn UPnP off"))

        // The router card reads the same list; nothing read is not "none".
        val router = LanSummary.from(current).router!!
        assertEquals(4, router.portMappings!!.size)
        assertNull(LanSummary.from(current.filterNot { it.key == LanKeys.ROUTER_PORT_MAPPINGS }).router!!.portMappings)
        val unconfirmed = current.filterNot { it.key == LanKeys.SCAN_GATE } + o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_UNCONFIRMED)
        assertTrue(LanRules.portOpened.evaluate(RuleContext(LanKeys.TUNNEL_ID, unconfirmed, emptyList(), isFirstScan = false)).isEmpty())
    }

    @Test
    fun riskyDoorsLookCloserFoundRaiseTheSameWarning() {
        fun o(subject: String, key: String, value: String) = Observation(LanKeys.TUNNEL_ID, subject, key, value)
        val at = LocalDateTime.of(2026, 10, 11, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val look = LookCloser.Summary(at, HostKind.TV, null, null, listOf(80, 5555, 23, 62078), emptyList(), null)
        val box = listOf(
            o("192.168.1.70", LanKeys.HOST_KIND, HostKind.TV.label),
            o("192.168.1.70", LanKeys.HOST_NAME, "TV box"),
            o("192.168.1.70", LanKeys.HOST_RISKY, LanKeys.NONE),
            o("192.168.1.70", LanKeys.HOST_CLOSER, look.encode()),
        )
        val drafts = LanRules.riskyService.evaluate(RuleContext(LanKeys.TUNNEL_ID, box, emptyList(), isFirstScan = false))
        // 23 is on the scan's own list, which found it closed: the scan wins there. 5555 only a look can see.
        assertEquals(
            "TV box accepts connections for Android debug bridge (ADB) (5555). Android debug bridge (ADB) (5555): found when you looked closer on 2026-10-11. " +
                "Anyone on this Wi-Fi can try to log in or read files.",
            drafts.single().evidence,
        )
        assertTrue(LanGuides.fix(LanRules.RISKY_SERVICE, listOf(5555)).contains("Developer options"))
        val clean = box.filterNot { it.key == LanKeys.HOST_CLOSER } + o("192.168.1.70", LanKeys.HOST_CLOSER, look.copy(openPorts = listOf(80)).encode())
        assertTrue(LanRules.riskyService.evaluate(RuleContext(LanKeys.TUNNEL_ID, clean, emptyList(), isFirstScan = false)).isEmpty())
    }
}
