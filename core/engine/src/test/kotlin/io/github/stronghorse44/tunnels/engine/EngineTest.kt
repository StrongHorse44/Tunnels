package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EngineTest {
    private fun obs(subject: String, key: String, value: String) = Observation("perm", subject, key, value)

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
    fun findingsKeepFirstSeenAndDropActionless() {
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val t1 = Instant.parse("2026-01-02T00:00:00Z")
        val rule = FindingRule { e ->
            if (e is DiffEntry.Changed && e.after.value == "granted") {
                FindingDraft(e.key.tunnelId, e.key.subject, "PERMISSION_GAINED", Severity.NOTICE, e.key.key)
            } else null
        }
        val diff = DiffEngine.diff(
            listOf(obs("a", "MIC", "denied"), obs("z", "MIC", "denied")),
            listOf(obs("a", "MIC", "granted"), obs("z", "MIC", "granted")),
        )
        val existingId = Finding.findingId("perm", "a", "PERMISSION_GAINED")
        val existing = mapOf(existingId to Finding("perm", "a", "PERMISSION_GAINED", Severity.NOTICE, t0, t0, "", emptyList()))
        val findings = FindingsEngine.derive(
            diff, listOf(rule),
            actionsFor = { d -> if (d.subject == "z") emptyList() else listOf(FindingAction.OpenAppDetails(d.subject)) },
            existing = existing, now = t1,
        )
        assertEquals(1, findings.size)
        assertEquals(t0, findings[0].firstSeen)
        assertEquals(t1, findings[0].lastSeen)
    }

    @Test
    fun retentionKeepsNewestTwelveAndAllPinned() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val snaps = (1..20).map { Snapshot(it.toLong(), base.plusSeconds(it * 60L), pinned = it == 2) }
        val doomed = RetentionPolicy.snapshotsToDelete(snaps)
        assertEquals(7, doomed.size) // 19 unpinned, keep 12
        assertTrue(doomed.none { it.pinned })
        assertTrue(doomed.all { it.id in 1L..8L })
    }
}
