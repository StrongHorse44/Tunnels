package io.github.stronghorse44.tunnels.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PresenceTest {
    private val day = LocalDate.of(2026, 10, 11).toEpochDay()

    @Test
    fun presenceRowsArePrunedAfterThirtyDaysWithoutAScanOfTheirNetwork() {
        // Two devices seen on day 0; the network is never scanned again, so only maintenance can forget them (L5).
        val row = Presence.update(Presence.Book.EMPTY, day, listOf("ip:10.0.0.1", "ip:10.0.0.2")).encode()
        assertEquals(row, Presence.prune(row, day + Presence.RETENTION_DAYS))
        assertNull(Presence.prune(row, day + Presence.RETENTION_DAYS + 1))
    }

    @Test
    fun presencePruneForgetsOnlyTheDevicesNotSeenForThirtyDays() {
        var book = Presence.update(Presence.Book.EMPTY, day, listOf("ip:10.0.0.1", "ip:10.0.0.2"))
        book = Presence.update(book, day + 20, listOf("ip:10.0.0.2"))
        val pruned = Presence.prune(book.encode(), day + 31)
        val kept = Presence.Book.parse(pruned)
        assertEquals(setOf("ip:10.0.0.2"), kept.entries.keys)
        assertEquals(book.scanDays, kept.scanDays)
        assertEquals(day + 20, kept.lastScanDay)
    }

    @Test
    fun presencePruneLeavesUnknownRowsAlone() {
        assertEquals("v9;whatever", Presence.prune("v9;whatever", day))
        assertEquals("junk", Presence.prune("junk", day))
        assertEquals("homenet.presence.", Presence.SETTINGS_PREFIX)
    }
}
