package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSightingsTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L

    private fun key(n: Int) = "%016x".format(n)

    /** A scan [minutesAgo] before [now] at [place] seeing access points [aps] (numbers become keys and names). */
    private fun scan(minutesAgo: Long, place: Long?, vararg aps: Int) =
        WifiScanRecord("s$minutesAgo", now - minutesAgo * min, place, aps.map { ApSeen(key(it), "net$it") })

    @Test
    fun aScanRowSurvivesAnyName() {
        val names = listOf("Home", "a,b~c;d=e%f", "\u0001ctl", "emoji 📶", "", WifiHeuristics.HIDDEN)
        val r = WifiScanRecord("monitor-1", 1234L, 7L, names.mapIndexed { i, n -> ApSeen(key(i), n) })
        assertEquals(r, WifiScanRecord.parse("monitor-1", r.encode()))
        assertTrue(r.encode(), r.encode().count { it == ';' } == 2)
        val noPlace = r.copy(place = null)
        assertEquals(noPlace, WifiScanRecord.parse("monitor-1", noPlace.encode()))
        assertNull(WifiScanRecord.parse("x", "place=1"))
        // A malformed key or escape drops that entry only.
        val parsed = WifiScanRecord.parse("x", "at=5;place=;aps=NOTHEX~a,${key(1)}~ok,${key(2)}~bad%zz")
        assertEquals(listOf(ApSeen(key(1), "ok")), parsed!!.aps)
    }

    @Test
    fun aMoveCountsOnlyWhenTheSurroundingsChanged() {
        // A position fix that wandered at home: place 10 and 11 share nearly every access point.
        val jitter = listOf(scan(100, 10, 1, 2, 3, 4, 5), scan(90, 11, 1, 2, 3, 4, 6))
        assertEquals(emptySet<Long>(), WifiFollowing.realMoves(jitter))
        // A real move: home (1..5 and the hotspot 9) then a road where only the hotspot is heard.
        val trip = listOf(scan(100, 10, 1, 2, 3, 4, 5, 9), scan(60, 11, 9))
        assertEquals(setOf(10L), WifiFollowing.realMoves(trip))
        // A place without Wi-Fi data, or a scan with no fix, never makes a move.
        assertEquals(emptySet<Long>(), WifiFollowing.realMoves(listOf(scan(10, 10, 1), scan(5, 11), scan(1, null, 1))))
    }

    @Test
    fun aggregateCountsScansPlacesMovesAndTheLatestName() {
        val records = listOf(
            scan(200, 10, 1, 2, 3, 9),
            scan(150, 10, 1, 2, 3, 9),
            WifiScanRecord("s100", now - 100 * min, 11, listOf(ApSeen(key(9), "renamed"))),
            scan(50, null, 1),
        )
        val byKey = WifiFollowing.aggregate(records).associateBy { it.key }
        val hotspot = byKey.getValue(key(9))
        assertEquals(3, hotspot.sessions)
        assertEquals(2, hotspot.places)
        assertEquals(1, hotspot.moves)
        assertEquals("renamed", hotspot.name)
        assertEquals(2, hotspot.names)
        assertEquals(100L, hotspot.spanMinutes)
        val router = byKey.getValue(key(1))
        assertEquals(3, router.sessions)
        assertEquals(0, router.moves)
        assertEquals("s50", router.lastSession)
    }

    @Test
    fun levelsFollowTheThreshold() {
        fun ap(sessions: Int, span: Long, moves: Int, lastAgoMin: Long = 1) =
            AccessPointSighting(key(1), "n", 1, now - (span + lastAgoMin) * min, now - lastAgoMin * min, sessions, 1, moves, "s")
        assertEquals(WifiFollowing.Level.REPEATED, WifiFollowing.level(ap(5, 30, 0), 5, now))
        assertEquals(WifiFollowing.Level.FOLLOWING, WifiFollowing.level(ap(5, 30, 1), 5, now))
        assertEquals(WifiFollowing.Level.NONE, WifiFollowing.level(ap(4, 600, 1), 5, now))
        assertEquals(WifiFollowing.Level.NONE, WifiFollowing.level(ap(9, 29, 0), 5, now))
        assertEquals(WifiFollowing.Level.REPEATED, WifiFollowing.level(ap(3, 40, 0), 3, now))
        // Not seen for over a week: history, no finding.
        assertEquals(WifiFollowing.Level.NONE, WifiFollowing.level(ap(9, 60, 1, lastAgoMin = 8 * 24 * 60), 5, now))
    }

    @Test
    fun theThresholdSettingIsClamped() {
        assertEquals(5, WifiFollowing.threshold(null))
        assertEquals(5, WifiFollowing.threshold("nonsense"))
        assertEquals(2, WifiFollowing.threshold("1"))
        assertEquals(50, WifiFollowing.threshold("500"))
        assertEquals(8, WifiFollowing.threshold(" 8 "))
    }

    @Test
    fun prefixesClearTheLocallyAdministeredBit() {
        assertEquals("00:11:22:33:44", ManyNames.prefixOf("02:11:22:33:44:55"))
        assertEquals("00:11:22:33:44", ManyNames.prefixOf("00:11:22:33:44:66"))
        assertNull(ManyNames.prefixOf("00:11:22:33:44"))
        assertNull(ManyNames.prefixOf("zz:11:22:33:44:55"))
    }

    private fun net(ssid: String, bssid: String, open: Boolean) = WifiNetwork(ssid, bssid, if (open) "[ESS]" else "[WPA2-PSK-CCMP][ESS]", 2437)

    @Test
    fun manyOpenNamesFromOneDeviceWarnsAndAnOfficeDoesNot() {
        val pineapple = listOf("attwifi", "Starbucks", "xfinitywifi", "Airport Free", "Hotel Guest", "Home").mapIndexed { i, n ->
            net(n, "02:aa:bb:cc:dd:%02x".format(i), open = i < 5)
        }
        val office = listOf("Corp", "Corp-IoT", "Guest").mapIndexed { i, n -> net(n, "00:10:20:30:40:%02x".format(i), open = n == "Guest") }
        val clusters = ManyNames.clusters(pineapple + office) { "k:$it" }.associateBy { it.key }
        val p = clusters.getValue("k:00:aa:bb:cc:dd")
        assertEquals(6, p.names.size)
        assertEquals(5, p.open)
        assertEquals(ManyNames.Level.WARN, ManyNames.level(p))
        assertEquals(ManyNames.Level.NONE, ManyNames.level(clusters.getValue("k:00:10:20:30:40")))
        val bigOffice = NameCluster("k", (1..8).map { "n$it" }, open = 1, bssids = 8)
        assertEquals(ManyNames.Level.NOTICE, ManyNames.level(bigOffice))
    }

    @Test
    fun hiddenNamesAndSingleNamesMakeNoCluster() {
        val nets = listOf(net("", "00:00:00:00:01:01", true), net("A", "00:00:00:00:01:02", true), net("B", "00:00:00:00:02:01", false))
        assertTrue(ManyNames.clusters(nets) { it }.isEmpty())
    }

    private fun findings(aps: List<AccessPointSighting>, threshold: Int = 5, muted: Set<String> = emptySet(), clusters: List<NameCluster> = emptyList()) =
        (SurroundingsKeys.apObservations(aps, threshold, now, muted) + SurroundingsKeys.clusterObservations(clusters, muted)).let { obs ->
            val ctx = RuleContext(SurroundingsKeys.TUNNEL_ID, obs, emptyList(), isFirstScan = true)
            SurroundingsRules.all.flatMap { it.evaluate(ctx) }
        }

    @Test
    fun everyAccessPointPastTheThresholdIsFlaggedAndAMoverWarns() {
        val neighbour = AccessPointSighting(key(1), "NETGEAR42", 1, now - 600 * min, now - 2 * min, 12, 1, 0, "s")
        val hotspot = AccessPointSighting(key(2), "iPhone (2)", 1, now - 90 * min, now - 1 * min, 6, 3, 2, "s")
        val passerBy = AccessPointSighting(key(3), "Cafe", 1, now - 10 * min, now - 10 * min, 1, 1, 0, "s")
        val f = findings(listOf(neighbour, hotspot, passerBy)).associateBy { it.subject }
        assertEquals(2, f.size)
        val n = f.getValue(SurroundingsKeys.apSubject(key(1)))
        assertEquals(SurroundingsRules.WIFI_AP_REPEATED, n.kind)
        assertEquals(Severity.NOTICE, n.severity)
        assertTrue(n.evidence, n.evidence.startsWith("Wi-Fi \"NETGEAR42\" was seen in 12 separate scans over 9 hours 58 minutes"))
        val h = f.getValue(SurroundingsKeys.apSubject(key(2)))
        assertEquals(SurroundingsRules.WIFI_AP_FOLLOWING, h.kind)
        assertEquals(Severity.WARN, h.severity)
        assertTrue(h.evidence, h.evidence.contains("on both sides of 2 moves"))
        // Muted: listed, no finding.
        assertTrue(findings(listOf(neighbour), muted = setOf(SurroundingsKeys.apSubject(key(1)))).isEmpty())
    }

    @Test
    fun theListIsCappedButNeverHidesAFlaggedOne() {
        val quiet = (100 until 100 + SurroundingsKeys.MAX_LISTED_APS + 10).map {
            AccessPointSighting(key(it), "n$it", 1, now - 5 * min, now - min, 1, 1, 0, "s")
        }
        val flagged = AccessPointSighting(key(1), "late", 1, now - 600 * min, now - min, 7, 1, 0, "s")
        val obs = SurroundingsKeys.apObservations(quiet + flagged, 5, now)
        val subjects = obs.map { it.subject }.filter { SurroundingsKeys.parseApSubject(it) != null }.toSet()
        assertTrue(SurroundingsKeys.apSubject(key(1)) in subjects)
        assertEquals(SurroundingsKeys.MAX_LISTED_APS, subjects.size)
        val summary = obs.filter { it.subject == SurroundingsKeys.WIFI_SUMMARY }.associate { it.key to it.value }
        assertEquals("71", summary[SurroundingsKeys.AP_TRACKED])
        assertEquals("1", summary[SurroundingsKeys.AP_REPEATED])
        assertEquals("11", summary[SurroundingsKeys.AP_UNLISTED])
        assertEquals("5", summary[SurroundingsKeys.AP_THRESHOLD])
    }

    @Test
    fun manyNamesFindings() {
        val warn = NameCluster(key(7), listOf("a", "b", "c", "d", "e", "f", "g"), open = 4, bssids = 7)
        val f = findings(emptyList(), clusters = listOf(warn)).single()
        assertEquals(SurroundingsRules.WIFI_MANY_NAMES, f.kind)
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.evidence, f.evidence.contains("7 network names, 4 of them open (a, b, c, d, e, f, …)"))
        assertTrue(findings(emptyList(), muted = setOf(SurroundingsKeys.deviceSubject(key(7))), clusters = listOf(warn)).isEmpty())
        assertTrue(findings(emptyList(), clusters = listOf(NameCluster(key(8), listOf("a", "b"), 2, 2))).isEmpty())
    }

    @Test
    fun aNetworkNamedLikeASubjectIsNotOne() {
        assertNull(SurroundingsKeys.parseApSubject("wifiap:not-a-key"))
        assertNull(SurroundingsKeys.parseDeviceSubject("wifiap:${key(1)}"))
        assertEquals(key(1), SurroundingsKeys.parseApSubject(SurroundingsKeys.apSubject(key(1))))
    }

    @Test
    fun theScreenReadsTheObservationsBack() {
        val neighbour = AccessPointSighting(key(1), "NETGEAR42", 1, now - 600 * min, now - 2 * min, 12, 1, 0, "s")
        val hotspot = AccessPointSighting(key(2), "iPhone (2)", 2, now - 90 * min, now - 1 * min, 6, 3, 1, "cur")
        val known = AccessPointSighting(key(3), "Home", 1, now - 900 * min, now - 1 * min, 40, 1, 0, "cur")
        val once = AccessPointSighting(key(4), "Cafe", 1, now - 10 * min, now - 10 * min, 1, 1, 0, "s")
        val obs = SurroundingsKeys.apObservations(listOf(once, known, neighbour, hotspot), 5, now, setOf(SurroundingsKeys.apSubject(key(3))), "cur") +
            SurroundingsKeys.clusterObservations(listOf(NameCluster(key(9), (1..6).map { "n$it" }, 3, 6)))
        val aps = WifiOverTime.accessPoints(obs)
        assertEquals(listOf("iPhone (2)", "NETGEAR42", "Cafe", "Home"), aps.map { it.name })
        val h = aps.first()
        assertEquals(WifiFollowing.Level.FOLLOWING, h.level)
        assertTrue(h.thisScan)
        assertEquals("6 scans over 1 hour 29 minutes · 3 places · 1 move · 2 names", h.counts)
        assertEquals("1 scan", aps[2].counts)
        assertTrue(aps.last().muted)
        assertEquals(listOf(SurroundingsKeys.apSubject(key(1))), WifiOverTime.muteAllCandidates(aps))
        assertEquals(WifiOverTime.Summary(tracked = 4, repeated = 1, following = 1, threshold = 5, unlisted = 0), WifiOverTime.summary(obs))
        val d = WifiOverTime.devices(obs).single()
        assertEquals(ManyNames.Level.WARN, d.level)
        assertEquals("n1, n2, n3, n4, n5, n6", d.sample)
        assertNull(WifiOverTime.summary(emptyList()))
    }
}
