package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundingsRulesTest {
    private val t = SurroundingsKeys.TUNNEL_ID

    private fun type(
        type: TrackerType,
        sessions: Int,
        span: Long,
        sepSessions: Int = 0,
        sepSpan: Long = 0,
        devices: Int = 1,
        muted: Boolean = false,
    ): List<Observation> {
        val s = SurroundingsKeys.typeSubject(type)
        return buildList {
            add(Observation(t, s, SurroundingsKeys.DEVICES, devices.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SESSIONS, sessions.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SPAN, span.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SESSIONS_SEPARATED, sepSessions.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SPAN_SEPARATED, sepSpan.toString()))
            add(Observation(t, s, SurroundingsKeys.STATE, if (sepSessions > 0) "separated" else "unknown"))
            if (muted) add(Observation(t, s, SurroundingsKeys.MUTED, "true"))
        }
    }

    private fun wifi(ssid: String, security: String, current: Boolean = false, twin: String? = null) = buildList {
        add(Observation(t, ssid, SurroundingsKeys.WIFI_SECURITY, security))
        add(Observation(t, ssid, SurroundingsKeys.WIFI_BSSIDS, "1"))
        add(Observation(t, ssid, SurroundingsKeys.WIFI_CURRENT, current.toString()))
        twin?.let { add(Observation(t, ssid, SurroundingsKeys.WIFI_TWIN, it)) }
    }

    private fun cell(tech: String) = listOf(
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_TYPE, tech),
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_OPERATOR, "262-01"),
    )

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return SurroundingsRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    private fun identity(
        type: TrackerType,
        key: String,
        sessions: Int,
        span: Long,
        sepSessions: Int = 0,
        sepSpan: Long = 0,
        state: TrackerState = if (sepSessions > 0) TrackerState.SEPARATED else TrackerState.UNKNOWN,
        muted: Boolean = false,
    ): List<Observation> {
        val s = SurroundingsKeys.trackerSubject(type, key)
        return buildList {
            add(Observation(t, s, SurroundingsKeys.SEEN_SESSIONS, sessions.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SPAN, span.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SESSIONS_SEPARATED, sepSessions.toString()))
            add(Observation(t, s, SurroundingsKeys.SEEN_SPAN_SEPARATED, sepSpan.toString()))
            add(Observation(t, s, SurroundingsKeys.STATE, state.slug))
            if (muted) add(Observation(t, s, SurroundingsKeys.MUTED, "true"))
        }
    }

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    /** The whole path a scan takes: stored sighting rows → aggregate → observations (with mutes) → rules. */
    private fun observe(records: List<SightingRecord>, muted: Set<String> = emptySet(), now: Long = records.maxOf { it.at }): List<Observation> {
        val stored = records.map { SightingRecord.parse(it.subject, it.encode())!! }
        return SurroundingsKeys.bleObservations(SightingAggregator.aggregate(stored), 100, SurroundingsKeys.AVAILABLE_YES, now, muted)
    }

    private fun pipeline(records: List<SightingRecord>, muted: Set<String> = emptySet(), now: Long = records.maxOf { it.at }): List<FindingDraft> =
        evaluate(observe(records, muted, now)).of(SurroundingsRules.TRACKER_FOLLOWING)

    private val day = 24 * 60 * minute

    @Test
    fun followingFindingsNeedARecentSighting() {
        val records = crowd() + follower("deadbeef", 70, TrackerState.SEPARATED)
        val last = t0 + 70 * minute // deadbeef's last sighting
        assertEquals(1, pipeline(records, now = last + 7 * day).size)
        // Eight days on: no finding, but the identity is still listed as history and its verdict says so.
        val later = last + 8 * day
        assertTrue(pipeline(records, now = later).isEmpty())
        val obs = observe(records, now = later).filter { it.subject == "tracker:findmy:deadbeef" }.associate { it.key to it.value }
        assertEquals("false", obs[SurroundingsKeys.SEEN_RECENT])
        val facts = IdentityFacts.from(TrackerType.APPLE_FINDMY, "deadbeef", obs)
        assertEquals(FollowingLevel.NONE, facts.level)
        assertEquals(FollowingLevel.CRITICAL, facts.assessed)
        assertTrue(TrackerVerdict.line(facts), TrackerVerdict.line(facts).contains("has not been seen in the last 7 days, so it is kept as history and no longer flagged."))
        // A tag that changes identity daily leaves one stale key per day: only this week's ones can be flagged.
        val daily = (0 until 10).flatMap { d ->
            (0 until 3).map { i -> SightingRecord("d$d-$i", t0 + d * day + i * 40 * minute, TrackerType.APPLE_FINDMY, "day%05d".format(d), TrackerState.SEPARATED, -60) }
        }
        val flagged = pipeline(daily, now = t0 + 9 * day + 80 * minute).map { it.subject }
        assertEquals((2 until 10).map { "tracker:findmy:day%05d".format(it) }.toSet(), flagged.toSet())
    }

    @Test
    fun closeMeansRealProgress() {
        fun closest(records: List<SightingRecord>) = observe(records).single { it.subject == "tracker:findmy" && it.key == SurroundingsKeys.CLOSEST_KEY }.value
        // Two monitor windows four minutes apart: not close.
        val quick = listOf(0L, 4L).mapIndexed { i, m -> SightingRecord("m$i", t0 + m * minute, TrackerType.APPLE_FINDMY, "aaaa0001", TrackerState.SEPARATED, -60) }
        assertEquals("none", closest(crowd() + quick))
        // Two scans fifteen minutes apart (half the span): close.
        val slow = listOf(0L, 15L).mapIndexed { i, m -> SightingRecord("m$i", t0 + m * minute, TrackerType.APPLE_FINDMY, "aaaa0001", TrackerState.SEPARATED, -60) }
        assertEquals("aaaa0001", closest(crowd() + slow))
        assertTrue(FollowingHeuristic.isClose(2, 15))
        assertTrue(!FollowingHeuristic.isClose(2, 14))
        assertTrue(!FollowingHeuristic.isClose(1, 300))
    }

    @Test
    fun everyUnmutedWarnIdentityIsListedPastTheCap() {
        // 45 static Tile keys, each over the WARN threshold, plus 10 identities muted through a legacy family mute.
        val warn = (0 until 45).flatMap { k -> (0 until 3).map { i -> SightingRecord("s$i", t0 + i * 20 * minute, TrackerType.TILE, "w%07x".format(k), TrackerState.UNKNOWN, -60) } }
        val muted = (0 until 10).flatMap { k -> (0 until 5).map { i -> SightingRecord("s$i", t0 + i * 20 * minute, TrackerType.CHIPOLO, "m%07x".format(k), TrackerState.UNKNOWN, -60) } }
        val obs = observe(warn + muted, muted = setOf("tracker:chipolo"))
        val listed = obs.map { it.subject }.filter { SurroundingsKeys.parseTrackerSubject(it)?.second != null }.toSet()
        assertEquals(45, listed.count { it.startsWith("tracker:tile:") })
        // Muted ones sort last, so none of them made the 40 rows.
        assertEquals(0, listed.count { it.startsWith("tracker:chipolo:") })
        assertEquals("10", SurroundingsKeys.value(obs.filter { it.subject == SurroundingsKeys.BLE_SUMMARY }, SurroundingsKeys.TRACKERS_UNLISTED))
        assertEquals(45, evaluate(obs).of(SurroundingsRules.TRACKER_FOLLOWING).size)
    }

    @Test
    fun rotatingNonAppleTagGetsAWeakNotice() {
        // One Tile, one or two keys per scan, changing key, across 4 scans over an hour.
        val keys = listOf("k1", "k1", "k2", "k3")
        val lone = keys.mapIndexed { i, k -> SightingRecord("s$i", t0 + i * 20 * minute, TrackerType.TILE, k, TrackerState.UNKNOWN, -60) } +
            SightingRecord("s2", t0 + 40 * minute, TrackerType.TILE, "k2b", TrackerState.UNKNOWN, -70)
        val drafts = evaluate(observe(lone))
        val notice = drafts.of(SurroundingsRules.ROTATING_TRACKER).single()
        assertEquals("tracker:tile", notice.subject)
        assertEquals(Severity.NOTICE, notice.severity)
        assertEquals(
            "A Tile tag was seen in 4 separate scans over 1 hour under 4 changing identities, never more than 2 at a time. " +
                "That fits one tag that changes its identity, but Tunnels has not verified how often Tile tags do that, " +
                "and a different stranger's tag in each scan looks the same. If it keeps happening as you move, use Find it and check bags, pockets and the car.",
            notice.evidence,
        )
        assertTrue(drafts.of(SurroundingsRules.TRACKER_FOLLOWING).isEmpty())
        // A crowd (many Tile keys per scan) never matches, even recurring over hours.
        val crowd = (0 until 4).flatMap { i -> (0 until 5).map { k -> SightingRecord("s$i", t0 + i * 20 * minute, TrackerType.TILE, "c$i-$k", TrackerState.UNKNOWN, -70) } }
        assertTrue(evaluate(observe(crowd)).of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        // Apple tags never get it (they say whether they are separated); too short or too few scans neither.
        assertTrue(evaluate(observe(lone.map { it.copy(type = TrackerType.APPLE_FINDMY) })).of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        assertTrue(evaluate(observe(lone.filter { it.session in setOf("s0", "s2") })).of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        val short = keys.mapIndexed { i, k -> SightingRecord("s$i", t0 + i * 5 * minute, TrackerType.TILE, k, TrackerState.UNKNOWN, -60) }
        assertTrue(evaluate(observe(short)).of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        // A legacy family mute silences it; a static key over the threshold gets its own WARN instead.
        assertTrue(evaluate(observe(lone, muted = setOf("tracker:tile"))).of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        val static = (0 until 3).map { i -> SightingRecord("s$i", t0 + i * 20 * minute, TrackerType.TILE, "k1", TrackerState.UNKNOWN, -60) }
        val both = evaluate(observe(static + SightingRecord("s1", t0 + 20 * minute, TrackerType.TILE, "k9", TrackerState.UNKNOWN, -60)))
        assertTrue(both.of(SurroundingsRules.ROTATING_TRACKER).isEmpty())
        assertEquals(1, both.of(SurroundingsRules.TRACKER_FOLLOWING).size)
    }

    /** The field case: 35 strangers' Find My identities, each heard once, spread over 17 scans in 2 h 7 min. */
    private fun crowd(): List<SightingRecord> = (0 until 35).map { i ->
        val scan = i % 17
        val state = when {
            i < 3 -> TrackerState.WITH_OWNER
            i < 5 -> TrackerState.UNKNOWN
            else -> TrackerState.SEPARATED
        }
        SightingRecord("s$scan", t0 + scan * 127L * minute / 16, TrackerType.APPLE_FINDMY, "c%07x".format(i), state, -70)
    }

    private fun follower(key: String, minutes: Long, state: TrackerState) = (0 until 3).map { i ->
        SightingRecord("s${i * 3}", t0 + i * minutes / 2 * minute, TrackerType.APPLE_FINDMY, key, state, -60)
    }

    /** One Tile identity in [places].size scans 20 minutes apart; each scan at the given place number (null: no fix). */
    private fun tile(key: String, vararg places: Long?) = places.mapIndexed { i, p ->
        SightingRecord("p$i", t0 + i * 20 * minute, TrackerType.TILE, key, TrackerState.UNKNOWN, -62, place = p)
    }

    private fun stays(records: List<SightingRecord>) = evaluate(observe(records)).of(SurroundingsRules.TRACKER_STAYS)

    @Test
    fun aNeighboursTagThatStaysPutIsANoticeNotAWarning() {
        // Four scans over an hour at home: past the time threshold, but always at one place while the phone was there.
        val neighbour = tile("aa11bb22", 5, 5, 5, 5)
        assertTrue(pipeline(neighbour).isEmpty())
        val notice = stays(neighbour).single()
        assertEquals(Severity.NOTICE, notice.severity)
        assertEquals("tracker:tile:aa11bb22", notice.subject)
        assertTrue(notice.evidence, notice.evidence.contains("always at the same place while you were there"))
        assertTrue(notice.evidence.contains("Tunnels warns as soon as it turns up after you have moved"))
        val obs = observe(neighbour).filter { it.subject == "tracker:tile:aa11bb22" }.associate { it.key to it.value }
        assertEquals("stayed", obs[SurroundingsKeys.MOVEMENT])
        assertEquals("1", obs[SurroundingsKeys.SEEN_PLACE_RUN])
        assertEquals("4", obs[SurroundingsKeys.SEEN_PLACED_SESSIONS])
        // Not "close to following" either: the family card names nobody.
        val family = observe(neighbour).filter { it.subject == "tracker:tile" }.associate { it.key to it.value }
        assertEquals("none", family[SurroundingsKeys.CLOSEST_KEY])
        assertEquals("0", family[SurroundingsKeys.FOLLOWING_COUNT])
    }

    @Test
    fun goingOutAndComingHomeIsNotATagThatFollowed() {
        // Seen at home in the morning (place 5) and again at home in the evening (place 7, after a day at place 6
        // where it was not around): never on both sides of one move.
        val records = tile("aa11bb22", 5, 5, null, 7, 7)
        assertTrue(pipeline(records).isEmpty())
        assertEquals(1, stays(records).size)
    }

    @Test
    fun aTagThatTravelsWithYouIsFollowing() {
        // Home (5), the commute (6), work (7): there before and after each move.
        val planted = tile("cc33dd44", 5, 6, 6, 7)
        val warn = pipeline(planted).single()
        assertEquals(Severity.WARN, warn.severity)
        assertTrue(warn.evidence, warn.evidence.contains("It was there both before and after you moved to another place, so it travelled with you."))
        assertTrue(stays(planted).isEmpty())
        val obs = observe(planted).filter { it.subject == "tracker:tile:cc33dd44" }.associate { it.key to it.value }
        assertEquals("moved", obs[SurroundingsKeys.MOVEMENT])
        assertEquals("3", obs[SurroundingsKeys.SEEN_PLACE_RUN])
        // One move is enough, even when most scans had no fix.
        assertEquals(Severity.WARN, pipeline(tile("cc33dd44", null, 8, 9, null)).single().severity)
        // An AirTag away from its owner that moved with you over an hour is CRITICAL.
        val airtag = (0 until 4).map { i ->
            SightingRecord("a$i", t0 + i * 25 * minute, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.SEPARATED, -60, place = 10L + i / 2)
        }
        val critical = pipeline(airtag).single()
        assertEquals(Severity.CRITICAL, critical.severity)
        assertTrue(critical.evidence.contains("so it travelled with you."))
    }

    @Test
    fun withoutLocationTimeAloneStillWarns() {
        // Location off: no scan knew the place. Turning location off must never silence a warning.
        val unknown = tile("ee55ff66", null, null, null, null)
        val warn = pipeline(unknown).single()
        assertEquals(Severity.WARN, warn.severity)
        assertTrue(warn.evidence.contains("Tunnels could not check whether you moved between those scans (no location fix)"))
        // Two scans with a place are not enough to call it "stays put": still judged on time.
        assertEquals(Severity.WARN, pipeline(tile("ee55ff66", 5, 5, null, null)).single().severity)
        assertTrue(stays(tile("ee55ff66", 5, 5, null, null)).isEmpty())
    }

    @Test
    fun crowdOfStrangersIsNeverFollowing() {
        val crowd = crowd()
        assertEquals(17, crowd.map { it.session }.toSet().size)
        assertTrue(pipeline(crowd).isEmpty())
        // The family summary still shows the crowd, with nobody close to following.
        val obs = SurroundingsKeys.bleObservations(SightingAggregator.aggregate(crowd), 100, SurroundingsKeys.AVAILABLE_YES, t0 + 127 * minute)
        val family = obs.filter { it.subject == "tracker:findmy" }.associate { it.key to it.value }
        assertEquals("35", family[SurroundingsKeys.DEVICES])
        assertEquals("17", family[SurroundingsKeys.SEEN_SESSIONS])
        assertEquals("127", family[SurroundingsKeys.SEEN_SPAN])
        assertEquals("3", family[SurroundingsKeys.DEVICES_WITH_OWNER])
        assertEquals("30", family[SurroundingsKeys.DEVICES_SEPARATED])
        assertEquals("2", family[SurroundingsKeys.DEVICES_UNKNOWN])
        assertEquals("none", family[SurroundingsKeys.CLOSEST_KEY])
    }

    @Test
    fun separatedIdentityAmidTheCrowdIsFlaggedAlone() {
        // Three scans over 70 minutes, separated each time: past the CRITICAL span (60 min) for that key only.
        val critical = pipeline(crowd() + follower("deadbeef", 70, TrackerState.SEPARATED))
        assertEquals(listOf("tracker:findmy:deadbeef"), critical.map { it.subject })
        assertEquals(Severity.CRITICAL, critical.single().severity)
        assertEquals(
            "Apple Find My identity deadbeef, reporting itself away from its owner, was with you in 3 separate scans over 1 hour 10 minutes, last seen ${SurroundingsRules.lastSeenLabel(t0 + 70 * minute)}. " +
                "It is the same identity in every one of those scans, not different tags of the same kind. " +
                "Tunnels could not check whether you moved between those scans (no location fix). " +
                "That is how an AirTag planted on a person behaves. Find it (it chirps when moved after a while), " +
                "remove its battery, and keep it as evidence if you suspect stalking.",
            critical.single().evidence,
        )
        // Three scans over 40 minutes: the same unchanged thresholds make that WARN (CRITICAL needs an hour separated).
        val warn = pipeline(crowd() + follower("deadbeef", 40, TrackerState.SEPARATED))
        assertEquals(listOf("tracker:findmy:deadbeef"), warn.map { it.subject })
        assertEquals(Severity.WARN, warn.single().severity)
        assertEquals(
            "Apple Find My identity deadbeef was seen in 3 separate scans over 40 minutes (away from owner), last seen ${SurroundingsRules.lastSeenLabel(t0 + 40 * minute)}. " +
                "It is the same identity in every one of those scans, not different tags of the same kind. " +
                "Tunnels could not check whether you moved between those scans (no location fix), so a tag that stays next door can look like this too; " +
                "with location on, Tunnels tells the two apart. " +
                "If it is yours or a companion's, mute it; otherwise check bags, pockets and the car.",
            warn.single().evidence,
        )
        // The family names it as the closest.
        val obs = SurroundingsKeys.bleObservations(SightingAggregator.aggregate(crowd() + follower("deadbeef", 40, TrackerState.SEPARATED)), 100, SurroundingsKeys.AVAILABLE_YES, t0)
        val family = obs.filter { it.subject == "tracker:findmy" }.associate { it.key to it.value }
        assertEquals("deadbeef", family[SurroundingsKeys.CLOSEST_KEY])
        assertEquals("3", family[SurroundingsKeys.CLOSEST_SESSIONS])
        assertEquals("40", family[SurroundingsKeys.CLOSEST_SPAN])
        assertEquals("1", family[SurroundingsKeys.FOLLOWING_COUNT])
    }

    @Test
    fun nearOwnerIdentityGetsTheSameWarnAsBeforePerKey() {
        // The per-type rule warned on any state after 3 scans over 30+ minutes; per key it still does, never CRITICAL.
        val drafts = pipeline(crowd() + follower("0a0b0c0d", 40, TrackerState.WITH_OWNER))
        assertEquals(listOf("tracker:findmy:0a0b0c0d"), drafts.map { it.subject })
        assertEquals(Severity.WARN, drafts.single().severity)
        assertTrue(drafts.single().evidence, drafts.single().evidence.contains("(near its owner)"))
    }

    @Test
    fun mutedIdentityAndOldFamilyMuteSuppress() {
        val records = crowd() + follower("deadbeef", 70, TrackerState.SEPARATED)
        assertTrue(pipeline(records, muted = setOf("tracker:findmy:deadbeef")).isEmpty())
        // A family mute written before v3 still mutes every identity of the family until it expires.
        assertTrue(pipeline(records, muted = setOf("tracker:findmy")).isEmpty())
        // Muting another family or another identity changes nothing.
        assertEquals(1, pipeline(records, muted = setOf("tracker:tile", "tracker:findmy:c0000001")).size)
        // A muted identity is never the family's closest.
        val obs = SurroundingsKeys.bleObservations(SightingAggregator.aggregate(records), 100, SurroundingsKeys.AVAILABLE_YES, t0, setOf("tracker:findmy:deadbeef"))
        assertEquals("none", obs.single { it.subject == "tracker:findmy" && it.key == SurroundingsKeys.CLOSEST_KEY }.value)
        assertEquals("0", obs.single { it.subject == "tracker:findmy" && it.key == SurroundingsKeys.FOLLOWING_COUNT }.value)
    }

    @Test
    fun trackerFollowingSeverities() {
        val drafts = evaluate(
            identity(TrackerType.TILE, "aaaa0001", sessions = 2, span = 400) +
                identity(TrackerType.CHIPOLO, "bbbb0002", sessions = 3, span = 45) +
                identity(TrackerType.APPLE_FINDMY, "cccc0003", sessions = 4, span = 120, sepSessions = 3, sepSpan = 75) +
                identity(TrackerType.SAMSUNG_SMARTTAG, "dddd0004", sessions = 5, span = 300, muted = true) +
                // Separated on a non-Apple family never upgrades to CRITICAL.
                identity(TrackerType.TILE, "eeee0005", sessions = 5, span = 300, sepSessions = 5, sepSpan = 300),
        ).of(SurroundingsRules.TRACKER_FOLLOWING)
        assertEquals(setOf("tracker:chipolo:bbbb0002", "tracker:findmy:cccc0003", "tracker:tile:eeee0005"), drafts.map { it.subject }.toSet())
        val chipolo = drafts.single { it.subject == "tracker:chipolo:bbbb0002" }
        assertEquals(Severity.WARN, chipolo.severity)
        assertTrue(chipolo.evidence, chipolo.evidence.startsWith("Chipolo identity bbbb0002 was seen in 3 separate scans over 45 minutes (state unknown). It is the same identity"))
        assertTrue(chipolo.evidence.contains("mute"))
        val apple = drafts.single { it.subject == "tracker:findmy:cccc0003" }
        assertEquals(Severity.CRITICAL, apple.severity)
        assertTrue(apple.evidence, apple.evidence.contains("3 separate scans over 1 hour 15 minutes"))
        assertEquals(Severity.WARN, drafts.single { it.subject == "tracker:tile:eeee0005" }.severity)
        // State rule: not sticky.
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun familySubjectsNeverCarryFollowingFindings() {
        // Even with family counts far past the threshold (and the pre-v3 separated keys), a family is a summary.
        assertTrue(evaluate(type(TrackerType.APPLE_FINDMY, sessions = 17, span = 127, sepSessions = 17, sepSpan = 127, devices = 35)).of(SurroundingsRules.TRACKER_FOLLOWING).isEmpty())
        // An identity row from before v3 (no separated keys) is still judged, at most WARN.
        val old = listOf(
            Observation(t, "tracker:findmy:ab12cd34", SurroundingsKeys.SEEN_SESSIONS, "9"),
            Observation(t, "tracker:findmy:ab12cd34", SurroundingsKeys.SEEN_SPAN, "900"),
            Observation(t, "tracker:findmy:ab12cd34", SurroundingsKeys.STATE, "separated"),
        )
        assertEquals(listOf(Severity.WARN), evaluate(old).of(SurroundingsRules.TRACKER_FOLLOWING).map { it.severity })
    }

    @Test
    fun newTrackerTypeIsStickyAndSkipsFirstScanAndMuted() {
        val first = type(TrackerType.TILE, 1, 0)
        assertTrue(evaluate(first).of(SurroundingsRules.NEW_TRACKER_TYPE).isEmpty())
        val second = first + type(TrackerType.CHIPOLO, 1, 0) + type(TrackerType.SAMSUNG_SMARTTAG, 1, 0, muted = true) +
            listOf(Observation(t, "tracker:findmy:deadbeef", SurroundingsKeys.SEEN_COUNT, "1"))
        val drafts = evaluate(second, first).of(SurroundingsRules.NEW_TRACKER_TYPE)
        assertEquals(listOf("tracker:chipolo"), drafts.map { it.subject })
        assertEquals(Severity.NOTICE, drafts.single().severity)
        assertTrue(drafts.single().sticky)
        assertTrue(drafts.single().evidence.startsWith("A Chipolo tracker was near you for the first time."))
        // Unchanged types do not fire again.
        assertTrue(evaluate(second, second).of(SurroundingsRules.NEW_TRACKER_TYPE).isEmpty())
    }

    @Test
    fun wifiRules() {
        val drafts = evaluate(
            wifi("Cafe", "open", current = true) + wifi("Hotel", "open") + wifi("Home", "wpa2", current = true) +
                wifi("Office", "wpa2", twin = "an open network uses the same name as a secured one"),
        )
        val open = drafts.of(SurroundingsRules.OPEN_WIFI_CONNECTED)
        assertEquals(listOf("Cafe"), open.map { it.subject })
        assertEquals(Severity.NOTICE, open.single().severity)
        assertTrue(open.single().evidence.contains("\"Cafe\""))
        val twin = drafts.of(SurroundingsRules.EVIL_TWIN_SUSPECT)
        assertEquals(listOf("Office"), twin.map { it.subject })
        assertEquals(Severity.WARN, twin.single().severity)
        assertTrue(twin.single().evidence.contains("an open network uses the same name as a secured one"))

        // A carrier network with an open copy: the finding says why it is suspicious despite the many vendors.
        val aps = (1..6).map { WifiNetwork("Spectrum Mobile", "0$it:1$it:2$it:00:00:01", "[RSN-EAP/SHA1-CCMP][ESS]", 5180) } +
            WifiNetwork("Spectrum Mobile", "de:ad:be:00:00:01", "[ESS]", 2437)
        val carrier = evaluate(SurroundingsKeys.wifiObservations(WifiHeuristics.summarise(aps), SurroundingsKeys.AVAILABLE_YES)).of(SurroundingsRules.EVIL_TWIN_SUSPECT)
        assertEquals(
            "\"Spectrum Mobile\" looks like it may have an impostor: a public hotspot name broadcast from many access points; " +
                "one of them advertises open security while others use enterprise sign-in. A fake access point with a familiar name can intercept traffic. " +
                "Forget the network if you do not need it, and do not enter passwords while on it.",
            carrier.single().evidence,
        )
        // All enterprise: nothing.
        assertTrue(evaluate(SurroundingsKeys.wifiObservations(WifiHeuristics.summarise(aps.dropLast(1)), SurroundingsKeys.AVAILABLE_YES)).of(SurroundingsRules.EVIL_TWIN_SUSPECT).isEmpty())
    }

    @Test
    fun cellRules() {
        val gsm = evaluate(cell("GSM")).of(SurroundingsRules.CELL_DOWNGRADE)
        assertEquals(1, gsm.size)
        assertEquals(Severity.WARN, gsm.single().severity)
        assertTrue(gsm.single().evidence.contains("LTE-only"))
        assertTrue(evaluate(cell("UMTS")).of(SurroundingsRules.CELL_DOWNGRADE).isEmpty())
        assertTrue(evaluate(cell("LTE")).of(SurroundingsRules.CELL_DOWNGRADE).isEmpty())

        val dropped = evaluate(cell("UMTS"), cell("LTE")).of(SurroundingsRules.CELL_DOWNGRADED)
        assertEquals(1, dropped.size)
        assertTrue(dropped.single().sticky)
        assertTrue(dropped.single().evidence, dropped.single().evidence.contains("fell from 4G (LTE) to 3G (UMTS)"))
        assertTrue(dropped.single().evidence.contains("LTE-only"))
        assertTrue(evaluate(cell("LTE"), cell("NR")).of(SurroundingsRules.CELL_DOWNGRADED).isEmpty())
        assertTrue(evaluate(cell("NR"), cell("GSM")).of(SurroundingsRules.CELL_DOWNGRADED).isEmpty())
        // First scan never produces the sticky one.
        assertTrue(evaluate(cell("GSM")).of(SurroundingsRules.CELL_DOWNGRADED).isEmpty())
    }

    @Test
    fun durations() {
        assertEquals("1 minute", SurroundingsRules.duration(1))
        assertEquals("45 minutes", SurroundingsRules.duration(45))
        assertEquals("1 hour", SurroundingsRules.duration(60))
        assertEquals("2 hours 5 minutes", SurroundingsRules.duration(125))
        assertEquals("3 days", SurroundingsRules.duration(3 * 24 * 60))
    }

    // The cell logbook's notice

    private fun logObs(
        verdict: String,
        signals: String = "none",
        tower: String? = null,
        tech: String = "LTE",
        operator: String = "310-260",
    ) = listOfNotNull(
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_TYPE, tech),
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_OPERATOR, operator),
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.LOG_STATE, "on"),
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.LOG_VERDICT, verdict),
        Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.LOG_SIGNALS, signals),
        tower?.let { Observation(t, SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.LOG_TOWER, it) },
    )

    @Test
    fun unfamiliarTowerIsOneStickyNotice() {
        val drafts = evaluate(logObs("unfamiliar", "area", tower = "0a1b2c3d")).of(SurroundingsRules.UNFAMILIAR_TOWER)
        assertEquals(1, drafts.size)
        val d = drafts.single()
        assertEquals("tower 0a1b2c3d", d.subject)
        assertEquals(Severity.NOTICE, d.severity)
        assertTrue(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("Unfamiliar tower at a place you scan often: the phone used a cell it has never used here, and the tracking area is new here."))
        assertTrue(d.evidence.contains("Usually this is the network changing (a new or re-planned cell)."))
        assertTrue(d.evidence.contains("use LTE only and turn off 2G."))
        // Two towers are two findings, one per scan's worst cell; a second scan with the same tower is the same finding.
        assertEquals(d.subject, evaluate(logObs("unfamiliar", "area", tower = "0a1b2c3d"), logObs("unfamiliar", "area", tower = "0a1b2c3d")).of(SurroundingsRules.UNFAMILIAR_TOWER).single().subject)
        // A garbled tower id makes no finding rather than a strange subject.
        assertTrue(evaluate(logObs("unfamiliar", "area", tower = "NOT-HEX!")).of(SurroundingsRules.UNFAMILIAR_TOWER).isEmpty())
        assertTrue(evaluate(logObs("unfamiliar", "area")).of(SurroundingsRules.UNFAMILIAR_TOWER).isEmpty())
        // The signals read as they are.
        val three = evaluate(logObs("unfamiliar", "area,downgrade,operator", tower = "0a1b2c3d", tech = "UMTS")).single { it.kind == SurroundingsRules.UNFAMILIAR_TOWER }.evidence
        assertTrue(three, three.contains("the tracking area is new here, the connection dropped to 3G (UMTS) and the operator 310-260 is new here."))
        val two = evaluate(logObs("unfamiliar", "downgrade,operator", tower = "0a1b2c3d", tech = "GSM", operator = "?")).single { it.kind == SurroundingsRules.UNFAMILIAR_TOWER }.evidence
        assertTrue(two, two.contains("the connection dropped to 2G (GSM) and the operator is new here."))
    }

    @Test
    fun noFindingForLearningFamiliarOrNewNormal() {
        for (v in listOf("learning", "familiar", "new-normal")) {
            assertTrue(v, evaluate(logObs(v, tower = "0a1b2c3d")).of(SurroundingsRules.UNFAMILIAR_TOWER).isEmpty())
        }
        assertTrue(evaluate(cell("LTE")).of(SurroundingsRules.UNFAMILIAR_TOWER).isEmpty())
        assertTrue(evaluate(emptyList()).of(SurroundingsRules.UNFAMILIAR_TOWER).isEmpty())
        // The logbook adds no other finding of its own.
        assertTrue(evaluate(logObs("unfamiliar", "area", tower = "0a1b2c3d")).none { it.kind != SurroundingsRules.UNFAMILIAR_TOWER })
    }

    @Test
    fun unfamiliarTowerWordingNeverSaysAttack() {
        val forbidden = listOf("attack", "detected", "imsi catcher", "imsi-catcher", "stingray")
        val combos = listOf(emptyList(), listOf(Signal.AREA), listOf(Signal.DOWNGRADE), listOf(Signal.OPERATOR), Signal.entries)
        for (signals in combos) for (tech in CellTech.entries) for (op in listOf(null, "?", "310-260")) {
            val text = SurroundingsRules.unfamiliarTowerEvidence(signals, tech, op).lowercase()
            for (w in forbidden) assertFalse("$w in $text", text.contains(w))
        }
        assertTrue(SurroundingsRules.all.contains(SurroundingsRules.unfamiliarTower))
    }
}
