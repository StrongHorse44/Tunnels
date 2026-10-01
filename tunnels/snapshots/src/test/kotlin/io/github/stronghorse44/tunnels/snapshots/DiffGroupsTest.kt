package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DiffGroupsTest {
    private fun obs(tunnel: String, subject: String, key: String, value: String) = Observation(tunnel, subject, key, value)

    @Test
    fun groupsByTunnelThenSubjectWithCounts() {
        val before = listOf(
            obs("permissions", "com.b", "perm:CAMERA", "denied"),
            obs("permissions", "com.a", "perm:MIC", "granted"),
            obs("doors", "com.a", "exported", "2"),
        )
        val after = listOf(
            obs("permissions", "com.b", "perm:CAMERA", "granted"),   // changed
            obs("permissions", "com.a", "perm:LOCATION", "granted"), // added, MIC removed
            obs("doors", "com.a", "exported", "2"),                  // same
        )
        val report = DiffGroups.build(DiffEngine.diff(before, after))

        assertEquals(DiffCounts(added = 1, removed = 1, changed = 1), report.counts)
        assertEquals(listOf("permissions"), report.tunnels.map { it.tunnelId })
        val perms = report.tunnels.single()
        assertEquals(listOf("com.a", "com.b"), perms.subjects.map { it.subject })
        assertEquals(DiffCounts(1, 1, 0), perms.subjects[0].counts)
        assertTrue(perms.subjects[1].entries.single() is DiffEntry.Changed)
        assertEquals(0, report.truncatedSubjects)
    }

    @Test
    fun emptyDiffIsEmpty() {
        val report = DiffGroups.build(emptyList())
        assertTrue(report.isEmpty)
        assertTrue(report.tunnels.isEmpty())
    }

    @Test
    fun capsEntriesPerSubjectButKeepsCounts() {
        val after = (1..100).map { obs("t", "s", "k%03d".format(it), "v") }
        val report = DiffGroups.build(DiffEngine.diff(emptyList(), after), perSubject = 10)
        val subject = report.tunnels.single().subjects.single()
        assertEquals(10, subject.entries.size)
        assertEquals(90, subject.hidden)
        assertEquals(100, subject.counts.added)
        assertEquals(100, report.counts.added)
        assertEquals("k001", subject.entries.first().key.key)
    }

    @Test
    fun capsSubjectsOverall() {
        val after = (1..50).map { obs(if (it <= 25) "a" else "b", "s$it", "k", "v") }
        val report = DiffGroups.build(DiffEngine.diff(emptyList(), after), maxSubjects = 30)
        assertEquals(20, report.truncatedSubjects)
        assertEquals(30, report.tunnels.sumOf { it.subjects.size })
        assertEquals(25, report.tunnels.first { it.tunnelId == "b" }.counts.added) // counts stay complete
        assertEquals(50, report.counts.added)
    }

    @Test
    fun countsAdd() {
        assertEquals(DiffCounts(3, 5, 7), DiffCounts(1, 2, 3) + DiffCounts(2, 3, 4))
        assertEquals(15, (DiffCounts(1, 2, 3) + DiffCounts(2, 3, 4)).total)
    }

    @Test
    fun exportFileNameUsesCompactDate() {
        assertEquals("tunnels-snapshots-20261001.tsnap", exportFileName(LocalDate.of(2026, 10, 1)))
    }
}
