package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxTest {
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")
    private val t1 = Instant.parse("2026-10-02T00:00:00Z")

    private fun f(tunnel: String, severity: Severity, first: Instant, last: Instant = first) =
        Finding(tunnel, "pkg.$tunnel", "K", severity, first, last, "e", emptyList())

    @Test
    fun mostSevereFirstThenMostRecent() {
        val a = f("a", Severity.NOTICE, t0, t1)
        val b = f("b", Severity.CRITICAL, t0)
        val c = f("c", Severity.WARN, t0, t0)
        val d = f("d", Severity.WARN, t0, t1)
        assertEquals(listOf("b", "d", "c", "a"), Inbox.sort(listOf(a, b, c, d)).map { it.tunnelId })
    }

    @Test
    fun newMeansFirstSeenAfterTheLastVisit() {
        val old = f("a", Severity.WARN, t0)
        val fresh = f("b", Severity.NOTICE, t1)
        val visit = Instant.parse("2026-10-01T12:00:00Z")
        assertFalse(Inbox.isNew(old, visit))
        assertTrue(Inbox.isNew(fresh, visit))
        assertFalse("nothing is new on a first visit", Inbox.isNew(fresh, null))
        assertEquals(listOf(fresh), Inbox.filter(listOf(old, fresh), Inbox.Filter.NEW, visit))
        assertEquals(listOf(old), Inbox.filter(listOf(old, fresh), Inbox.Filter.URGENT, visit))
        assertEquals(listOf(fresh), Inbox.filter(listOf(old, fresh), Inbox.Filter.ALL, visit, tunnel = "b"))
        assertEquals(mapOf(Severity.WARN to 1, Severity.NOTICE to 1), Inbox.counts(listOf(old, fresh)))
    }
}
