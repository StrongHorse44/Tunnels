package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailurePolicyTest {
    private var now = 1_000_000L
    private val policy = FailurePolicy { now }
    private val minute = 60_000L

    @Test fun twoCountedFailuresRefuseThatFileOnly() {
        policy.record("/staged/a.webp", DecodeStatus.CRASHED)
        assertNull(policy.refusal("/staged/a.webp"))
        policy.record("/staged/a.webp", DecodeStatus.TIMEOUT)
        assertEquals(DecodeStatus.REFUSED_FILE, policy.refusal("/staged/a.webp"))
        assertNull(policy.refusal("/staged/b.webp"))
    }

    @Test fun aBadReplyCounts() {
        policy.record("/a", DecodeStatus.BAD_REPLY)
        policy.record("/a", DecodeStatus.BAD_REPLY)
        assertEquals(DecodeStatus.REFUSED_FILE, policy.refusal("/a"))
    }

    @Test fun ordinaryOutcomesNeverCount() {
        val never = listOf(
            DecodeStatus.OK, DecodeStatus.DAMAGED, DecodeStatus.UNSUPPORTED, DecodeStatus.TOO_LARGE, DecodeStatus.PASSWORD,
            DecodeStatus.CANCELLED, DecodeStatus.BUSY, DecodeStatus.UNAVAILABLE, DecodeStatus.BAD_REQUEST, DecodeStatus.INTERNAL,
            DecodeStatus.REFUSED_FILE, DecodeStatus.PAUSED,
        )
        repeat(10) { never.forEach { s -> policy.record("/a", s) } }
        assertNull(policy.refusal("/a"))
        assertFalse(policy.isPaused)
        assertEquals(setOf(DecodeStatus.CRASHED, DecodeStatus.TIMEOUT, DecodeStatus.BAD_REPLY), DecodeStatus.entries.filter { it.counts }.toSet())
    }

    @Test fun fiveCountedFailuresWithinTenMinutesPauseTheReader() {
        for (i in 1..4) {
            policy.record("/f$i", DecodeStatus.CRASHED)
            now += 2 * minute
        }
        assertNull(policy.refusal("/other"))
        policy.record("/f5", DecodeStatus.TIMEOUT)
        assertTrue(policy.isPaused)
        assertEquals(DecodeStatus.PAUSED, policy.refusal("/other"))
        // Paused until the process restarts: time alone does not lift it.
        now += 24 * 60 * minute
        assertEquals(DecodeStatus.PAUSED, policy.refusal("/other"))
    }

    @Test fun fiveSpreadOverElevenMinutesDoNotPause() {
        for (i in 1..5) {
            policy.record("/f$i", DecodeStatus.CRASHED)
            if (i < 5) now += 11 * minute / 4
        }
        assertFalse(policy.isPaused)
        assertNull(policy.refusal("/f6"))
    }

    @Test fun pausedWinsOverARefusedFile() {
        repeat(5) { policy.record("/a", DecodeStatus.CRASHED) }
        assertEquals(DecodeStatus.PAUSED, policy.refusal("/a"))
    }
}
