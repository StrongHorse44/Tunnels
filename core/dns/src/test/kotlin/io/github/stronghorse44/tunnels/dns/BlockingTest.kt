package io.github.stronghorse44.tunnels.dns

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingTest {
    private val on = BlockPolicy(enabled = true)

    @Test
    fun blocksTrackerLookupsOfTheChosenKindsOnly() {
        assertEquals("doubleclick.net", on.blocks("com.app", "ad.doubleclick.net")?.domain)
        assertEquals("firebaseinstallations.googleapis.com", on.blocks("com.app", "firebaseinstallations.googleapis.com")?.domain)
        assertNull("ordinary domains go out", on.blocks("com.app", "www.example.com"))
        assertNull("other googleapis hosts go out", on.blocks("com.app", "www.googleapis.com"))
        assertNull("social is off by default", on.blocks("com.app", "graph.facebook.com"))
        assertNull("push is off by default", on.blocks("com.app", "onesignal.com"))
        assertEquals("graph.facebook.com", on.copy(kinds = on.kinds + TrackerKind.SOCIAL).blocks("com.app", "graph.facebook.com")?.domain)
    }

    @Test
    fun offOrExemptMeansNothingIsBlocked() {
        assertNull(BlockPolicy().blocks("com.app", "ad.doubleclick.net"))
        assertNull(on.copy(exempt = setOf("com.app")).blocks("com.app", "ad.doubleclick.net"))
        assertTrue(on.copy(exempt = setOf("com.app")).blocks("com.other", "ad.doubleclick.net") != null)
    }

    @Test
    fun policyRoundTripsAndFallsBackSafely() {
        val p = BlockPolicy(enabled = true, kinds = setOf(TrackerKind.ADS, TrackerKind.PUSH), exempt = setOf("org.b", "com.a"))
        assertEquals("on=true;kinds=ADS,PUSH;exempt=com.a,org.b", p.encode())
        assertEquals(p, BlockPolicy.decode(p.encode()))
        assertEquals(BlockPolicy(), BlockPolicy.decode(null))
        val odd = BlockPolicy.decode("on=yes;kinds=ADS,NOPE")
        assertFalse(odd.enabled)
        assertEquals(setOf(TrackerKind.ADS), odd.kinds)
        assertEquals(BlockPolicy.DEFAULT_KINDS, BlockPolicy.decode("on=true").kinds)
    }

    @Test
    fun nxdomainAnswersTheQuestionAndNothingElse() {
        val query = DnsMessage.query(0xBEEF, "ad.doubleclick.net", DnsMessage.TYPE_AAAA)
        val reply = DnsMessage.nxdomain(query)!!
        val m = DnsMessage.parse(reply)
        assertEquals(0xBEEF, m.id)
        assertTrue(m.isResponse)
        assertEquals(0, m.opcode)
        assertTrue(m.recursionDesired)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, m.rcode)
        assertEquals("NXDOMAIN", m.rcodeName)
        assertEquals("ad.doubleclick.net", m.queryName)
        assertEquals(DnsMessage.TYPE_AAAA, m.questions.single().type)
        assertEquals(0, m.answerCount + m.authorityCount + m.additionalCount)
        assertEquals(0x80, reply[3].toInt() and 0x80) // recursion available
        // The question section is the query's, byte for byte.
        assertArrayEquals(query.copyOfRange(DnsMessage.HEADER_LENGTH, query.size), reply.copyOfRange(DnsMessage.HEADER_LENGTH, reply.size))
    }

    @Test
    fun nxdomainDropsTheQuerysExtraRecords() {
        val base = DnsMessage.query(7, "app-measurement.com")
        // An EDNS OPT record in the additional section, as Android's resolver sends.
        val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        val withOpt = base + opt
        withOpt[11] = 1 // ARCOUNT = 1
        val reply = DnsMessage.nxdomain(withOpt)!!
        assertEquals(base.size, reply.size)
        assertEquals(0, DnsMessage.parse(reply).additionalCount)
    }

    @Test
    fun nxdomainOnlyAnswersQueries() {
        val query = DnsMessage.query(1, "example.com")
        val response = query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsMessage.nxdomain(response))
        assertNull(DnsMessage.nxdomain(byteArrayOf(1, 2, 3)))
        val noQuestion = query.copyOfRange(0, DnsMessage.HEADER_LENGTH).also { it[5] = 0 }
        assertNull(DnsMessage.nxdomain(noQuestion))
        val noRecursion = query.copyOf().also { it[2] = 0 }
        assertFalse(DnsMessage.parse(DnsMessage.nxdomain(noRecursion)!!).recursionDesired)
    }

    @Test
    fun blockedLookupsAreCountedPerAppAndInTotal() {
        val counter = SessionCounter("tok")
        counter.query("com.a", "ad.doubleclick.net")
        counter.blocked("com.a")
        counter.query("com.a", "www.example.com")
        assertEquals(1, counter.totals.blocked)
        val rows = counter.flush(1).toMap()
        val a = SessionRecord.parse(rows["com.a"]!!)!!
        assertEquals(1, a.blocked)
        assertTrue(rows["com.a"]!!.endsWith(";blocked=1"))
        // A record without blocks reads and writes exactly as before blocking existed.
        val plain = SessionRecord("x", 1, 1, 0, emptyList(), 0, emptyList())
        assertFalse(plain.encode().contains("blocked"))
        assertEquals(0, SessionRecord.parse(plain.encode())!!.blocked)
    }

    @Test
    fun aggregationAndTheTrackerFindingCarryBlocks() {
        val rows = listOf(
            "com.a" to SessionRecord("s1", 3, 10, 0, listOf("a.com" to 9), 1, listOf("doubleclick.net" to 4), blocked = 4).encode(),
            "com.a" to SessionRecord("s2", 2, 5, 0, listOf("a.com" to 5), 1, listOf("doubleclick.net" to 2), blocked = 2).encode(),
        )
        val agg = DnsAggregator.aggregate(rows)
        assertEquals(6, agg.perApp.getValue("com.a").blocked)
        val obs: List<Observation> = DnsAggregator.observations(agg, sessionActive = false, otherVpnActive = false)
        assertEquals("6", obs.single { it.key == TrafficKeys.BLOCKED30 }.value)
        val ctx = RuleContext(TrafficKeys.TUNNEL_ID, obs, DiffEngine.diff(emptyList(), obs), isFirstScan = true)
        val evidence = TrafficRules.trackerDomains.evaluate(ctx).single().evidence
        assertEquals(
            "Contacted 1 tracking domain in the last 30 days: doubleclick.net. Sessions blocked 6 lookups to them; outside a session they go out.",
            evidence,
        )
    }
}
