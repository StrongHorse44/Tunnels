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

    @Test fun theWindowIsTenMinutesExactly() {
        // Four failures at the start, the fifth exactly ten minutes after the first: the first has left the window.
        repeat(4) { policy.record("/w$it", DecodeStatus.CRASHED) }
        now += FailurePolicy.WINDOW_MS
        policy.record("/w4", DecodeStatus.CRASHED)
        assertFalse(policy.isPaused)

        val tight = FailurePolicy { now }
        val start = now
        repeat(4) { tight.record("/t$it", DecodeStatus.CRASHED) }
        now = start + FailurePolicy.WINDOW_MS - 1
        tight.record("/t4", DecodeStatus.CRASHED)
        assertTrue("one millisecond inside the window pauses", tight.isPaused)
    }

    /** Records one counted failure per file, three minutes apart, so the reader never pauses. */
    private fun fail(key: String) {
        policy.record(key, DecodeStatus.CRASHED)
        now += 3 * minute
    }

    @Test fun aFileNotSeenFor256OtherFilesIsForgotten() {
        fail("/old")
        repeat(FailurePolicy.MAX_FILES) { fail("/other$it") }
        fail("/old")
        assertNull("its first failure was forgotten", policy.refusal("/old"))
        assertFalse(policy.isPaused)
    }

    @Test fun aRefusedFileThatKeepsComingBackIsNotForgotten() {
        fail("/bad")
        fail("/bad")
        assertEquals(DecodeStatus.REFUSED_FILE, policy.refusal("/bad"))
        // Alternating: the refused file is asked about between new files, so it stays the most recently seen.
        repeat(3 * FailurePolicy.MAX_FILES) {
            fail("/new$it")
            assertEquals("after ${it + 1} new files", DecodeStatus.REFUSED_FILE, policy.refusal("/bad"))
        }
        assertFalse(policy.isPaused)
    }
}
