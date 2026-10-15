package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookCloserTest {
    @Test
    fun closerPortsAddTellingOnesWithoutChangingTheScan() {
        assertEquals(30, PortCatalog.ports.size)
        val extra = PortCatalog.closerOnly.map { it.port }
        assertTrue(listOf(62078, 7000, 8009, 1400, 5555, 34567, 37777, 6668).all { it in extra })
        assertTrue(extra.none { it in PortCatalog.ports })
        assertEquals(PortCatalog.closer.size, PortCatalog.closer.map { it.port }.toSet().size)
        assertTrue(5555 in PortCatalog.risky && 2323 in PortCatalog.risky)
        assertEquals("iPhone or iPad sync (port 62078)", PortCatalog.byPort(62078)!!.service)
    }

    @Test
    fun portsPointToKinds() {
        assertEquals(HostKind.PHONE, PortClues.kindOf(listOf(62078, 7000)))
        assertEquals(HostKind.TV, PortClues.kindOf(listOf(8008, 8009)))
        assertEquals(HostKind.CAMERA, PortClues.kindOf(listOf(554, 34567)))
        assertEquals(HostKind.SPEAKER, PortClues.kindOf(listOf(1400)))
        // Ports that point two ways decide nothing.
        assertNull(PortClues.kindOf(listOf(9100, 554)))
        assertNull(PortClues.kindOf(listOf(22)))
        assertEquals(listOf(554, 22), PortClues.of(listOf(22, 554)).map { it.port })
    }

    @Test
    fun webPagesSayWhatTheyAre() {
        assertEquals("Hikvision & co <IP Camera>", Greetings.httpTitle("<html><head><TITLE>\n  Hikvision &amp; co &lt;IP Camera&gt; </title></head>"))
        assertEquals("Login", Greetings.httpTitle("<title lang=\"en\">Log<b>in</b></title>".replace("<b>", "").replace("</b>", "")))
        assertNull(Greetings.httpTitle("<html><body>no title</body></html>"))
        assertNull(Greetings.httpTitle("<title>   </title>"))
        assertEquals("DS-2CD2143G2-I", Greetings.authRealm(mapOf("www-authenticate" to "Digest realm=\"DS-2CD2143G2-I\", nonce=\"x\"")))
        assertEquals("https://192.168.1.250/", Greetings.redirect(302, mapOf("location" to "https://192.168.1.250/")))
        assertNull(Greetings.redirect(200, mapOf("location" to "/x")))
        assertEquals(Greetings.MAX_TEXT, Greetings.clean("x".repeat(500))!!.length)
        assertEquals("a b", Greetings.clean("a\u0000\n\t b\u0007"))
    }

    @Test
    fun sshRtspAndBannersAreReadOut() {
        assertEquals("OpenSSH 8.9p1 (Ubuntu)", Greetings.sshSoftware("SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.1\r\n"))
        assertEquals("dropbear 2019.78", Greetings.sshSoftware("SSH-2.0-dropbear_2019.78"))
        assertNull(Greetings.sshSoftware("HTTP/1.0 400"))
        assertEquals("a small Linux device (router, camera, NAS or smart gadget)", Greetings.sshHint("dropbear 2019.78"))
        assertEquals("a Linux computer", Greetings.sshHint("OpenSSH 8.9p1 (Ubuntu)"))
        assertEquals("Hipcam RealServer/V1.0", Greetings.rtspServer("RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hipcam RealServer/V1.0\r\n\r\n"))
        assertNull(Greetings.rtspServer("HTTP/1.1 200 OK\r\nServer: x\r\n"))
        assertTrue(Greetings.rtspOptions().startsWith("OPTIONS * RTSP/1.0\r\n"))
        // Telnet negotiation (IAC DO ECHO, IAC WILL SGA) before the banner is skipped.
        val telnet = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x01, 0xFF.toByte(), 0xFB.toByte(), 0x03) + "\r\nBusyBox v1.24 login: ".toByteArray()
        assertEquals("BusyBox v1.24 login:", Greetings.bannerLine(telnet))
        assertEquals("220 FRITZ!Box FTP server ready.", Greetings.bannerLine("220 FRITZ!Box FTP server ready.\r\n230 more".toByteArray()))
        assertNull(Greetings.bannerLine(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x01)))
    }

    @Test
    fun certificatesGiveTheirNames() {
        val c = CertFacts.of("CN=DS-2CD2143G2,O=Hikvision,C=CN", "CN=DS-2CD2143G2,O=Hikvision,C=CN", listOf("192.168.1.64"))
        assertEquals("DS-2CD2143G2", c.commonName)
        assertEquals("Hikvision", c.organization)
        assertTrue(c.selfSigned)
        assertEquals("DS-2CD2143G2 (Hikvision), self-signed", c.line)
        val signed = CertFacts.of("CN=nas.example\\, inc,O=Synology Inc.", "CN=R3,O=Let's Encrypt", emptyList())
        assertEquals("nas.example, inc", signed.commonName)
        assertFalse(signed.selfSigned)
        assertEquals("nas.example, inc (Synology Inc.), signed by R3", signed.line)
    }

    @Test
    fun netbiosAsksForTheWildcardAndReadsNamesOnly() {
        val q = NetBios.query(0x1234)
        assertEquals(50, q.size)
        assertEquals(0x12, q[0].toInt())
        assertEquals(0x20, q[12].toInt())
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII))
        assertEquals(0x21, q[47].toInt())

        fun name(n: String, suffix: Int, flags: Int): ByteArray = n.padEnd(15, ' ').toByteArray(Charsets.US_ASCII) + byteArrayOf(suffix.toByte(), (flags shr 8).toByte(), flags.toByte())
        val names = name("LIVINGROOM-PC", 0x00, 0x0400) + name("WORKGROUP", 0x00, 0x8400) + name("LIVINGROOM-PC", 0x20, 0x0400)
        val rdata = byteArrayOf(3) + names + ByteArray(46) // statistics (hardware address and counters), never read
        val reply = byteArrayOf(0x12, 0x34, 0x84.toByte(), 0x00, 0, 0, 0, 1, 0, 0, 0, 0) +
            q.copyOfRange(12, 46) + byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, (rdata.size shr 8).toByte(), rdata.size.toByte()) + rdata
        val parsed = NetBios.parse(0x1234, reply)!!
        assertEquals("LIVINGROOM-PC", NetBios.computerName(parsed))
        assertEquals("WORKGROUP", NetBios.workgroup(parsed))
        assertNull(NetBios.parse(0x9999, reply))
        assertNull(NetBios.parse(0x1234, reply.copyOf(20)))
    }

    @Test
    fun pingsAreWellFormedAndTtlsHintAtTheSystem() {
        val p = IcmpEcho.request(0, 7)
        assertEquals(8, p[0].toInt())
        assertEquals(0, IcmpEcho.checksum(p))
        val reply = p.copyOf().also { it[0] = 0 }
        assertTrue(IcmpEcho.isReply(reply, reply.size, 7))
        assertFalse(IcmpEcho.isReply(reply, reply.size, 8))
        assertFalse(IcmpEcho.isReply(p, p.size, 7))
        assertEquals(OsHint.UNIX_LIKE, OsHint.fromTtl(64))
        assertEquals(OsHint.UNIX_LIKE, OsHint.fromTtl(63))
        assertEquals(OsHint.WINDOWS, OsHint.fromTtl(128))
        assertEquals(OsHint.NETWORK_GEAR, OsHint.fromTtl(255))
        assertNull(OsHint.fromTtl(0))
    }

    @Test
    fun aLookBecomesAKeptSummaryAndPlacesTheDevice() {
        val camera = LookCloser.Findings(
            open = listOf(80, 554), refused = listOf(22), httpTitle = "Web Viewer", authRealm = "DS-2CD2143G2",
            cert = CertFacts.of("CN=DS-2CD2143G2,O=Hikvision", "CN=DS-2CD2143G2,O=Hikvision", emptyList()), ttl = 64,
        )
        assertEquals(HostKind.CAMERA, camera.kind)
        assertEquals("Hikvision", camera.vendor)
        val kept = camera.summary(1_000L)
        assertEquals("Web Viewer", kept.says)
        assertEquals(listOf(80, 554), kept.openPorts)
        assertEquals(OsHint.UNIX_LIKE, kept.os)
        assertEquals(kept, LookCloser.Summary.parse(kept.encode()))

        val phone = LookCloser.Findings(open = listOf(62078), mdnsName = "Anas-iPhone", ttl = 64)
        assertEquals(HostKind.PHONE, phone.kind)
        assertEquals(listOf("Anas-iPhone"), phone.names)
        val windows = LookCloser.Findings(netbiosName = "LIVINGROOM-PC", workgroup = "WORKGROUP", ttl = 128)
        assertEquals(HostKind.COMPUTER, windows.kind)
        assertEquals(HostKind.UNKNOWN, LookCloser.Findings().kind)

        // Names with the row's separators survive the round trip.
        val odd = LookCloser.Summary(5L, HostKind.TV, "Sony", "Bravia; 4K=yes, 65%", listOf(8008), listOf("a,b", "c;d"), null)
        assertEquals(odd, LookCloser.Summary.parse(odd.encode()))
        assertNull(LookCloser.Summary.parse("kind=tv"))
        assertEquals("tag1|192.168.1.5", LookCloser.subject("tag1", "192.168.1.5"))
        assertEquals("192.168.1.5", LookCloser.ipOf("tag1|192.168.1.5", "tag1"))
        assertNull(LookCloser.ipOf("tag2|192.168.1.5", "tag1"))
    }

    @Test
    fun aDeviceThatRefusesEveryDoorQuietlyIsAQuietDeviceOfNoKind() {
        val all = PortCatalog.closer.map { it.port }
        // A device on the home network that refused every door, gave no name, pinged with TTL 64 and announced nothing.
        val quiet = LookCloser.Findings(refused = all, ttl = 64)
        assertTrue(quiet.quiet)
        // A guess on the screen only: the list keeps it under "Not identified".
        assertEquals(HostKind.UNKNOWN, quiet.kind)
        val kept = quiet.summary(1_000L)
        assertTrue(kept.quiet)
        assertEquals(kept, LookCloser.Summary.parse(kept.encode()))
        assertFalse(LookCloser.Summary.parse("at=1;kind=unknown;ports=none")!!.quiet)
        // A few doors lost to timeouts still count; most lost does not.
        assertTrue(LookCloser.Findings(refused = all.drop(4), ttl = 64).quiet)
        assertFalse(LookCloser.Findings(refused = all.take(20), ttl = 64).quiet)
        // Windows pings with 128; a name, an announcement, an open door or no ping at all rule it out.
        assertFalse(LookCloser.Findings(refused = all, ttl = 128).quiet)
        assertFalse(LookCloser.Findings(refused = all, ttl = 64, mdnsName = "kitchen-hub").quiet)
        assertFalse(LookCloser.Findings(refused = all, ttl = 64, known = listOf("_googlecast._tcp")).quiet)
        assertFalse(LookCloser.Findings(open = listOf(80), refused = all - 80, ttl = 64).quiet)
        assertFalse(LookCloser.Findings(refused = all).quiet)
        assertTrue(LanGuides.QUIET_WHY.contains("e-readers") && LanGuides.QUIET_GUESS.contains("phone"))
        // A Chromebook running Linux apps is one such device: ChromeOS answers exactly this way.
        assertTrue(LanGuides.QUIET_GUESS.contains("Chromebook") && LanGuides.QUIET_WHY.contains("forward their ports"))
    }

    @Test
    fun theScreenUsesALookToPlaceAndNameAnUnknownDevice() {
        val kept = LookCloser.Summary(1L, HostKind.CAMERA, "Hikvision", "Web Viewer", listOf(80, 554), listOf("garage-cam"), OsHint.UNIX_LIKE)
        fun o(subject: String, key: String, value: String) = Observation(LanKeys.TUNNEL_ID, subject, key, value)
        val summary = LanSummary.from(
            listOf(
                o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED),
                o("192.168.1.250", LanKeys.HOST_KIND, HostKind.UNKNOWN.label),
                o("192.168.1.250", LanKeys.HOST_FOUND_BY, LanKeys.FOUND_BY_SWEEP),
                o("192.168.1.250", LanKeys.HOST_SWEEP_ANSWER, LanSweep.answer(80, open = false)),
                o("192.168.1.250", LanKeys.HOST_CLOSER, kept.encode()),
                o("192.168.1.20", LanKeys.HOST_KIND, HostKind.TV.label),
                o("192.168.1.20", LanKeys.HOST_CLOSER, kept.encode()),
            ),
        )
        val unknown = summary.hosts.first { it.ip == "192.168.1.250" }
        assertEquals(HostKind.CAMERA, unknown.kind)
        assertEquals("garage-cam", unknown.title)
        assertEquals("80/refused", unknown.sweepAnswer)
        assertEquals(
            "Found when port 80, Web page (HTTP) refused a connection: a device is there, without that service.",
            LanSweep.describeAnswer(unknown.sweepAnswer),
        )
        // What the scan itself saw is never overridden.
        assertEquals(HostKind.TV, summary.hosts.first { it.ip == "192.168.1.20" }.kind)
        assertNull(LanSweep.describeAnswer("nonsense"))
    }
}
