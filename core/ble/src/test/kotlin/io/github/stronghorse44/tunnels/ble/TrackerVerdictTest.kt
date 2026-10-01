package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerVerdictTest {
    private val t0 = 1_700_000_000_000L

    private fun facts(
        type: TrackerType = TrackerType.APPLE_FINDMY,
        key: String = "deadbeef",
        state: TrackerState = TrackerState.UNKNOWN,
        scans: Int = 1,
        span: Long = 0,
        rssiLast: Int? = -58,
        thisScan: Boolean = true,
        muted: Boolean = false,
    ) = IdentityFacts(type, key, state, scans, scans, span, t0, t0 + span * 60_000, rssiLast, rssiLast, null, null, thisScan, muted)

    @Test
    fun factsReadBackFromObservations() {
        val agg = SightingAggregator.aggregate(
            listOf(
                SightingRecord("s1", t0, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.WITH_OWNER, -55, 3, battery = "full", kind = "airtag"),
                SightingRecord("s2", t0 + 20 * 60_000, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.WITH_OWNER, -72, 1, battery = "full", kind = "airtag"),
            ),
        )
        val obs = SurroundingsKeys.bleObservations(agg, 10, SurroundingsKeys.AVAILABLE_YES, t0 + 21 * 60_000, currentSession = "s2")
        val subject = SurroundingsKeys.trackerSubject(TrackerType.APPLE_FINDMY, "deadbeef")
        val f = IdentityFacts.from(TrackerType.APPLE_FINDMY, "deadbeef", obs.filter { it.subject == subject }.associate { it.key to it.value })
        assertEquals(TrackerState.WITH_OWNER, f.state)
        assertEquals(2, f.scans)
        assertEquals(4, f.sightings)
        assertEquals(20L, f.spanMinutes)
        assertEquals(t0, f.firstSeen)
        assertEquals(t0 + 20 * 60_000, f.lastSeen)
        assertEquals(-72, f.rssiLast)
        assertEquals(Proximity.MEDIUM, f.proximityLast)
        assertEquals(Proximity.NEAR, f.proximityAvg)
        assertEquals("full", f.battery)
        assertEquals("airtag", f.kind)
        assertTrue(f.seenThisScan)
        assertFalse(f.muted)
        // Missing facts degrade to zeros and nulls, never throw.
        val empty = IdentityFacts.from(TrackerType.TILE, "k", emptyMap())
        assertEquals(0, empty.scans)
        assertNull(empty.rssiLast)
        assertNull(empty.proximityLast)
        assertEquals(TrackerState.UNKNOWN, empty.state)
    }

    @Test
    fun progressTracksTheRealThresholds() {
        val p = FollowingProgress(1, 0)
        assertEquals(FollowingHeuristic.MIN_SESSIONS, p.scansNeeded)
        assertEquals(FollowingHeuristic.MIN_SPAN_MINUTES, p.minutesNeeded)
        assertEquals("1 of 3 scans · 0 of 30 min", p.label)
        assertEquals(1f / 3f, p.scanFraction, 1e-6f)
        assertEquals(0f, p.minuteFraction)
        assertFalse(p.reached)
        val done = FollowingProgress(7, 500)
        assertTrue(done.reached)
        assertEquals("3 of 3 scans · 30 of 30 min", done.label)
        assertEquals(1f, done.scanFraction)
        assertFalse(FollowingProgress(3, 29).reached)
        assertFalse(FollowingProgress(2, 300).reached)
    }

    @Test
    fun verdictLineForTheUsersCase() {
        // "Apple Find My · 2 identities · 1 scan over 0 minutes" with one tag near its owner.
        val line = TrackerVerdict.line(facts(state = TrackerState.WITH_OWNER), FollowingProgress(1, 0), FollowingLevel.NONE)
        assertEquals(
            "Seen in 1 scan over 0 min. Flagged as following you only after 3 separate scans spread over at least 30 minutes (now 1 of 3 scans · 0 of 30 min). " +
                "It reports being near its owner, which is usually benign: the owner's phone is close by (someone in the room, on the bus, or in the next car).",
            line,
        )
        // When the family has more scans than this identity (rotating addresses), the line says so.
        val family = TrackerVerdict.line(facts(state = TrackerState.SEPARATED, scans = 1), FollowingProgress(2, 25), FollowingLevel.NONE)
        assertTrue(family, family.contains("(this family together: 2 of 3 scans · 25 of 30 min)"))
        assertTrue(family, family.contains("It reports being away from its owner."))
        // Over the threshold.
        val warn = TrackerVerdict.line(facts(type = TrackerType.TILE, scans = 4, span = 95), FollowingProgress(4, 95), FollowingLevel.WARN)
        assertTrue(warn, warn.startsWith("Seen in 4 scans over 1 h 35 min. Flagged as following you: this family has been with you in 4 scans over 1 h 35 min, past the threshold of 3 separate scans spread over at least 30 minutes."))
        assertTrue(warn, warn.endsWith("Tile tags do not say whether their owner is near; Tunnels can only count how often one recurs."))
        // CRITICAL quotes the separated-only counts the rule judged, not the family's overall ones.
        val critical = TrackerVerdict.line(facts(state = TrackerState.SEPARATED, scans = 3, span = 70), FollowingProgress(5, 200), FollowingLevel.CRITICAL, separatedScans = 3, separatedMinutes = 70)
        assertTrue(critical, critical.contains("an Apple tag away from its owner was with you across 3 scans over 1 h 10 min."))
        val muted = TrackerVerdict.line(facts(muted = true), FollowingProgress(1, 0), FollowingLevel.NONE)
        assertTrue(muted, muted.contains("Muted as a known tracker"))
        assertEquals("Its advertisement did not say whether its owner is near.", TrackerVerdict.stateExplanation(TrackerType.APPLE_FINDMY, TrackerState.UNKNOWN))
    }

    @Test
    fun shortDurations() {
        assertEquals("0 min", TrackerVerdict.minutes(0))
        assertEquals("45 min", TrackerVerdict.minutes(45))
        assertEquals("1 h", TrackerVerdict.minutes(60))
        assertEquals("1 h 20 min", TrackerVerdict.minutes(80))
        assertEquals("3 d", TrackerVerdict.minutes(3 * 24 * 60))
    }

    @Test
    fun threatSummaryLines() {
        val none = emptyMap<TrackerType, FollowingLevel>()
        assertEquals("No trackers seen in 30 days.", ThreatSummary.line(emptyList(), none))
        assertEquals(
            "2 identities nearby: 1 near its owner, 1 separated (seen 1×, not yet following)",
            ThreatSummary.line(listOf(facts(key = "a", state = TrackerState.WITH_OWNER), facts(key = "b", state = TrackerState.SEPARATED)), none),
        )
        assertEquals(
            "1 identity nearby: 1 state unknown (seen 1×, not yet following)",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE)), none),
        )
        // "seen N×" counts the family's scans: one rotating tag seen in 2 scans under 2 identities is "seen 2×".
        assertEquals(
            "2 identities nearby: 2 separated (seen 2×, not yet following)",
            ThreatSummary.line(listOf(facts(key = "a", state = TrackerState.SEPARATED), facts(key = "b", state = TrackerState.SEPARATED)), none, mapOf(TrackerType.APPLE_FINDMY to 2)),
        )
        // Unlisted identities count in the head and get their own part.
        assertEquals(
            "43 identities in 30 days, 1 in this scan: 1 state unknown (seen 1×, not yet following), 42 not listed",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE)), none, unlisted = 42),
        )
        assertEquals(
            "3 identities in 30 days, 1 in this scan: 2 near their owners, 1 separated (following you)",
            ThreatSummary.line(
                listOf(
                    facts(key = "a", state = TrackerState.WITH_OWNER, thisScan = false),
                    facts(key = "b", state = TrackerState.WITH_OWNER, thisScan = false),
                    facts(key = "c", state = TrackerState.SEPARATED, scans = 4, span = 90),
                ),
                mapOf(TrackerType.APPLE_FINDMY to FollowingLevel.CRITICAL),
            ),
        )
        assertEquals(
            "2 identities in 30 days, none in this scan: 1 state unknown (seen 2×, not yet following), 1 muted",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE, key = "a", scans = 2, thisScan = false), facts(key = "b", muted = true, thisScan = false)), none),
        )
    }

    @Test
    fun monitorLineUsesTheSameWords() {
        assertEquals("no trackers yet", ThreatSummary.monitorLine(emptyMap()))
        assertEquals("1 identity since start: 1 near its owner", ThreatSummary.monitorLine(mapOf(TrackerState.WITH_OWNER to 1)))
        assertEquals(
            "3 identities since start: 1 near its owner, 2 separated",
            ThreatSummary.monitorLine(mapOf(TrackerState.SEPARATED to 2, TrackerState.WITH_OWNER to 1, TrackerState.UNKNOWN to 0)),
        )
        assertEquals("1 identity", ThreatSummary.identities(1))
        assertEquals("2 identities", ThreatSummary.identities(2))
    }
}
