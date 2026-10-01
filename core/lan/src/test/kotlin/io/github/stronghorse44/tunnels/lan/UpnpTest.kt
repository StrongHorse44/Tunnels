package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {
    private val igd = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
            <friendlyName>FRITZ!Box 7590</friendlyName>
            <manufacturer>AVM Berlin</manufacturer>
            <modelName>FRITZ!Box 7590</modelName>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType></service>
            </serviceList>
            <deviceList><device>
              <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
              <deviceList><device>
                <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                <serviceList>
                  <service><serviceType> urn:schemas-upnp-org:service:WANIPConnection:1 </serviceType></service>
                  <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType></service>
                </serviceList>
              </device></deviceList>
            </device></deviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun findsIgdServices() {
        val d = Upnp.scan(igd)
        assertTrue(d.hasIgd)
        assertEquals("FRITZ!Box 7590", d.friendlyName)
        assertEquals("AVM Berlin", d.manufacturer)
        assertEquals("FRITZ!Box 7590", d.modelName)
        assertEquals("FRITZ!Box 7590 (AVM Berlin)", d.label)
        assertEquals(
            listOf("urn:schemas-upnp-org:service:Layer3Forwarding:1", "urn:schemas-upnp-org:service:WANIPConnection:1"),
            d.serviceTypes,
        )
        assertEquals(3, d.deviceTypes.size)
    }

    @Test
    fun mediaRendererIsNotIgd() {
        val xml = "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Living Room &amp; Kitchen</friendlyName>" +
            "<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType></service></serviceList></device></root>"
        val d = Upnp.scan(xml)
        assertFalse(d.hasIgd)
        assertEquals("Living Room & Kitchen", d.friendlyName)
        assertEquals("Living Room & Kitchen", d.label)
        assertNull(d.manufacturer)
        assertTrue(Upnp.isIgdService("urn:schemas-upnp-org:service:WANPPPConnection:1"))
        assertFalse(Upnp.isIgdService("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1"))
    }

    @Test
    fun emptyAndBoundedInput() {
        val e = Upnp.scan("")
        assertEquals(UpnpDescription.EMPTY, e)
        assertNull(e.label)
        assertFalse(e.hasIgd)
        assertNull(Upnp.scan("<root>garbage").label)

        val padding = "<x>".repeat(Upnp.MAX_CHARS / 3 + 10)
        val late = padding + "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
        assertFalse("beyond the cap is not read", Upnp.scan(late).hasIgd)

        val many = (1..100).joinToString("") { "<serviceType>urn:x:service:S$it:1</serviceType>" }
        assertEquals(32, Upnp.scan(many).serviceTypes.size)
        val longName = "<friendlyName>" + "n".repeat(500) + "</friendlyName>"
        assertNull("over-long names are dropped by the regex bound", Upnp.scan(longName).friendlyName)
        assertEquals("Only maker", Upnp.scan("<manufacturer>Only maker</manufacturer>").label)
    }

    @Test
    fun igdVerdictNeverReportsAFailedCheckAsPassed() {
        val withIgd = Upnp.scan(igd)
        val plain = Upnp.scan("<root><device><friendlyName>Box</friendlyName></device></root>")
        assertEquals(LanKeys.TRUE, Upnp.igdVerdict(withIgd, advertisesIgd = false, locationsTried = 1, ssdpResponded = true))
        assertEquals(LanKeys.TRUE, Upnp.igdVerdict(null, advertisesIgd = true, locationsTried = 0, ssdpResponded = true))
        assertEquals(LanKeys.FALSE, Upnp.igdVerdict(plain, advertisesIgd = false, locationsTried = 1, ssdpResponded = true))
        assertEquals("gateway advertised but its description could not be read", LanKeys.UNKNOWN, Upnp.igdVerdict(null, false, locationsTried = 2, ssdpResponded = true))
        assertEquals("others answered, the gateway did not", LanKeys.FALSE, Upnp.igdVerdict(null, false, locationsTried = 0, ssdpResponded = true))
        assertEquals("nobody answered SSDP: not a pass", LanKeys.UNKNOWN, Upnp.igdVerdict(null, false, locationsTried = 0, ssdpResponded = false))
    }
}
