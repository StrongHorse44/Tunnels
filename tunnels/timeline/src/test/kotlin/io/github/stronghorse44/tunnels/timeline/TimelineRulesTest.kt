package io.github.stronghorse44.tunnels.timeline

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
import java.time.LocalDate

class TimelineRulesTest {
    private val t = TimelineKeys.TUNNEL_ID
    private val today = LocalDate.of(2026, 9, 30)
    private val rules = TimelineRules.all { today }

    private fun app(
        pkg: String,
        system: Boolean = false,
        firstInstall: String? = "2025-01-01",
        fgMinutes30: Long = 120,
        lastUsed: String = "2026-09-29",
        wifiMb: Long? = 10,
        mobileMb: Long? = 0,
        fgMb: Long? = null,
        bgMb: Long? = null,
    ): List<Observation> = buildList {
        fun add(key: String, value: String) = add(Observation(t, pkg, key, value))
        add(TimelineKeys.LABEL, pkg.substringAfterLast('.'))
        add(TimelineKeys.SYSTEM, system.toString())
        firstInstall?.let { add(TimelineKeys.FIRST_INSTALL, it) }
        add(TimelineKeys.FG_MINUTES_7, "0")
        add(TimelineKeys.FG_MINUTES_30, fgMinutes30.toString())
        add(TimelineKeys.DAYS_USED_30, "1")
        add(TimelineKeys.LAUNCHES_7, "0")
        add(TimelineKeys.LAST_USED, lastUsed)
        wifiMb?.let { add(TimelineKeys.WIFI_MB_30, it.toString()) }
        mobileMb?.let { add(TimelineKeys.MOBILE_MB_30, it.toString()) }
        fgMb?.let { add(TimelineKeys.FG_MB_30, it.toString()) }
        bgMb?.let { add(TimelineKeys.BG_MB_30, it.toString()) }
    }

