package io.github.stronghorse44.tunnels.syspkg

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SysPkgRulesTest {
    private val t = SysPkgKeys.TUNNEL_ID

    /** Builds a package's observations the way the tunnel does, deriving known/category/namespace from the knowledge base. */
    private fun pkg(
        name: String,
        enabled: String = SysPkgKeys.ENABLED_VALUE,
        version: String = "15 (35)",
        privileged: Boolean = false,
        launcher: Boolean = false,
        updated: Boolean = false,
    ): List<Observation> = buildList {
        val known = SystemPackageKb.lookup(name)
        add(Observation(t, name, SysPkgKeys.LABEL, name.substringAfterLast('.')))
        add(Observation(t, name, SysPkgKeys.ENABLED, enabled))
        add(Observation(t, name, SysPkgKeys.UPDATED, updated.toString()))
        add(Observation(t, name, SysPkgKeys.VERSION, version))
        add(Observation(t, name, SysPkgKeys.KNOWN, (known != null).toString()))
        add(Observation(t, name, SysPkgKeys.CATEGORY, known?.category?.label ?: SysPkgKeys.UNKNOWN_CATEGORY))
        add(Observation(t, name, SysPkgKeys.NAMESPACE, SystemPackageKb.namespaceOf(name).label))
        add(Observation(t, name, SysPkgKeys.PRIVILEGED, privileged.toString()))
        add(Observation(t, name, SysPkgKeys.HAS_LAUNCHER, launcher.toString()))
        if (known != null) {
            add(Observation(t, name, SysPkgKeys.PURPOSE, known.purpose))
            add(Observation(t, name, SysPkgKeys.DISABLE_RISK, known.disableRisk.label))
        }
    }

    private fun summary(total: Int, disabled: Int = 0, unknown: Int = 0, skipped: Int? = null) = buildList {
        add(Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.TOTAL, total.toString()))
        add(Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.DISABLED, disabled.toString()))
        add(Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.UNKNOWN, unknown.toString()))
        skipped?.let { add(Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.SKIPPED, it.toString())) }
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return SysPkgRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun unknownOnlyForOtherNamespace() {
        val current = pkg("com.android.systemui") + // known aosp
            pkg("com.android.notinkb") + // unknown but aosp namespace
            pkg("app.grapheneos.notinkb") + // unknown grapheneos namespace
            pkg("com.google.android.notinkb") + // unknown google namespace
            pkg("com.qualcomm.qti.telephonyservice") + // unknown other
            pkg("com.shannon.imsservice") + // unknown other
            summary(6, unknown = 5)
        val drafts = evaluate(current).of(SysPkgRules.UNKNOWN_SYSTEM_PACKAGE)
        assertEquals(setOf("com.qualcomm.qti.telephonyservice", "com.shannon.imsservice"), drafts.map { it.subject }.toSet())
        val d = drafts.first()
        assertEquals(Severity.INFO, d.severity)
        assertEquals(SysPkgRules.UNKNOWN_EVIDENCE, d.evidence)
        assertTrue("state rule, not sticky", drafts.none { it.sticky })
        // The summary subject is never a finding of this kind.
        assertTrue(drafts.none { it.subject == SysPkgKeys.SUMMARY })
        // It also fires on the first scan, since it describes state.
        assertEquals(2, evaluate(current).of(SysPkgRules.UNKNOWN_SYSTEM_PACKAGE).size)
    }

    @Test
    fun addedPackageIsStickyWarnAfterFirstScan() {
        val before = pkg("com.android.systemui") + summary(1)
        val after = before + pkg("com.vendor.newthing")
        assertTrue(evaluate(after).of(SysPkgRules.SYSTEM_PACKAGE_ADDED).isEmpty())
        assertTrue(evaluate(after, after).of(SysPkgRules.SYSTEM_PACKAGE_ADDED).isEmpty())
        val drafts = evaluate(after, before).of(SysPkgRules.SYSTEM_PACKAGE_ADDED)
        assertEquals(listOf("com.vendor.newthing"), drafts.map { it.subject })
        val d = drafts.single()
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("System package \"newthing\" appeared since the last scan."))
        // A removed package produces nothing here.
        assertTrue(evaluate(before, after).of(SysPkgRules.SYSTEM_PACKAGE_ADDED).isEmpty())
    }

    @Test
    fun enabledStateChangeIsStickyNotice() {
        val before = pkg("com.android.egg") + pkg("com.android.stk") + summary(2)
        val after = pkg("com.android.egg", enabled = SysPkgKeys.DISABLED_USER_VALUE) + pkg("com.android.stk") + summary(2, disabled = 1)
        assertTrue(evaluate(after).of(SysPkgRules.ENABLED_STATE_CHANGED).isEmpty())
        val drafts = evaluate(after, before).of(SysPkgRules.ENABLED_STATE_CHANGED)
        assertEquals(listOf("com.android.egg"), drafts.map { it.subject })
        val d = drafts.single()
        assertEquals(Severity.NOTICE, d.severity)
        assertTrue(d.sticky)
        assertEquals("Was enabled, now disabled by the user.", d.evidence)
        // Re-enabling is a change too, in the other direction.
        val back = evaluate(before, after).of(SysPkgRules.ENABLED_STATE_CHANGED).single()
        assertEquals("Was disabled by the user, now enabled.", back.evidence)
        // The summary's disabled count changing is not an enabled-state change.
        assertTrue(drafts.none { it.subject == SysPkgKeys.SUMMARY })
    }

    @Test
    fun osUpdateIsOneSummaryFindingNotHundreds() {
        val names = (1..30).map { "com.android.pkg$it" }
        val before = names.flatMap { pkg(it, version = "15 (35)") } + summary(30)
        val afterFew = names.take(20).flatMap { pkg(it, version = "16 (36)") } + names.drop(20).flatMap { pkg(it, version = "15 (35)") } + summary(30)
        val afterMany = names.flatMap { pkg(it, version = "16 (36)") } + summary(30)

        assertTrue("first scan never fires", evaluate(afterMany).of(SysPkgRules.OS_UPDATE_DETECTED).isEmpty())
        assertTrue("exactly the threshold does not fire", evaluate(afterFew, before).of(SysPkgRules.OS_UPDATE_DETECTED).isEmpty())
        val drafts = evaluate(afterMany, before)
        val d = drafts.of(SysPkgRules.OS_UPDATE_DETECTED).single()
        assertEquals(SysPkgKeys.SUMMARY, d.subject)
        assertEquals(Severity.INFO, d.severity)
        assertTrue(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("30 system packages changed version since the last scan"))
        // No per-package finding of any kind came out of the version bump.
        assertEquals(listOf(SysPkgRules.OS_UPDATE_DETECTED), drafts.map { it.kind })
    }

    @Test
    fun ruleOrderAndCount() {
        assertEquals(4, SysPkgRules.all.size)
        assertEquals(20, SysPkgRules.OS_UPDATE_THRESHOLD)
    }
}
