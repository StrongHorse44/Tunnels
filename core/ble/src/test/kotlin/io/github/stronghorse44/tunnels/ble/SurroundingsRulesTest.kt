package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
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
    private fun pipeline(records: List<SightingRecord>, muted: Set<String> = emptySet()): List<FindingDraft> {
        val stored = records.map { SightingRecord.parse(it.subject, it.encode())!! }
        val obs = SurroundingsKeys.bleObservations(SightingAggregator.aggregate(stored), 100, SurroundingsKeys.AVAILABLE_YES, records.maxOf { it.at }, muted)
        return evaluate(obs).of(SurroundingsRules.TRACKER_FOLLOWING)
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
            "Apple Find My identity deadbeef, reporting itself away from its owner, was with you in 3 separate scans over 1 hour 10 minutes. " +
                "It is the same identity in every one of those scans, not different tags of the same kind. " +
                "That is how an AirTag planted on a person behaves. Find it (it chirps when moved after a while), " +
                "remove its battery, and keep it as evidence if you suspect stalking.",
            critical.single().evidence,
        )
        // Three scans over 40 minutes: the same unchanged thresholds make that WARN (CRITICAL needs an hour separated).
        val warn = pipeline(crowd() + follower("deadbeef", 40, TrackerState.SEPARATED))
        assertEquals(listOf("tracker:findmy:deadbeef"), warn.map { it.subject })
        assertEquals(Severity.WARN, warn.single().severity)
        assertEquals(
            "Apple Find My identity deadbeef was seen in 3 separate scans over 40 minutes (away from owner). " +
                "It is the same identity in every one of those scans, not different tags of the same kind. " +
                "A tag that stays with you across places and hours may have been planted. " +
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
            "\"Spectrum Mobile\" looks like it may have an impostor: normally a carrier network with many access points; " +
                "this one advertises open security while others use enterprise sign-in. A fake access point with a familiar name can intercept traffic. " +
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
}
