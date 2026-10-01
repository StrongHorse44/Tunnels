package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.ble.SurroundingsKeys.MuteRow
import org.junit.Assert.assertEquals
import org.junit.Test

class MuteLedgerTest {
    private val day = SurroundingsKeys.DAY_MS
    private val now = 100 * day
    private val family = "tracker:findmy"
    private val tag = "tracker:findmy:ab12cd34"

    @Test
    fun muteIsActiveUntilItExpires() {
        val rows = listOf(MuteRow(SurroundingsKeys.EVENT_MUTE, family, now - 2 * day))
        assertEquals(setOf(family), SurroundingsKeys.activeMutes(rows, now))
        val old = listOf(MuteRow(SurroundingsKeys.EVENT_MUTE, family, now - (SurroundingsKeys.MUTE_DAYS + 1) * day))
        assertEquals(emptySet<String>(), SurroundingsKeys.activeMutes(old, now))
    }

    @Test
    fun unmuteCancelsEarlierMutesOnlyForItsSubject() {
        val rows = listOf(
            MuteRow(SurroundingsKeys.EVENT_MUTE, family, now - 3 * day),
            MuteRow(SurroundingsKeys.EVENT_MUTE, family, now - 2 * day),
            MuteRow(SurroundingsKeys.EVENT_MUTE, tag, now - 2 * day),
            MuteRow(SurroundingsKeys.EVENT_UNMUTE, family, now - day),
        )
        assertEquals(setOf(tag), SurroundingsKeys.activeMutes(rows, now))
    }

    @Test
    fun muteAfterUnmuteWinsAndOtherEventsAreIgnored() {
        val rows = listOf(
            MuteRow(SurroundingsKeys.EVENT_UNMUTE, family, now - 2 * day),
            MuteRow(SurroundingsKeys.EVENT_MUTE, family, now - day),
            MuteRow(SurroundingsKeys.EVENT_SIGHTING, tag, now),
        )
        assertEquals(setOf(family), SurroundingsKeys.activeMutes(rows, now))
    }
}
