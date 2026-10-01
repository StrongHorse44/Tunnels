package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRulesTest {
    private val t = LanKeys.TUNNEL_ID

    private fun host(
        ip: String,
        name: String? = null,
        kind: HostKind = HostKind.UNKNOWN,
        services: List<String> = emptyList(),
        open: List<Int> = emptyList(),
        upnp: Boolean = false,
    ): List<Observation> = buildList {
        name?.let { add(Observation(t, ip, LanKeys.HOST_NAME, it)) }
        add(Observation(t, ip, LanKeys.HOST_KIND, kind.label))
        add(Observation(t, ip, LanKeys.HOST_SERVICES, LanKeys.list(services)))
        add(Observation(t, ip, LanKeys.HOST_OPEN_PORTS, LanKeys.portList(open)))
        add(Observation(t, ip, LanKeys.HOST_RISKY, LanKeys.portList(PortCatalog.riskyOf(open))))
        add(Observation(t, ip, LanKeys.HOST_UPNP, upnp.toString()))
    }

    private fun router(upnp: String = LanKeys.FALSE, hijack: String = LanKeys.FALSE): List<Observation> = listOf(
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_IP, "192.168.1.1"),
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_UPNP_IGD, upnp),
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_HIJACK, hijack),
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_IS_GATEWAY, LanKeys.TRUE),
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_PRIVATE_DNS, LanKeys.FALSE),
        Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_OPEN_PORTS, "53,80,443"),
    )

    private fun summary(gate: String = LanKeys.GATE_CONFIRMED, network: String = "ab12cd34", hosts: Int = 1): List<Observation> = buildList {
        add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, gate))
        if (gate == LanKeys.GATE_CONFIRMED) {
            add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, network))
            add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_TOTAL, hosts.toString()))
            add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_RISKY, "0"))
            add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_DURATION, "42"))
        } else {
            add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE_REASON, LanKeys.REASON_NOT_CONFIRMED))
        }
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return LanRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun riskyServiceNamesThePortsInPlainWords() {
        val nas = host("192.168.1.20", name = "DiskStation", kind = HostKind.NAS, open = listOf(80, 443, 445, 22))
        val tv = host("192.168.1.30", kind = HostKind.TV, open = listOf(8008, 8443))
        val telnet = host("192.168.1.40", open = listOf(23, 21))
        val drafts = evaluate(nas + tv + telnet + router() + summary()).of(LanRules.RISKY_SERVICE)
        assertEquals(setOf("192.168.1.20", "192.168.1.40"), drafts.map { it.subject }.toSet())
        val d = drafts.single { it.subject == "192.168.1.20" }
        assertEquals(Severity.WARN, d.severity)
        assertEquals("DiskStation accepts connections for Windows file sharing (SMB) (445). Anyone on this Wi-Fi can try to log in or read files.", d.evidence)
        val e = drafts.single { it.subject == "192.168.1.40" }
        assertTrue(e.evidence, e.evidence.startsWith("This device accepts connections for FTP file transfer (21), Telnet remote login (23)."))
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun routerSubjectNeverGetsHostRules() {
        val r = router() + listOf(Observation(t, LanKeys.SUBJECT_ROUTER, LanKeys.HOST_RISKY, "23"))
        assertTrue(evaluate(r + summary()).of(LanRules.RISKY_SERVICE).isEmpty())
        assertTrue(evaluate(r + summary()).of(LanRules.CAMERA_OPEN_WEB).isEmpty())
    }

    @Test
    fun cameraOpenWeb() {
        val cam = host("192.168.1.50", name = "Front door", kind = HostKind.CAMERA, open = listOf(554, 80))
        val quietCam = host("192.168.1.51", kind = HostKind.CAMERA, open = listOf(8080))
        val notCam = host("192.168.1.52", kind = HostKind.NAS, open = listOf(80, 443))
        val drafts = evaluate(cam + quietCam + notCam + summary()).of(LanRules.CAMERA_OPEN_WEB)
        assertEquals(listOf("192.168.1.50"), drafts.map { it.subject })
        assertEquals(Severity.WARN, drafts.single().severity)
        assertTrue(drafts.single().evidence, drafts.single().evidence.startsWith("Front door serves Web page (HTTP) (80), Video stream (RTSP) (554) to every device"))
    }

    @Test
    fun routerRulesFollowTheTriState() {
        assertTrue(evaluate(router() + summary()).of(LanRules.UPNP_IGD_ENABLED).isEmpty())
        assertTrue(evaluate(router(upnp = LanKeys.UNKNOWN, hijack = LanKeys.UNKNOWN) + summary()).none { it.subject == LanKeys.SUBJECT_ROUTER })
        val both = evaluate(router(upnp = LanKeys.TRUE, hijack = LanKeys.TRUE) + summary())
        val upnp = both.of(LanRules.UPNP_IGD_ENABLED).single()
        assertEquals(LanKeys.SUBJECT_ROUTER, upnp.subject)
        assertEquals(Severity.NOTICE, upnp.severity)
        assertTrue(upnp.evidence.startsWith("Your router accepts UPnP port-mapping requests from any device on the LAN"))
        val dns = both.of(LanRules.DNS_HIJACK).single()
        assertEquals(Severity.CRITICAL, dns.severity)
        assertTrue(dns.evidence.startsWith("Your router's DNS answers for names that do not exist"))
        assertTrue(both.none { it.sticky })
    }

    @Test
    fun newHostIsStickyAndSkipsTheFirstScanAndFreshBaselines() {
        val base = host("192.168.1.20", name = "DiskStation", kind = HostKind.NAS) + router() + summary(hosts = 1)
        val withNew = host("192.168.1.20", name = "DiskStation", kind = HostKind.NAS) + host("192.168.1.77", name = "shellyplug", kind = HostKind.IOT) +
            router() + summary(hosts = 2)
        assertTrue(evaluate(withNew).of(LanRules.NEW_HOST).isEmpty())
        assertTrue(evaluate(withNew, withNew).of(LanRules.NEW_HOST).isEmpty())
        val d = evaluate(withNew, base).of(LanRules.NEW_HOST).single()
        assertEquals("192.168.1.77", d.subject)
        assertEquals(Severity.INFO, d.severity)
        assertTrue(d.sticky)
        assertEquals("A new device (shellyplug, iot) joined your network at 192.168.1.77 since the last scan.", d.evidence)

        // Previous scan was refused by the gate: everything would be "new", so nothing is.
        assertTrue(evaluate(withNew, summary(gate = LanKeys.GATE_UNCONFIRMED)).of(LanRules.NEW_HOST).isEmpty())
        // Different network (fingerprint hash changed): also a fresh baseline.
        val otherNet = host("10.0.0.5", kind = HostKind.UNKNOWN) + summary(network = "ffffeeee", hosts = 1)
        assertTrue(evaluate(withNew, otherNet).of(LanRules.NEW_HOST).isEmpty())
        // An unnamed unknown host still reads sensibly.
        val anon = withNew + host("192.168.1.88")
        val e = evaluate(anon, withNew).of(LanRules.NEW_HOST).single()
        assertEquals("A new device (unidentified device) joined your network at 192.168.1.88 since the last scan.", e.evidence)
    }

    @Test
    fun hostsWithoutRiskyPortsProduceNoDrafts() {
        val clean = host("192.168.1.2", kind = HostKind.PHONE, open = listOf(8080)) + host("192.168.1.3", kind = HostKind.TV, open = listOf(8008, 8443, 9000))
        assertTrue(evaluate(clean + router() + summary()).isEmpty())
    }
}