    private val summary = TimelineObservations.summary(true, 3, 1, 1_000_000, TimelineKeys.NET_YES)

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current + summary, previous?.let { DiffEngine.diff(it + summary, current + summary) }.orEmpty(), isFirstScan = previous == null)
        return rules.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun unusedAppNeedsSixtyDaysAndAUserApp() {
        val never = app("com.never", lastUsed = TimelineKeys.NEVER)
        val old = app("com.old", lastUsed = "2026-07-01")           // 91 days
        val edge = app("com.edge", lastUsed = "2026-08-01")         // exactly 60 days
        val recent = app("com.recent", lastUsed = "2026-08-02")     // 59 days
        val system = app("com.sys", system = true, lastUsed = TimelineKeys.NEVER)
        val fresh = app("com.fresh", firstInstall = "2026-09-01", lastUsed = TimelineKeys.NEVER)
        val unknownInstall = app("com.unknown", firstInstall = null, lastUsed = TimelineKeys.NEVER)
        val drafts = evaluate(never + old + edge + recent + system + fresh + unknownInstall).of(TimelineRules.UNUSED_APP)
        assertEquals(setOf("com.never", "com.old", "com.edge", "com.unknown"), drafts.map { it.subject }.toSet())
        assertTrue(drafts.all { it.severity == Severity.INFO && !it.sticky })
        assertEquals("Not opened in 60+ days; still holds its permissions (never opened).", drafts.single { it.subject == "com.never" }.evidence)
        assertEquals("Not opened in 60+ days; still holds its permissions (last opened 2026-07-01).", drafts.single { it.subject == "com.old" }.evidence)
        assertTrue("the summary subject never becomes a finding", drafts.none { it.subject == TimelineKeys.SUMMARY })
    }

    @Test
    fun heavyBackgroundDataHasTwoSeverities() {
        val fine = app("com.fine", fgMb = 400, bgMb = 50)
        val notice = app("com.notice", fgMb = 1, bgMb = 51)
        val warn = app("com.warn", fgMb = 0, bgMb = 501)
        val system = app("com.sys", system = true, bgMb = 2000)
        val unsplit = app("com.unsplit", wifiMb = 900, mobileMb = 900)
        val drafts = evaluate(fine + notice + warn + system + unsplit).of(TimelineRules.HEAVY_BACKGROUND_DATA)
        assertEquals(setOf("com.notice", "com.warn"), drafts.map { it.subject }.toSet())
        assertEquals(Severity.NOTICE, drafts.single { it.subject == "com.notice" }.severity)
        val w = drafts.single { it.subject == "com.warn" }
        assertEquals(Severity.WARN, w.severity)
        assertEquals("Moved 501 MB in the background over the last 30 days (while open: 0 MB).", w.evidence)
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun dataSpikeIsStickyAndNeedsAPreviousScan() {
        val before = app("com.a", mobileMb = 100) + app("com.b", mobileMb = 100) + app("com.sys", system = true, mobileMb = 0)
        val after = app("com.a", mobileMb = 301) + app("com.b", mobileMb = 300) + app("com.sys", system = true, mobileMb = 5000) +
            app("com.new", mobileMb = 900)
        assertTrue(evaluate(after).of(TimelineRules.DATA_SPIKE).isEmpty())
        assertTrue(evaluate(after, after).of(TimelineRules.DATA_SPIKE).isEmpty())
        val drafts = evaluate(after, before).of(TimelineRules.DATA_SPIKE)
        assertEquals(listOf("com.a"), drafts.map { it.subject })
        val d = drafts.single()
        assertTrue(d.sticky)
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals("Mobile data over the last 30 days jumped from 100 MB to 301 MB since the previous scan (+201 MB).", d.evidence)
        // A window that rolls off (less mobile than before) is not a spike.
        assertTrue(evaluate(before, after).of(TimelineRules.DATA_SPIKE).isEmpty())
    }

    @Test
    fun usageWithoutLaunchNeedsTrafficAndNoForeground() {
        val silent = app("com.silent", fgMinutes30 = 0, wifiMb = 15, mobileMb = 6)
        val small = app("com.small", fgMinutes30 = 0, wifiMb = 20, mobileMb = 0)
        val opened = app("com.opened", fgMinutes30 = 1, wifiMb = 500, mobileMb = 0)
        val system = app("com.sys", system = true, fgMinutes30 = 0, wifiMb = 500)
        val noNet = app("com.nonet", fgMinutes30 = 0, wifiMb = null, mobileMb = null)
        val drafts = evaluate(silent + small + opened + system + noNet).of(TimelineRules.USAGE_WITHOUT_LAUNCH)
        assertEquals(listOf("com.silent"), drafts.map { it.subject })
        assertEquals(Severity.NOTICE, drafts.single().severity)
        assertEquals("Moved 21 MB over the last 30 days (Wi-Fi 15 MB, mobile 6 MB) without being opened once.", drafts.single().evidence)
    }

    @Test
    fun keysHelpers() {
        assertEquals(60L, TimelineKeys.daysSince("2026-08-01", today))
        assertNull(TimelineKeys.daysSince(TimelineKeys.NEVER, today))
        assertNull(TimelineKeys.daysSince("yesterday", today))
        assertNull(TimelineKeys.daysSince(null, today))
        assertEquals(10L, TimelineKeys.totalMb(app("com.a", wifiMb = 10, mobileMb = 0)))
        assertNull(TimelineKeys.totalMb(app("com.a", wifiMb = 10, mobileMb = null)))
        assertFalse(TimelineKeys.isApp(summary))
        assertTrue(TimelineKeys.isSummary(TimelineKeys.SUMMARY))
        assertFalse(TimelineKeys.isUnused(summary, today))
        assertEquals(4, TimelineRules.all().size)
        assertEquals(setOf(TimelineRules.HEAVY_BACKGROUND_DATA, TimelineRules.DATA_SPIKE, TimelineRules.USAGE_WITHOUT_LAUNCH), TimelineRules.dataKinds)
    }
}
