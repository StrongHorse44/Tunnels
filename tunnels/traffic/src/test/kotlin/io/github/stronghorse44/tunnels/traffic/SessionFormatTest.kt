package io.github.stronghorse44.tunnels.traffic

import io.github.stronghorse44.tunnels.dns.SessionTotals
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFormatTest {
    @Test
    fun elapsed() {
        assertEquals("under a minute", SessionFormat.elapsed(0))
        assertEquals("under a minute", SessionFormat.elapsed(59_999))
        assertEquals("1 min", SessionFormat.elapsed(60_000))
        assertEquals("12 min", SessionFormat.elapsed(12 * 60_000L + 30_000))
        assertEquals("1 h 05 min", SessionFormat.elapsed(65 * 60_000L))
        assertEquals("under a minute", SessionFormat.elapsed(-5))
    }

    @Test
    fun remainingMinutesRoundsUpAndNeverGoesNegative() {
        val max = 60 * 60_000L
        assertEquals(60L, SessionFormat.remainingMinutes(0, 0, max))
        assertEquals(59L, SessionFormat.remainingMinutes(0, 60_000, max))
        assertEquals(1L, SessionFormat.remainingMinutes(0, max - 1, max))
        assertEquals(0L, SessionFormat.remainingMinutes(0, max, max))
        assertEquals(0L, SessionFormat.remainingMinutes(0, max + 99_999, max))
    }

    @Test
    fun counters() {
        assertEquals("0 queries · 0 apps · 0 domains", SessionFormat.counters(SessionTotals()))
        assertEquals("1 query · 1 app · 1 domain", SessionFormat.counters(SessionTotals(1, 1, 1)))
        assertEquals(
            "231 queries · 7 apps · 14 domains · 2 trackers · 1 encrypted attempt",
            SessionFormat.counters(SessionTotals(queries = 231, apps = 7, domains = 14, encrypted = 1, trackers = 2)),
        )
        assertEquals(
            "300 queries · 9 apps · 20 domains · 4 trackers · 40 blocked",
            SessionFormat.counters(SessionTotals(queries = 300, apps = 9, domains = 20, trackers = 4, blocked = 40)),
        )
    }
}
