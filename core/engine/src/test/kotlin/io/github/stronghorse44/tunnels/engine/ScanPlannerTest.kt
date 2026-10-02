package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.SourceData
import io.github.stronghorse44.tunnels.model.TunnelModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanPlannerTest {
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")
    private val t1 = Instant.parse("2026-09-02T00:00:00Z")

    private open class Sensor(override val id: String) : TunnelModule {
        override val requiredPermissions = emptyList<PermissionSpec>()
        override suspend fun scan(progress: ScanProgress) = emptyList<Observation>()
        override val rules: List<FindingRule> = listOf(
            Rules.perSubject("MIC_GRANTED", Severity.WARN) { _, o -> if (o.any { it.key == "mic" && it.value == "granted" }) "mic granted" else null },
            Rules.onChange("CHANGED", Severity.NOTICE) { "changed ${it.key.key}" },
        )
        override fun actionsFor(draft: FindingDraft) = listOf<FindingAction>(FindingAction.OpenAppDetails(draft.subject))
    }

    /** Joins "perm" and "apk": an app with the mic granted and a tracker SDK. Records what it was given. */
    private class Joiner : DerivedTunnel {
        override val id = "join"
        override val sources = setOf("perm", "apk")
        override val requiredPermissions = emptyList<PermissionSpec>()
        var lastInput: DerivedInput? = null
        override fun derive(input: DerivedInput): List<Observation> {
            lastInput = input
            val mic = input.sources["perm"]?.observations.orEmpty().filter { it.key == "mic" && it.value == "granted" }.map { it.subject }.toSet()
            val sdk = input.sources["apk"]?.observations.orEmpty().filter { it.key == "sdk" }.map { it.subject }.toSet()
            return (mic intersect sdk).map { Observation(id, it, "micAndSdk", "true") } + Observation("perm", "x", "stray", "dropped")
        }
        override val rules: List<FindingRule> = listOf(Rules.perSubject("MIC_AND_SDK", Severity.CRITICAL) { _, o -> if (o.isNotEmpty()) "both" else null })
        override fun actionsFor(draft: FindingDraft) = listOf<FindingAction>(FindingAction.RequestUninstall(draft.subject))
    }

    private fun o(tunnel: String, subject: String, key: String, value: String) = Observation(tunnel, subject, key, value)

    @Test
    fun firstScanStoresABaselineWithoutChangeFindings() {
        val perm = Sensor("perm")
        val plan = ScanPlanner.plan(listOf(perm to listOf(o("perm", "a", "mic", "granted"))), emptyList(), emptyMap(), emptyList(), emptySet(), t0)
        assertTrue(plan.observationsChanged)
        assertEquals(listOf("perm|a|MIC_GRANTED"), plan.added.map { it.id })
        assertEquals(1, plan.observations.size)
        assertEquals(listOf("perm"), plan.tunnels)
    }

    @Test
    fun anUnchangedRescanChangesNothing() {
        val perm = Sensor("perm")
        val current = listOf(o("perm", "a", "mic", "granted"))
        val first = ScanPlanner.plan(listOf(perm to current), emptyList(), emptyMap(), emptyList(), emptySet(), t0)
        val second = ScanPlanner.plan(listOf(perm to current), emptyList(), mapOf("perm" to SourceData(current, t0)), first.upserts, emptySet(), t1)
        assertFalse(second.observationsChanged)
        assertFalse(second.findingsChanged)
        assertEquals(t0, second.upserts.single().firstSeen)
    }

    @Test
    fun aChangeInClockDrivenKeysAloneIsNotAChange() {
        val clock = object : Sensor("silicon") {
            override val volatileKeys = setOf("patchAgeDays")
        }
        val before = listOf(o("silicon", "device", "patchAgeDays", "10"), o("silicon", "device", "boot", "verified"))
        val aged = listOf(o("silicon", "device", "patchAgeDays", "11"), o("silicon", "device", "boot", "verified"))
        val quiet = ScanPlanner.plan(listOf(clock to aged), emptyList(), mapOf("silicon" to SourceData(before, t0)), emptyList(), emptySet(), t1)
        assertFalse(quiet.observationsChanged)
        val unlocked = listOf(o("silicon", "device", "patchAgeDays", "11"), o("silicon", "device", "boot", "unverified"))
        assertTrue(ScanPlanner.plan(listOf(clock to unlocked), emptyList(), mapOf("silicon" to SourceData(before, t0)), emptyList(), emptySet(), t1).observationsChanged)
        // A first scan always counts, whatever its keys.
        val onlyClock = listOf(o("silicon", "device", "patchAgeDays", "11"))
        assertTrue(ScanPlanner.plan(listOf(clock to onlyClock), emptyList(), emptyMap(), emptyList(), emptySet(), t1).observationsChanged)
    }

    @Test
    fun aChangeAddsAStickyFindingAndClearsAState() {
        val perm = Sensor("perm")
        val before = listOf(o("perm", "a", "mic", "granted"))
        val first = ScanPlanner.plan(listOf(perm to before), emptyList(), emptyMap(), emptyList(), emptySet(), t0)
        val after = listOf(o("perm", "a", "mic", "denied"))
        val plan = ScanPlanner.plan(listOf(perm to after), emptyList(), mapOf("perm" to SourceData(before, t0)), first.upserts, emptySet(), t1)
        assertTrue(plan.observationsChanged)
        assertEquals(listOf("perm|a|CHANGED"), plan.added.map { it.id })
        assertEquals(listOf("perm|a|MIC_GRANTED"), plan.removals)
        assertTrue(plan.findingsChanged)
    }

    @Test
    fun aDerivedTunnelJoinsFreshAndStoredSourcesInTheSameScan() {
        val perm = Sensor("perm")
        val join = Joiner()
        val apkStored = SourceData(listOf(o("apk", "a", "sdk", "ads"), o("apk", "b", "sdk", "ads")), t0)
        val plan = ScanPlanner.plan(
            scanned = listOf(perm to listOf(o("perm", "a", "mic", "granted"), o("perm", "b", "mic", "denied"))),
            derived = listOf(join),
            previous = mapOf("apk" to apkStored),
            existing = emptyList(), dismissed = emptySet(), now = t1,
        )
        val input = join.lastInput!!
        assertEquals("fresh perm data is stamped now", t1, input.sources.getValue("perm").takenAt)
        assertEquals("apk comes from its stored snapshot", t0, input.sources.getValue("apk").takenAt)
        assertEquals(listOf("perm", "join"), plan.tunnels)
        assertEquals("stray rows under another tunnel are dropped", listOf(o("join", "a", "micAndSdk", "true")), plan.observations.filter { it.tunnelId == "join" })
        assertFalse(plan.observations.any { it.key == "stray" })
        assertTrue(plan.added.any { it.id == "join|a|MIC_AND_SDK" })
        // The derived tunnel saw the finding the perm scan raised a moment earlier, without actions.
        assertEquals(listOf("perm|a|MIC_GRANTED"), input.findings.map { it.id })
        assertTrue(input.findings.all { it.actions.isEmpty() })
    }

    @Test
    fun dismissedAndClearedFindingsAreHiddenFromDerivedTunnels() {
        val perm = Sensor("perm")
        val join = Joiner()
        fun finding(subject: String, kind: String, sticky: Boolean) = Finding("perm", subject, kind, Severity.WARN, t0, t0, "e", emptyList(), sticky)
        val dismissed = finding("a", "CHANGED", sticky = true)
        val clearing = finding("b", "MIC_GRANTED", sticky = false)
        val stays = finding("c", "CHANGED", sticky = true)
        val current = listOf(o("perm", "a", "mic", "denied"))
        ScanPlanner.plan(
            listOf(perm to current), listOf(join), mapOf("perm" to SourceData(current, t0)),
            listOf(dismissed, clearing, stays), setOf(dismissed.id), t1,
        )
        assertEquals(listOf(stays.id), join.lastInput!!.findings.map { it.id })
    }

    @Test
    fun aDerivedTunnelThatThrowsIsReportedAndSkipped() {
        val perm = Sensor("perm")
        val broken = object : DerivedTunnel {
            override val id = "broken"
            override val sources = setOf("perm")
            override val requiredPermissions = emptyList<PermissionSpec>()
            override fun derive(input: DerivedInput): List<Observation> = error("bad join")
            override val rules = emptyList<FindingRule>()
            override fun actionsFor(draft: FindingDraft) = emptyList<FindingAction>()
        }
        val plan = ScanPlanner.plan(listOf(perm to listOf(o("perm", "a", "mic", "granted"))), listOf(broken), emptyMap(), emptyList(), emptySet(), t0)
        assertEquals("bad join", plan.failures["broken"])
        assertEquals(listOf("perm"), plan.tunnels)
    }

    @Test
    fun aDerivedTunnelWithNothingToJoinStoresNothing() {
        val join = Joiner()
        val plan = ScanPlanner.plan(emptyList(), listOf(join), emptyMap(), emptyList(), emptySet(), t0)
        assertFalse(plan.observationsChanged)
        assertTrue(plan.observations.isEmpty())
        assertTrue(join.lastInput!!.sources.isEmpty())
    }
}
