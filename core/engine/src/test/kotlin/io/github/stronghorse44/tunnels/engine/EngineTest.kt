package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EngineTest {
    private fun obs(subject: String, key: String, value: String) = Observation("perm", subject, key, value)
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun diffFindsAddedRemovedChanged() {
        val before = listOf(obs("a", "CAMERA", "granted"), obs("a", "MIC", "denied"), obs("b", "NET", "granted"))
        val after = listOf(obs("a", "CAMERA", "granted"), obs("a", "MIC", "granted"), obs("c", "NET", "granted"))
        val diff = DiffEngine.diff(before, after)
        assertEquals(3, diff.size)
        assertTrue(diff.any { it is DiffEntry.Changed && it.key.key == "MIC" })
        assertTrue(diff.any { it is DiffEntry.Removed && it.key.subject == "b" })
        assertTrue(diff.any { it is DiffEntry.Added && it.key.subject == "c" })
    }

    @Test
    fun identicalSnapshotsProduceEmptyDiff() {
        val s = listOf(obs("a", "CAMERA", "granted"))
        assertTrue(DiffEngine.diff(s, s).isEmpty())
    }

    @Test
    fun stateFindingsKeepFirstSeenAndClearWhenStateClears() {
        val rule = Rules.perSubject("MIC_GRANTED", Severity.NOTICE) { _, o -> if (o.any { it.key == "MIC" && it.value == "granted" }) "mic granted" else null }
        val actions = { d: io.github.stronghorse44.tunnels.model.FindingDraft -> if (d.subject == "z") emptyList() else listOf(FindingAction.OpenAppDetails(d.subject)) }
        val first = FindingsEngine.derive(
            RuleContext("perm", listOf(obs("a", "MIC", "granted"), obs("z", "MIC", "granted")), emptyList(), true),
            listOf(rule), actions, emptyList(), t0,
        )
        assertEquals(listOf("perm|a|MIC_GRANTED"), first.upserts.map { it.id }) // z dropped: no actions
        val second = FindingsEngine.derive(
            RuleContext("perm", listOf(obs("a", "MIC", "granted")), emptyList(), false),
            listOf(rule), actions, first.upserts, t1,
        )
        assertEquals(t0, second.upserts.single().firstSeen)
        assertEquals(t1, second.upserts.single().lastSeen)
        val third = FindingsEngine.derive(
            RuleContext("perm", listOf(obs("a", "MIC", "denied")), emptyList(), false),
            listOf(rule), actions, second.upserts, t1,
        )
        assertTrue(third.upserts.isEmpty())
        assertEquals(listOf("perm|a|MIC_GRANTED"), third.removals)
    }

    @Test
    fun changeFindingsAreStickyAndSkipFirstScan() {
        val rule = Rules.onAdded("perm:", "PERMISSION_GAINED", Severity.WARN) { "gained ${it.key}" }
        val actions = { d: io.github.stronghorse44.tunnels.model.FindingDraft -> listOf(FindingAction.OpenAppDetails(d.subject)) }
        val added = listOf(DiffEntry.Added(obs("a", "perm:CAMERA", "granted")))
        assertTrue(FindingsEngine.derive(RuleContext("perm", emptyList(), added, true), listOf(rule), actions, emptyList(), t0).upserts.isEmpty())
        val u = FindingsEngine.derive(RuleContext("perm", emptyList(), added, false), listOf(rule), actions, emptyList(), t0)
        val f = u.upserts.single()
        assertTrue(f.sticky)
        // A later scan with no diff keeps the sticky finding instead of removing it.
        val later = FindingsEngine.derive(RuleContext("perm", emptyList(), emptyList(), false), listOf(rule), actions, listOf(f), t1)
        assertTrue(later.removals.isEmpty())
    }

    @Test
    fun retentionKeepsNewestTwelveAndAllPinned() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val snaps = (1..20).map { Snapshot(it.toLong(), base.plusSeconds(it * 60L), pinned = it == 2) }
        val doomed = RetentionPolicy.snapshotsToDelete(snaps)
        assertEquals(7, doomed.size) // 19 unpinned, keep 12
        assertTrue(doomed.none { it.pinned })
        assertTrue(doomed.all { it.id in 1L..8L })
        assertEquals(Finding::class.simpleName, "Finding")
    }
}
