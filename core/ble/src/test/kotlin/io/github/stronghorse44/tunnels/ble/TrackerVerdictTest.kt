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
    fun verdictLineUsesTheIdentitysOwnCounts() {
        // One tag near its owner, seen once.
        assertEquals(
            "Seen in 1 scan over 0 min. Flagged as following you only after 3 separate scans spread over at least 30 minutes by this same identity " +
                "(now 1 of 3 scans · 0 of 30 min). " +
                "It reports being near its owner, which is usually benign: the owner's phone is close by (someone in the room, on the bus, or in the next car).",
            TrackerVerdict.line(facts(state = TrackerState.WITH_OWNER)),
        )
        // Separated, two scans: the progress is this identity's, never a family's.
        assertEquals(
            "Seen in 2 scans over 25 min. Tunnels could not tell whether you moved between those scans. Flagged as following you only after 3 separate scans spread over at least 30 minutes by this same identity " +
                "(now 2 of 3 scans · 25 of 30 min). " +
                "It reports being away from its owner. A lost item looks like this, and so does a planted tag; what matters is whether it keeps turning up as you move.",
            TrackerVerdict.line(facts(state = TrackerState.SEPARATED, scans = 2, span = 25)),
        )
        // Over the threshold.
        assertEquals(
            "Seen in 4 scans over 1 h 35 min. Tunnels could not tell whether you moved between those scans. Flagged as following you: this identity was with you in 4 scans over 1 h 35 min, " +
                "past the threshold of 3 separate scans spread over at least 30 minutes. " +
                "Tile tags do not say whether their owner is near; Tunnels can only count how often one recurs.",
            TrackerVerdict.line(facts(type = TrackerType.TILE, scans = 4, span = 95)),
        )
        // CRITICAL quotes the separated-only counts the rule judged.
        val critical = facts(state = TrackerState.SEPARATED, scans = 4, span = 90).copy(separatedScans = 3, separatedMinutes = 70)
        assertEquals(FollowingLevel.CRITICAL, critical.level)
        assertEquals(
            "Seen in 4 scans over 1 h 30 min. Tunnels could not tell whether you moved between those scans. Flagged as following you: this identity, reporting itself away from its owner, was with you across 3 scans over 1 h 10 min. " +
                "It reports being away from its owner. A lost item looks like this, and so does a planted tag; what matters is whether it keeps turning up as you move.",
            TrackerVerdict.line(critical),
        )
        val muted = facts(muted = true, scans = 5, span = 100)
        assertEquals(FollowingLevel.NONE, muted.level)
        assertTrue(TrackerVerdict.line(muted).contains("Muted as a known tracker: it is listed but never flagged."))
        assertFalse(TrackerVerdict.line(critical).contains("family"))
        assertEquals("Its advertisement did not say whether its owner is near.", TrackerVerdict.stateExplanation(TrackerType.APPLE_FINDMY, TrackerState.UNKNOWN))
        assertEquals("away from owner", TrackerVerdict.stateLabel(TrackerState.SEPARATED))
    }

    @Test
    fun identityRowsSortByClosenessThenLastSeen() {
        val rows = listOf(
            facts(key = "once-old", scans = 1, thisScan = false).copy(lastSeen = t0),
            facts(key = "once-new", scans = 1).copy(lastSeen = t0 + 99 * 60_000),
            facts(key = "two", scans = 2, span = 20),
            facts(key = "warn", scans = 3, span = 40),
            facts(key = "muted", scans = 9, span = 300, muted = true),
        )
        assertEquals(listOf("warn", "two", "once-new", "once-old", "muted"), rows.sortedWith(IdentityFacts.order).map { it.key })
        assertEquals("2 of 3 scans", rows[2].progress.scansHint)
        assertEquals("3 of 3 scans", facts(scans = 7).progress.scansHint)
    }

    @Test
    fun familyCardSummarisesIdentities() {
        val minute = 60_000L
        // The field case: 35 strangers over 17 scans in 2 h 7 min, nobody close.
        val crowd = (0 until 35).map { i ->
            SightingRecord("s${i % 17}", t0 + (i % 17) * 127L * minute / 16, TrackerType.APPLE_FINDMY, "c%07x".format(i),
                if (i < 3) TrackerState.WITH_OWNER else if (i < 5) TrackerState.UNKNOWN else TrackerState.SEPARATED, -70)
        }
        fun family(records: List<SightingRecord>, muted: Set<String> = emptySet()): FamilyFacts {
            val obs = SurroundingsKeys.bleObservations(SightingAggregator.aggregate(records), 100, SurroundingsKeys.AVAILABLE_YES, t0, muted)
            val listed = obs.groupBy { it.subject }.mapNotNull { (s, o) ->
                SurroundingsKeys.parseTrackerSubject(s)?.second?.let { IdentityFacts.from(TrackerType.APPLE_FINDMY, it, o.associate { x -> x.key to x.value }) }
            }
            return FamilyFacts.from(TrackerType.APPLE_FINDMY, obs.filter { it.subject == "tracker:findmy" }.associate { it.key to it.value }, listed)
        }
        val f = family(crowd)
        assertEquals("35 identities · 17 scans over 2 h 7 min", FamilySummary.headline(f))
        assertEquals("3 near their owners · 30 separated · 2 state unknown", FamilySummary.states(f))
        assertEquals("Closest to following: none close", FamilySummary.closest(f))

        // One identity in two scans 25 minutes apart.
        val two = crowd + listOf(0L, 25L).mapIndexed { i, m -> SightingRecord("s$i", t0 + m * minute, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.SEPARATED, -60) }
        assertEquals("Closest to following: deadbeef, 2 of 3 scans · 25 of 30 min", FamilySummary.closest(family(two)))
        // Muted, it is not the closest any more.
        assertEquals("Closest to following: none close", FamilySummary.closest(family(two, setOf("tracker:findmy:deadbeef"))))

        // Over the threshold.
        val three = crowd + listOf(0L, 20L, 40L).mapIndexed { i, m -> SightingRecord("s$i", t0 + m * minute, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.SEPARATED, -60) }
        assertEquals("Following you: deadbeef, 3 of 3 scans · 30 of 30 min", FamilySummary.closest(family(three)))

        // A snapshot from before v3 (no summary keys) falls back to the listed identities.
        val old = FamilyFacts.from(
            TrackerType.APPLE_FINDMY,
            mapOf(SurroundingsKeys.DEVICES to "5", SurroundingsKeys.SEEN_SESSIONS to "3", SurroundingsKeys.SEEN_SPAN to "50"),
            listOf(
                facts(key = "a", state = TrackerState.WITH_OWNER),
                facts(key = "b", state = TrackerState.SEPARATED, scans = 2, span = 20),
                facts(key = "c", state = TrackerState.SEPARATED, scans = 2, span = 4),
            ),
        )
        // The counts sum to the identity total: the unlisted remainder is "state unknown".
        assertEquals("1 near its owner · 2 separated · 2 state unknown", FamilySummary.states(old))
        assertEquals("Closest to following: b, 2 of 3 scans · 20 of 30 min", FamilySummary.closest(old))
        // Two scans four minutes apart are not close.
        val quick = FamilyFacts.from(TrackerType.APPLE_FINDMY, emptyMap(), listOf(facts(key = "c", scans = 2, span = 4)))
        assertEquals("Closest to following: none close", FamilySummary.closest(quick))
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
        assertEquals("No trackers seen in 30 days.", ThreatSummary.line(emptyList()))
        assertEquals(
            "2 identities nearby: 1 near its owner, 1 separated (seen 1×, not yet following)",
            ThreatSummary.line(listOf(facts(key = "a", state = TrackerState.WITH_OWNER), facts(key = "b", state = TrackerState.SEPARATED))),
        )
        assertEquals(
            "1 identity nearby: 1 state unknown (seen 1×, not yet following)",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE))),
        )
        // "seen N×" is the most-seen identity's own count: many identities seen once each stay "seen 1×".
        assertEquals(
            "30 identities nearby: 30 separated (seen 1×, not yet following)",
            ThreatSummary.line((0 until 30).map { facts(key = "k$it", state = TrackerState.SEPARATED) }),
        )
        // Unlisted identities count in the head and get their own part.
        assertEquals(
            "43 identities in 30 days, 1 in this scan: 1 state unknown (seen 1×, not yet following), 42 not listed",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE)), unlisted = 42),
        )
        // "following you" only when an identity of the group is over the threshold on its own.
        assertEquals(
            "3 identities in 30 days, 1 in this scan: 2 near their owners, 1 separated (following you)",
            ThreatSummary.line(
                listOf(
                    facts(key = "a", state = TrackerState.WITH_OWNER, thisScan = false),
                    facts(key = "b", state = TrackerState.WITH_OWNER, thisScan = false),
                    facts(key = "c", state = TrackerState.SEPARATED, scans = 4, span = 90).copy(separatedScans = 4, separatedMinutes = 90),
                ),
            ),
        )
        // A near-owner identity over the threshold is called out too.
        assertEquals(
            "1 identity nearby: 1 near its owner (following you)",
            ThreatSummary.line(listOf(facts(state = TrackerState.WITH_OWNER, scans = 3, span = 40))),
        )
        assertEquals(
            "2 identities in 30 days, none in this scan: 1 state unknown (seen 2×, not yet following), 1 muted",
            ThreatSummary.line(listOf(facts(type = TrackerType.TILE, key = "a", scans = 2, thisScan = false), facts(key = "b", muted = true, thisScan = false))),
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
        assertEquals(
            "3 identities since start: 1 near its owner, 2 separated · 1 identity close to following",
            ThreatSummary.monitorLine(mapOf(TrackerState.SEPARATED to 2, TrackerState.WITH_OWNER to 1), close = 1),
        )
        assertEquals(
            "4 identities since start: 4 separated · 1 identity following you · 2 identities close to following",
            ThreatSummary.monitorLine(mapOf(TrackerState.SEPARATED to 4), close = 2, following = 1),
        )
        assertEquals("1 identity", ThreatSummary.identities(1))
        assertEquals("2 identities", ThreatSummary.identities(2))
    }
}
