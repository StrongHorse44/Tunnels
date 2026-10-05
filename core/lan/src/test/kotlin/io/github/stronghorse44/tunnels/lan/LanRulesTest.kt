package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        ids: List<String> = emptyList(),
        vendor: String? = null,
    ): List<Observation> = buildList {
        name?.let { add(Observation(t, ip, LanKeys.HOST_NAME, it)) }
        vendor?.let { add(Observation(t, ip, LanKeys.HOST_VENDOR, it)) }
        if (ids.isNotEmpty()) add(Observation(t, ip, LanKeys.HOST_IDS, LanKeys.list(ids)))
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

    private fun summary(
        gate: String = LanKeys.GATE_CONFIRMED,
        network: String = "ab12cd34",
        hosts: Int = 1,
        census: String? = null,
        known: List<String> = emptyList(),
        partial: List<String> = emptyList(),
    ): List<Observation> = buildList {
        census?.let { add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, it)) }
        if (census != null && census != DeviceCensus.STATE_UNAVAILABLE) add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, LanKeys.list(known.sorted())))
        if (partial.isNotEmpty()) add(Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_PARTIAL, LanKeys.list(partial)))
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

    private fun tok(n: Int, type: Char = 'n') = type + "%016x".format(n)

    /** A router, the given hosts and a summary with a set-up list holding [known]. */
    private fun network(vararg hosts: List<Observation>, census: String? = DeviceCensus.STATE_SET, known: List<String> = emptyList(), partial: List<String> = emptyList()) =
        hosts.toList().flatten() + router() + summary(hosts = hosts.size, census = census, known = known, partial = partial)

    private val tv = host("192.168.1.23", name = "Living Room TV", kind = HostKind.TV, ids = listOf(tok(1, 'u'), tok(2)))
    private val printer = host("192.168.1.30", name = "Office Printer", kind = HostKind.PRINTER, ids = listOf(tok(3)))

    @Test
    fun unknownDeviceIsOneStickyNoticePerDevice() {
        val drafts = evaluate(network(tv, printer, host("192.168.1.31", name = "Plug", kind = HostKind.IOT, ids = listOf(tok(4))), known = listOf(tok(3)))).of(LanRules.UNKNOWN_DEVICE)
        assertEquals(listOf("Living Room TV · ${tok(1, 'u')}", "Plug · ${tok(4)}"), drafts.map { it.subject })
        val d = drafts.first()
        assertEquals(Severity.NOTICE, d.severity)
        assertTrue(d.sticky)
        assertEquals("A device that is not on your list for this network is here: Living Room TV (TV) at 192.168.1.23.", d.evidence)
        assertEquals(tok(1, 'u'), DeviceCensus.parsePrimary(d.subject))
        assertTrue(drafts.all { it.sticky })
        // No name and no vendor: a plain word, never the address that moves with the lease.
        val anon = evaluate(network(host("192.168.1.40", ids = listOf(tok(5, 'u'))), known = listOf(tok(3)))).of(LanRules.UNKNOWN_DEVICE).single()
        assertEquals("Unnamed device · ${tok(5, 'u')}", anon.subject)
        assertEquals("A device that is not on your list for this network is here: Unnamed device at 192.168.1.40.", anon.evidence)
        val vendored = evaluate(network(host("192.168.1.41", vendor = "Sony", kind = HostKind.TV, ids = listOf(tok(6))), known = listOf(tok(3)))).of(LanRules.UNKNOWN_DEVICE).single()
        assertEquals("Sony · ${tok(6)}", vendored.subject)
    }

    @Test
    fun acknowledgedDeviceRaisesNothingOnTheNextScan() {
        // The acceptance case: a device is unknown, the user taps Mine, the next scan carries its tokens in census:known.
        val before = network(tv, printer, known = listOf(tok(3)))
        assertEquals(1, evaluate(before).of(LanRules.UNKNOWN_DEVICE).size)
        val after = network(tv, printer, known = listOf(tok(3), tok(1, 'u'), tok(2)))
        assertTrue(evaluate(after, before).of(LanRules.UNKNOWN_DEVICE).isEmpty())
        assertTrue(evaluate(after, after).isEmpty())
        // The same device on a later scan with only its name token still matches.
        val nameOnly = network(host("192.168.1.99", name = "Living Room TV", kind = HostKind.TV, ids = listOf(tok(2))), printer, known = listOf(tok(3), tok(1, 'u'), tok(2)))
        assertTrue(evaluate(nameOnly, after).isEmpty())
    }

    @Test
    fun anyListedTokenMakesAHostKnown() {
        val oneOfTwo = network(tv, known = listOf(tok(2)))
        assertTrue(evaluate(oneOfTwo).of(LanRules.UNKNOWN_DEVICE).isEmpty())
        val none = network(tv, known = listOf(tok(77)))
        assertEquals(1, evaluate(none).of(LanRules.UNKNOWN_DEVICE).size)
        // Junk in a host's ids is not a token and never matches.
        val junk = network(host("192.168.1.50", name = "X", ids = listOf("zzz")), known = listOf("zzz"))
        assertEquals("unidentified · 192.168.1.50", evaluate(junk).of(LanRules.UNKNOWN_DEVICE).single().subject)
    }

    @Test
    fun gatewayIsNeverJudged() {
        val gateway = host("192.168.1.1", name = "Router", kind = HostKind.ROUTER)
        val withIds = host("192.168.1.1", name = "Router", kind = HostKind.ROUTER, ids = listOf(tok(40)))
        assertTrue(evaluate(network(gateway, tv, known = listOf(tok(1, 'u')))).of(LanRules.UNKNOWN_DEVICE).isEmpty())
        assertTrue(evaluate(network(withIds, tv, known = listOf(tok(1, 'u')))).of(LanRules.UNKNOWN_DEVICE).isEmpty())
        // It is not counted among the devices of a first scan either.
        val unset = evaluate(network(gateway, tv, census = DeviceCensus.STATE_UNSET)).of(LanRules.CENSUS_NOT_SET_UP).single()
        assertTrue(unset.evidence, unset.evidence.contains("1 device answered: Living Room TV."))
    }

    @Test
    fun hostWithoutIdsIsUnknown() {
        val d = evaluate(network(host("192.168.1.60", name = "Mystery", kind = HostKind.UNKNOWN), known = listOf(tok(3)))).of(LanRules.UNKNOWN_DEVICE).single()
        assertEquals("unidentified · 192.168.1.60", d.subject)
        assertNull(DeviceCensus.parsePrimary(d.subject))
        assertTrue(d.sticky)
        assertEquals(Severity.NOTICE, d.severity)
        assertTrue(d.evidence, d.evidence.contains("192.168.1.60") && d.evidence.contains("cannot be added"))
    }

    @Test
    fun notSetUpRaisesOneNoticeNotOnePerDevice() {
        val hosts = (1..10).map { host("192.168.1.${100 + it}", name = "Device $it", ids = listOf(tok(it))) }.toTypedArray()
        val drafts = evaluate(network(*hosts, census = DeviceCensus.STATE_UNSET))
        assertTrue(drafts.of(LanRules.UNKNOWN_DEVICE).isEmpty())
        val d = drafts.of(LanRules.CENSUS_NOT_SET_UP).single()
        assertEquals("Device census · ab12cd34", d.subject)
        assertEquals(Severity.NOTICE, d.severity)
        assertFalse("a state finding clears when the list is set up", d.sticky)
        assertEquals("ab12cd34", DeviceCensus.parseTag(d.subject))
        assertTrue(d.evidence, d.evidence.contains("10 devices answered: Device 1, Device 2, Device 3, Device 4, Device 5, Device 6, Device 7, Device 8 and 2 more."))
        // Nothing to put on the list: no finding.
        assertTrue(evaluate(network(census = DeviceCensus.STATE_UNSET)).of(LanRules.CENSUS_NOT_SET_UP).isEmpty())
        // A set list does not ask again.
        assertTrue(evaluate(network(*hosts, known = listOf(tok(1)))).of(LanRules.CENSUS_NOT_SET_UP).isEmpty())
    }

    @Test
    fun partialThenFullScanRaisesNothingForListedHosts() {
        // B03 follow-up 1: a partial scan misses the printer, the next full one sees it again; nothing is "new".
        val known = listOf(tok(1, 'u'), tok(2), tok(3))
        val full = network(tv, printer, known = known)
        val partial = network(tv, known = known, partial = listOf("mdns"))
        assertTrue(evaluate(partial, full).isEmpty())
        assertTrue(evaluate(full, partial).isEmpty())
    }

    @Test
    fun partialScanStillJudgesAndSaysSo() {
        val d = evaluate(network(tv, known = listOf(tok(3)), partial = listOf("mdns", "ports"))).of(LanRules.UNKNOWN_DEVICE).single()
        assertEquals(
            "A device that is not on your list for this network is here: Living Room TV (TV) at 192.168.1.23. " +
                "This scan was incomplete (mdns), so it may be a listed device seen only in part.",
            d.evidence,
        )
        // Only the stages that find devices matter: a slow port scan does not change who answered.
        assertFalse(evaluate(network(tv, known = listOf(tok(3)), partial = listOf("ports", "router"))).of(LanRules.UNKNOWN_DEVICE).single().evidence.contains("incomplete"))
        assertTrue(evaluate(network(tv, known = listOf(tok(3)), partial = listOf("ssdp", "network"))).of(LanRules.UNKNOWN_DEVICE).single().evidence.contains("(ssdp, network)"))
    }

    @Test
    fun unconfirmedOrUnavailableRaisesNothing() {
        val hosts = network(tv, printer, census = DeviceCensus.STATE_UNSET)
        // Unavailable: nothing was judged, whatever the hosts look like.
        val unavailable = network(tv, printer, census = DeviceCensus.STATE_UNAVAILABLE)
        assertTrue(evaluate(unavailable).none { it.kind == LanRules.UNKNOWN_DEVICE || it.kind == LanRules.CENSUS_NOT_SET_UP })
        // Gate refused: only the summary of the refusal, no census.
        val refused = tv + summary(gate = LanKeys.GATE_UNCONFIRMED, census = DeviceCensus.STATE_SET, known = listOf(tok(77)))
        assertTrue(evaluate(refused).isEmpty())
        // A scan from before the census has neither state: nothing.
        assertTrue(evaluate(tv + router() + summary(census = null)).isEmpty())
        // A state word nobody writes is not "set".
        assertTrue(evaluate(tv + summary(census = "maybe", known = listOf(tok(77)))).isEmpty())
        assertFalse(evaluate(hosts).isEmpty())
    }

    @Test
    fun newHostIsNoLongerARule() {
        val base = host("192.168.1.20", name = "DiskStation", kind = HostKind.NAS) + router() + summary(hosts = 1)
        val withNew = host("192.168.1.20", name = "DiskStation", kind = HostKind.NAS) + host("192.168.1.77", name = "shellyplug", kind = HostKind.IOT) +
            router() + summary(hosts = 2)
        assertTrue(evaluate(withNew, base).of(LanRules.NEW_HOST).isEmpty())
        assertEquals(6, LanRules.all.size)
        // The kind and its guide stay so a finding an older build stored still gets its actions.
        assertEquals("NEW_HOST", LanRules.NEW_HOST)
        assertEquals(LanGuides.fix(LanRules.UNKNOWN_DEVICE), LanGuides.fix(LanRules.NEW_HOST))
        assertTrue(LanGuides.fix(LanRules.NEW_HOST).contains("client list"))
        assertTrue(LanGuides.fix(LanRules.CENSUS_NOT_SET_UP).startsWith("Check each device in the list. If you know them all, tap These are all mine;"))
    }

    @Test
    fun hostsWithoutRiskyPortsProduceNoDrafts() {
        val clean = host("192.168.1.2", kind = HostKind.PHONE, open = listOf(8080)) + host("192.168.1.3", kind = HostKind.TV, open = listOf(8008, 8443, 9000))
        assertTrue(evaluate(clean + router() + summary()).isEmpty())
    }
}
