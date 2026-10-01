package io.github.stronghorse44.tunnels.dns

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRulesTest {
    private val t = TrafficKeys.TUNNEL_ID

    private fun app(pkg: String, domains: Int = 5, queries: Int = 50, top: List<String> = listOf("a.com"), trackers: Int = 0, trackerTop: List<String> = emptyList()): List<Observation> =
        buildList {
            add(Observation(t, pkg, TrafficKeys.DOMAINS30, domains.toString()))
            add(Observation(t, pkg, TrafficKeys.QUERIES30, queries.toString()))
            if (top.isNotEmpty()) add(Observation(t, pkg, TrafficKeys.TOP, top.joinToString(",")))
            add(Observation(t, pkg, TrafficKeys.TRACKER_DOMAINS30, trackers.toString()))
            if (trackerTop.isNotEmpty()) add(Observation(t, pkg, TrafficKeys.TRACKER_TOP, trackerTop.joinToString(",")))
        }

    private fun summary(sessions: Int = 1, active: Boolean = false, otherVpn: Boolean = false) = listOf(
        Observation(t, TrafficKeys.SUMMARY, TrafficKeys.SESSIONS_COUNT30, sessions.toString()),
        Observation(t, TrafficKeys.SUMMARY, TrafficKeys.SESSION_ACTIVE, active.toString()),
        Observation(t, TrafficKeys.SUMMARY, TrafficKeys.VPN_OTHER_ACTIVE, otherVpn.toString()),
    )

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return TrafficRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun trackerDomainsSeverityAndEvidence() {
        val few = app("com.a", trackers = 2, trackerTop = listOf("app-measurement.com", "graph.facebook.com"))
        val many = app("com.b", trackers = 7, trackerTop = listOf("a.io", "b.io", "c.io", "d.io", "e.io"))
        val clean = app("com.c")
        val one = app("com.d", trackers = 1, trackerTop = listOf("sentry.io"))
        val drafts = evaluate(few + many + clean + one + summary()).of(TrafficRules.TRACKER_DOMAINS)
        assertEquals(setOf("com.a", "com.b", "com.d"), drafts.map { it.subject }.toSet())
        val a = drafts.single { it.subject == "com.a" }
        assertEquals(Severity.NOTICE, a.severity)
        assertEquals("Contacted 2 tracking domains in the last 30 days: app-measurement.com, graph.facebook.com.", a.evidence)
        val b = drafts.single { it.subject == "com.b" }
        assertEquals(Severity.WARN, b.severity)
        assertEquals("Contacted 7 tracking domains in the last 30 days: a.io, b.io, c.io, d.io, e.io and 2 more.", b.evidence)
        assertEquals("Contacted 1 tracking domain in the last 30 days: sentry.io.", drafts.single { it.subject == "com.d" }.evidence)
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun talkativeAppThreshold() {
        val quiet = app("com.q", domains = 39)
        val loud = app("com.l", domains = 40, queries = 900)
        val drafts = evaluate(quiet + loud + summary()).of(TrafficRules.TALKATIVE_APP)
        assertEquals(listOf("com.l"), drafts.map { it.subject })
        assertEquals(Severity.INFO, drafts.single().severity)
        assertEquals("Looked up at least 40 different domains in one logging session (900 queries in 30 days).", drafts.single().evidence)
    }

    @Test
    fun newTrackerDomainIsStickyAndNamesTheNewcomers() {
        val before = app("com.a", trackers = 1, trackerTop = listOf("sentry.io")) + app("com.same", trackers = 2, trackerTop = listOf("x.io", "y.io")) + summary()
        val after = app("com.a", trackers = 3, trackerTop = listOf("sentry.io", "adjust.com", "branch.io")) +
            app("com.same", trackers = 2, trackerTop = listOf("x.io", "y.io")) +
            app("com.fresh", trackers = 4, trackerTop = listOf("q.io")) +
            summary()
        assertTrue(evaluate(after).of(TrafficRules.NEW_TRACKER_DOMAIN).isEmpty())
        assertTrue(evaluate(after, after).of(TrafficRules.NEW_TRACKER_DOMAIN).isEmpty())
        val drafts = evaluate(after, before).of(TrafficRules.NEW_TRACKER_DOMAIN)
        assertEquals(listOf("com.a"), drafts.map { it.subject })
        val d = drafts.single()
        assertTrue(d.sticky)
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals("Now contacts 3 tracking domains, up from 1. New: adjust.com, branch.io.", d.evidence)
        // A drop never fires.
        assertTrue(evaluate(before, after).of(TrafficRules.NEW_TRACKER_DOMAIN).isEmpty())
        // Count up without a top change still fires, without the detail.
        val noTop = app("com.a", trackers = 2, trackerTop = listOf("sentry.io")) + summary()
        val fired = evaluate(noTop, app("com.a", trackers = 1, trackerTop = listOf("sentry.io")) + summary()).of(TrafficRules.NEW_TRACKER_DOMAIN).single()
        assertEquals("Now contacts 2 tracking domains, up from 1.", fired.evidence)
    }

    @Test
    fun otherVpnActiveOnlyOnTheSummarySubject() {
        assertTrue(evaluate(summary(otherVpn = false)).of(TrafficRules.OTHER_VPN_ACTIVE).isEmpty())
        val d = evaluate(app("com.a") + summary(otherVpn = true)).of(TrafficRules.OTHER_VPN_ACTIVE).single()
        assertEquals(TrafficKeys.SUMMARY, d.subject)
        assertEquals(Severity.INFO, d.severity)
        assertTrue(d.evidence.contains("Disconnect it"))
        assertTrue(!d.sticky)
    }

    @Test
    fun summarySubjectNeverGetsAppFindings() {
        val odd = summary() + Observation(t, TrafficKeys.SUMMARY, TrafficKeys.TRACKER_DOMAINS30, "9") + Observation(t, TrafficKeys.SUMMARY, TrafficKeys.DOMAINS30, "99")
        val drafts = evaluate(odd)
        assertTrue(drafts.none { it.kind == TrafficRules.TRACKER_DOMAINS || it.kind == TrafficRules.TALKATIVE_APP })
    }

    @Test
    fun describeHandlesEmptyLists() {
        assertEquals("names not recorded", TrafficRules.describe(emptyList(), 3))
        assertEquals("a.com", TrafficRules.describe(listOf("a.com"), 1))
        assertEquals("a.com and 4 more", TrafficRules.describe(listOf("a.com"), 5))
    }
}
