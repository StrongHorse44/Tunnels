package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSettingsTest {
    @Test
    fun settingsRoundTrip() {
        val s = WatchSettings(enabled = true, intervalHours = 6, notifyAt = Severity.CRITICAL, extra = setOf("timeline"))
        assertEquals(s, WatchSettings.decode(s.encode()))
    }

    @Test
    fun damagedOrMissingValuesFallBackToDefaults() {
        assertEquals(WatchSettings(), WatchSettings.decode(null))
        val odd = WatchSettings.decode("enabled=true;interval=5;notify=INFO;extra=traffic,timeline;junk")
        assertTrue(odd.enabled)
        assertEquals(WatchPolicy.DEFAULT_INTERVAL_HOURS, odd.intervalHours)
        assertEquals(Severity.WARN, odd.notifyAt)
        assertEquals(setOf("timeline"), odd.extra)
    }

    @Test
    fun statusRoundTripsAndKeepsItsReasonReadable() {
        val s = WatchStatus(lastRunAt = 10, lastAppFilesAt = 5, bootCount = 2, packageSequence = 99, tunnels = 7, added = 1, stored = true, failed = listOf("silicon"), reason = "apps changed")
        assertEquals(s, WatchStatus.decode(s.encode()))
        assertTrue(WatchStatus.decode(null).neverRan)
    }
}
