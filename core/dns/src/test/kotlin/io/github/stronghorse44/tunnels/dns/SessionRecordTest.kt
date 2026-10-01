package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionRecordTest {
    @Test
    fun recordRoundTrip() {
        val r = SessionRecord("ab12cd", 14, 231, 2, listOf("example.com" to 120, "cdn.example.net" to 40), 2, listOf("app-measurement.com" to 12))
        val s = r.encode()
        assertEquals("s=ab12cd;domains=14;queries=231;enc=2;top=example.com:120,cdn.example.net:40;trackers=2;trackerTop=app-measurement.com:12", s)
        assertEquals(r, SessionRecord.parse(s))
    }

    @Test
    fun recordParsingIsTolerant() {
        assertNull(SessionRecord.parse(""))
        assertNull(SessionRecord.parse("garbage"))
        assertNull(SessionRecord.parse("s=x;queries=abc;domains=1"))
        assertNull(SessionRecord.parse(SessionMarker("x", 1, 2, 3).encode()))
        val minimal = SessionRecord.parse("s=x;domains=3;queries=9")!!
        assertEquals(0, minimal.encrypted)
        assertEquals(emptyList<Pair<String, Int>>(), minimal.top)
        // Count-less items default to 1, IPv6 literals keep their colons.
        val odd = SessionRecord.parse("s=x;domains=3;queries=9;top=a.com,fd00::1:7,b.net:2")!!
        assertEquals(listOf("a.com" to 1, "fd00::1" to 7, "b.net" to 2), odd.top)
        // Over-long lists are capped.
        val long = SessionRecord.parse("s=x;domains=3;queries=9;top=" + (1..9).joinToString(",") { "d$it.com:$it" })!!
        assertEquals(SessionRecord.TOP_MAX, long.top.size)
    }

    @Test
    fun markerRoundTrip() {
        val m = SessionMarker("ab12cd", 3, 400, 12)
        assertEquals("s=ab12cd;apps=3;queries=400;minutes=12", m.encode())
        assertEquals(m, SessionMarker.parse(m.encode()))
        assertNull(SessionMarker.parse("s=ab12cd;queries=400"))
    }

    @Test
    fun separatorsInValuesAreNeutralised() {
        assertEquals("a=b_c;d=e_f", Fields.encode(listOf("a" to "b;c", "d" to "e=f")))
        assertEquals("x_y:1", Fields.encodeCounts(listOf("x,y" to 1)))
        assertEquals(mapOf("a" to "1", "b" to "2"), Fields.parse("a=1;;b=2;=3;novalue"))
    }

    @Test
    fun counterProducesBoundedRows() {
        val counter = SessionCounter("tok", maxApps = 2, maxDomainsPerApp = 3)
        repeat(3) { counter.query("com.a", "www.example.com") }
        counter.query("com.a", "cdn.example.net")
        counter.query("com.a", "app-measurement.com")
        counter.query("com.a", "x.other.org") // 4th distinct domain: overflow
        counter.query("com.a", "y.other.org") // same registrable: still overflow
        counter.query("com.b", "graph.facebook.com")
        counter.query("com.c", "ads.example.com") // 3rd app: folded into "other"
        counter.encrypted("com.b")
        counter.encrypted("com.d") // also "other"

        val totals = counter.totals
        assertEquals(9, totals.queries)
        assertEquals(3, totals.apps) // com.a, com.b, other
        assertEquals(2, totals.encrypted)
        assertEquals(2, totals.trackers)
        assertEquals(5, totals.domains) // example.com, example.net, app-measurement.com, other.org, facebook.com

        val rows = counter.flush(minutes = 5).toMap()
        assertEquals(setOf("com.a", "com.b", TrafficKeys.OTHER_SUBJECT, TrafficKeys.SUMMARY), rows.keys)
        val a = SessionRecord.parse(rows["com.a"]!!)!!
        assertEquals("tok", a.session)
        assertEquals(7, a.queries)
        assertEquals(5, a.domains) // 3 counted + 2 overflow increments
        assertEquals(listOf("example.com" to 3, "app-measurement.com" to 1, "example.net" to 1), a.top)
        assertEquals(1, a.trackers)
        assertEquals(listOf("app-measurement.com" to 1), a.trackerTop)
        val b = SessionRecord.parse(rows["com.b"]!!)!!
        assertEquals(1, b.queries)
        assertEquals(1, b.encrypted)
        assertEquals(listOf("graph.facebook.com" to 1), b.trackerTop)
        val other = SessionRecord.parse(rows[TrafficKeys.OTHER_SUBJECT]!!)!!
        assertEquals(1, other.queries)
        assertEquals(1, other.encrypted)
        val marker = SessionMarker.parse(rows[TrafficKeys.SUMMARY]!!)!!
        assertEquals(SessionMarker("tok", 3, 9, 5), marker)

        // The interval resets; an idle flush writes nothing unless forced.
        assertEquals(emptyList<Pair<String, String>>(), counter.flush(10))
        val forced = counter.flush(10, force = true)
        assertEquals(1, forced.size)
        assertEquals(SessionMarker("tok", 0, 0, 10), SessionMarker.parse(forced.single().second))
        // Totals are cumulative across flushes.
        assertEquals(9, counter.totals.queries)
    }

    @Test
    fun counterIgnoresEmptyNamesAndUsesTheTrackerMatcher() {
        val counter = SessionCounter("tok", matchTracker = { if (it == "evil.example") TrackerDomain("evil.example", TrackerKind.ADS, "Evil") else null })
        counter.query("com.a", "")
        counter.query("com.a", ".")
        assertEquals(0, counter.totals.queries)
        counter.query("com.a", "evil.example")
        counter.query("com.a", "good.example")
        assertEquals(1, counter.totals.trackers)
        val a = SessionRecord.parse(counter.flush(1).toMap()["com.a"]!!)!!
        assertEquals(listOf("evil.example" to 1), a.trackerTop)
    }

    @Test
    fun tokensAreShortAndRandom() {
        val t = SessionCounter.newToken(Random(1))
        assertEquals(6, t.length)
        assertTrue(t.all { it in 'a'..'z' || it in '0'..'9' })
        assertTrue(SessionCounter.newToken(Random(1)) != SessionCounter.newToken(Random(2)))
    }

    @Test
    fun aggregatorMergesSessionsPerApp() {
        val rows = listOf(
            "com.a" to SessionRecord("s1", 10, 100, 0, listOf("a.com" to 60, "b.com" to 30), 1, listOf("sentry.io" to 5)).encode(),
            "com.a" to SessionRecord("s2", 25, 50, 1, listOf("b.com" to 20, "c.com" to 10), 2, listOf("sentry.io" to 2, "adjust.com" to 1)).encode(),
            "com.b" to SessionRecord("s2", 1, 3, 0, listOf("z.org" to 3), 0, emptyList()).encode(),
            TrafficKeys.SUMMARY to SessionMarker("s1", 1, 100, 60).encode(),
            TrafficKeys.SUMMARY to SessionMarker("s2", 2, 53, 5).encode(),
            TrafficKeys.SUMMARY to SessionMarker("s3", 0, 0, 1).encode(),
            "com.junk" to "not a record",
        )
        val agg = DnsAggregator.aggregate(rows)
        assertEquals(3, agg.sessions)
        assertEquals(setOf("com.a", "com.b"), agg.perApp.keys)
        val a = agg.perApp["com.a"]!!
        assertEquals(150, a.queries)
        assertEquals(1, a.encrypted)
        assertEquals(25, a.domains)
        assertEquals(listOf("a.com" to 60, "b.com" to 50, "c.com" to 10), a.top)
        assertEquals(2, a.trackerDomains)
        assertEquals(listOf("sentry.io" to 7, "adjust.com" to 1), a.trackerTop)
        // Union of top lists can exceed the largest single-interval count.
        val merged = DnsAggregator.merge(
            listOf(
                SessionRecord("s", 1, 1, 0, listOf("a.com" to 1), 1, listOf("x.io" to 1)),
                SessionRecord("s", 1, 1, 0, listOf("b.com" to 1), 1, listOf("y.io" to 1)),
            ),
        )
        assertEquals(2, merged.domains)
        assertEquals(2, merged.trackerDomains)

        val obs = DnsAggregator.observations(agg, sessionActive = false, otherVpnActive = true)
        val byA = obs.filter { it.subject == "com.a" }.associate { it.key to it.value }
        assertEquals("25", byA[TrafficKeys.DOMAINS30])
        assertEquals("150", byA[TrafficKeys.QUERIES30])
        assertEquals("a.com,b.com,c.com", byA[TrafficKeys.TOP])
        assertEquals("2", byA[TrafficKeys.TRACKER_DOMAINS30])
        assertEquals("sentry.io,adjust.com", byA[TrafficKeys.TRACKER_TOP])
        assertEquals("1", byA[TrafficKeys.ENCRYPTED30])
        val byB = obs.filter { it.subject == "com.b" }.associate { it.key to it.value }
        assertNull(byB[TrafficKeys.TRACKER_TOP])
        assertNull(byB[TrafficKeys.ENCRYPTED30])
        val summary = obs.filter { it.subject == TrafficKeys.SUMMARY }.associate { it.key to it.value }
        assertEquals(mapOf(TrafficKeys.SESSIONS_COUNT30 to "3", TrafficKeys.SESSION_ACTIVE to "false", TrafficKeys.VPN_OTHER_ACTIVE to "true"), summary)
        assertTrue(obs.all { it.tunnelId == TrafficKeys.TUNNEL_ID })

        val empty = DnsAggregator.observations(DnsAggregate.EMPTY, sessionActive = true, otherVpnActive = false)
        assertEquals(3, empty.size)
        assertEquals("0", TrafficKeys.value(empty, TrafficKeys.SESSIONS_COUNT30))
        assertEquals("true", TrafficKeys.value(empty, TrafficKeys.SESSION_ACTIVE))
    }

    @Test
    fun subjectKinds() {
        assertTrue(TrafficKeys.isPackageSubject("com.example.app"))
        assertTrue(!TrafficKeys.isPackageSubject("uid:1000"))
        assertTrue(!TrafficKeys.isPackageSubject("unknown"))
        assertTrue(!TrafficKeys.isPackageSubject("other"))
        assertTrue(!TrafficKeys.isPackageSubject("summary"))
        assertEquals(listOf("a", "b"), TrafficKeys.list(" a, b,, "))
        assertEquals(emptyList<String>(), TrafficKeys.list(null))
    }
}
