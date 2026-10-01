package io.github.stronghorse44.tunnels.doors

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorsRulesTest {
    private val t = DoorsKeys.TUNNEL_ID

    private fun app(
        pkg: String,
        system: Boolean = false,
        activities: Int = 1,
        services: Int = 0,
        receivers: Int = 0,
        providers: Int = 0,
        grantUri: Int = 0,
        verified: Int = 0,
        selected: Int = 0,
        browser: Boolean = false,
    ): List<Observation> = buildList {
        fun o(k: String, v: Any) = add(Observation(t, pkg, k, v.toString()))
        o(DoorsKeys.APP_LABEL, pkg)
        o(DoorsKeys.APP_SYSTEM, system)
        o(DoorsKeys.UNPROTECTED_ACTIVITIES, activities)
        o(DoorsKeys.UNPROTECTED_SERVICES, services)
        o(DoorsKeys.UNPROTECTED_RECEIVERS, receivers)
        o(DoorsKeys.UNPROTECTED_PROVIDERS, providers)
        o(DoorsKeys.EXPORTED_UNPROTECTED, activities + services + receivers + providers)
        o(DoorsKeys.PROVIDER_GRANT_URI, grantUri)
        o(DoorsKeys.LINKS_VERIFIED, verified)
        o(DoorsKeys.LINKS_SELECTED, selected)
        if (browser) o(DoorsKeys.HANDLER_BROWSER, DoorsKeys.TRUE)
    }

    private fun first(current: List<Observation>) = RuleContext(t, current, emptyList(), isFirstScan = true)

    private fun next(before: List<Observation>, after: List<Observation>) = RuleContext(t, after, DiffEngine.diff(before, after), isFirstScan = false)

    private fun drafts(ctx: RuleContext, kind: String): Map<String, FindingDraft> =
        DoorsRules.all.flatMap { it.evaluate(ctx) }.filter { it.kind == kind }.associateBy { it.subject }

    @Test
    fun unprotectedExportsScaleWithCountAndSystemFlag() {
        val ctx = first(
            app("com.launcher") +
                app("com.open", activities = 5, services = 3, receivers = 6) +
                app("com.few", activities = 1, receivers = 1) +
                app("com.sys", system = true, activities = 4, services = 4) +
                app("com.sysbig", system = true, activities = 8, providers = 3) +
                app("com.none", activities = 0),
        )
        val d = drafts(ctx, DoorsRules.UNPROTECTED_EXPORTS)
        assertEquals(setOf("com.open", "com.few", "com.sysbig"), d.keys)
        assertEquals(Severity.WARN, d["com.open"]!!.severity)
        assertEquals("14 exported components without a permission (5 activities, 3 services, 6 receivers)", d["com.open"]!!.evidence)
        assertEquals(Severity.NOTICE, d["com.few"]!!.severity)
        assertEquals("2 exported components without a permission (1 activity, 1 receiver)", d["com.few"]!!.evidence)
        assertEquals(Severity.NOTICE, d["com.sysbig"]!!.severity)
        assertEquals("11 exported components without a permission (8 activities, 3 providers)", d["com.sysbig"]!!.evidence)
        assertTrue(d.values.none { it.sticky })
    }

    @Test
    fun grantUriProvidersOnlyForUserApps() {
        val ctx = first(app("com.a", grantUri = 1) + app("com.b", grantUri = 2) + app("com.sys", system = true, grantUri = 3) + app("com.c"))
        val d = drafts(ctx, DoorsRules.EXPORTED_PROVIDER_GRANT_URI)
        assertEquals(setOf("com.a", "com.b"), d.keys)
        assertEquals("1 exported content provider can grant other apps access to its data (grantUriPermissions)", d["com.a"]!!.evidence)
        assertEquals("2 exported content providers can grant other apps access to their data (grantUriPermissions)", d["com.b"]!!.evidence)
        assertEquals(Severity.WARN, d["com.a"]!!.severity)
    }

    @Test
    fun changeRulesAreQuietOnFirstScan() {
        val kinds = DoorsRules.all.flatMap { it.evaluate(first(app("com.a", browser = true, verified = 2))) }.map { it.kind }
        assertTrue(kinds.isEmpty())
    }

    @Test
    fun linksChangedReportsBothCountsInOneFinding() {
        val before = app("com.a", verified = 3, selected = 0) + app("com.b", verified = 1)
        val after = app("com.a", verified = 5, selected = 1) + app("com.b", verified = 1)
        val d = drafts(next(before, after), DoorsRules.LINKS_CHANGED)
        assertEquals(setOf("com.a"), d.keys)
        assertEquals("Chosen link domains 0 → 1; Verified link domains 3 → 5", d["com.a"]!!.evidence)
        assertEquals(Severity.INFO, d["com.a"]!!.severity)
        assertTrue(d["com.a"]!!.sticky)
    }

    @Test
    fun newLinkHandlerFiresWhenBrowserHandlerAppears() {
        val before = app("com.a") + app("com.b", browser = true)
        val after = app("com.a", browser = true) + app("com.b", browser = true) + app("com.new", browser = true)
        val d = drafts(next(before, after), DoorsRules.NEW_LINK_HANDLER)
        assertEquals(setOf("com.a", "com.new"), d.keys)
        assertEquals("Now offers to open http and https links", d["com.a"]!!.evidence)
        assertTrue(d["com.a"]!!.sticky)
        assertEquals(Severity.NOTICE, d["com.a"]!!.severity)
        assertFalse(drafts(next(after, after), DoorsRules.NEW_LINK_HANDLER).isNotEmpty())
    }
}
