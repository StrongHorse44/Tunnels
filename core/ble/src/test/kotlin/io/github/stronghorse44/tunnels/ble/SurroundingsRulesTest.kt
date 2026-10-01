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

    @Test
    fun trackerFollowingSeverities() {
        val drafts = evaluate(
            type(TrackerType.TILE, sessions = 2, span = 400) +
                type(TrackerType.CHIPOLO, sessions = 3, span = 45, devices = 2) +
                type(TrackerType.APPLE_FINDMY, sessions = 4, span = 120, sepSessions = 3, sepSpan = 75) +
                type(TrackerType.SAMSUNG_SMARTTAG, sessions = 5, span = 300, muted = true),
        ).of(SurroundingsRules.TRACKER_FOLLOWING)
        assertEquals(setOf("tracker:chipolo", "tracker:findmy"), drafts.map { it.subject }.toSet())
        val chipolo = drafts.single { it.subject == "tracker:chipolo" }
        assertEquals(Severity.WARN, chipolo.severity)
        assertTrue(chipolo.evidence, chipolo.evidence.startsWith("Chipolo trackers seen in 3 separate scans over 45 minutes under 2 rotating identities."))
        assertTrue(chipolo.evidence.contains("mute"))
        val apple = drafts.single { it.subject == "tracker:findmy" }
        assertEquals(Severity.CRITICAL, apple.severity)
        assertTrue(apple.evidence, apple.evidence.contains("3 separate scans over 1 hour 15 minutes"))
        assertTrue(apple.evidence.contains("evidence"))
        // State rule: not sticky.
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun deviceSubjectsNeverCarryFollowingFindings() {
        val device = listOf(
            Observation(t, "tracker:tile:ab12cd34", SurroundingsKeys.SEEN_SESSIONS, "9"),
            Observation(t, "tracker:tile:ab12cd34", SurroundingsKeys.SEEN_SPAN, "900"),
        )
        assertTrue(evaluate(device).isEmpty())
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
