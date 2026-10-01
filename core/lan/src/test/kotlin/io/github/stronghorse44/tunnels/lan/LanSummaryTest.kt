package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSummaryTest {
    private val t = LanKeys.TUNNEL_ID

    @Test
    fun rebuildsHostsRouterAndTotals() {
        val obs = listOf(
            Observation(t, "192.168.1.20", LanKeys.HOST_NAME, "DiskStation"),
            Observation(t, "192.168.1.20", LanKeys.HOST_KIND, "nas"),
            Observation(t, "192.168.1.20", LanKeys.HOST_SERVICES, "smb,http"),
            Observation(t, "192.168.1.20", LanKeys.HOST_OPEN_PORTS, "80,445"),
            Observation(t, "192.168.1.20", LanKeys.HOST_RISKY, "445"),
            Observation(t, "192.168.1.20", LanKeys.HOST_UPNP, "true"),
            Observation(t, "192.168.1.20", LanKeys.HOST_VENDOR, "Synology"),
            Observation(t, "192.168.1.9", LanKeys.HOST_KIND, "unknown"),
            Observation(t, "192.168.1.9", LanKeys.HOST_SERVICES, "none"),
            Observation(t, "192.168.1.9", LanKeys.HOST_OPEN_PORTS, "none"),
            Observation(t, "192.168.1.9", LanKeys.HOST_RISKY, "none"),
            Observation(t, "192.168.1.9", LanKeys.HOST_UPNP, "false"),
            Observation(t, "192.168.1.100", LanKeys.HOST_KIND, "unknown"),
            Observation(t, "192.168.1.1", LanKeys.HOST_KIND, "router"),
            Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_IP, "192.168.1.1"),
            Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_UPNP_IGD, "true"),
            Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_HIJACK, "false"),
            Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_OPEN_PORTS, "53,80"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_TOTAL, "4"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_RISKY, "1"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_SSID, "ab12cd34"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_DURATION, "37"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, "confirmed"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_PARTIAL, "ports"),
            Observation("other_tunnel", "x", "y", "z"),
        )
        val s = LanSummary.from(obs)
        assertTrue(s.scanned)
        assertEquals(4, s.totalHosts)
        assertEquals(1, s.riskyHosts)
        assertEquals("ab12cd34", s.ssidPrefix)
        assertEquals(37, s.durationSec)
        assertEquals(listOf("ports"), s.partialStages)
        assertEquals(listOf("192.168.1.1", "192.168.1.20", "192.168.1.9", "192.168.1.100"), s.hosts.map { it.ip })
        val nas = s.hosts.single { it.ip == "192.168.1.20" }
        assertEquals("DiskStation", nas.title)
        assertEquals(HostKind.NAS, nas.kind)
        assertEquals(listOf("smb", "http"), nas.services)
        assertEquals(listOf(80, 445), nas.openPorts)
        assertEquals(listOf(445), nas.riskyPorts)
        assertTrue(nas.upnp)
        assertEquals("Synology", nas.vendor)
        assertEquals("192.168.1.9", s.hosts.single { it.ip == "192.168.1.9" }.title)
        val grouped = s.hostsByKind()
        assertEquals(listOf(HostKind.ROUTER, HostKind.NAS, HostKind.UNKNOWN), grouped.map { it.first })
        assertEquals(2, grouped.last().second.size)
        val r = s.router!!
        assertEquals("192.168.1.1", r.ip)
        assertEquals("true", r.upnpIgd)
        assertEquals("false", r.dnsHijack)
        assertEquals(LanKeys.UNKNOWN, r.privateDns)
        assertEquals(listOf(53, 80), r.openPorts)
    }

    @Test
    fun refusedScanAndEmptyInput() {
        assertEquals(LanSummary.EMPTY, LanSummary.from(emptyList()))
        assertEquals(LanSummary.EMPTY, LanSummary.from(listOf(Observation("other", "a", "b", "c"))))
        val refused = LanSummary.from(
            listOf(
                Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_UNCONFIRMED),
                Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE_REASON, LanKeys.REASON_NO_WIFI),
            ),
        )
        assertFalse(refused.scanned)
        assertEquals(LanKeys.REASON_NO_WIFI, refused.gateReason)
        assertNull(refused.router)
        assertTrue(refused.hosts.isEmpty())
        assertEquals(0, refused.totalHosts)
    }

    @Test
    fun ipSortKeyOrdersNumerically() {
        val ips = listOf("192.168.1.100", "192.168.1.9", "fe80::1", "10.0.0.1")
        assertEquals(listOf("10.0.0.1", "192.168.1.9", "192.168.1.100", "fe80::1"), ips.sortedBy(LanSummary::ipSortKey))
    }
}
