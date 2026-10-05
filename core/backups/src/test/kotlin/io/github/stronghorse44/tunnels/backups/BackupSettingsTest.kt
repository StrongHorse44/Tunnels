package io.github.stronghorse44.tunnels.backups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupSettingsTest {
    @Test
    fun roundTrips() {
        val s = BackupSettings(
            thresholdDays = 60,
            drill = LocalDate.of(2026, 7, 1),
            overrides = mapOf("southbound" to false, "lumen" to true),
            seen = setOf("tunnels", "prikey"),
        )
        assertEquals(s, BackupSettings.decode(s.encode()))
    }

    @Test
    fun damagedOrUnknownFallsBackToDefaults() {
        assertEquals(BackupSettings(), BackupSettings.decode(null))
        assertEquals(BackupSettings(), BackupSettings.decode("garbage"))
        val d = BackupSettings.decode("days=17;drill=yesterday;track=nope:on,lumen:maybe;seen=ghost,prikey")
        assertEquals(BackupSettings.DEFAULT_THRESHOLD_DAYS, d.thresholdDays)
        assertEquals(null, d.drill)
        assertTrue(d.overrides.isEmpty())
        assertEquals(setOf("prikey"), d.seen)
    }

    @Test
    fun trackedMeansSeenOrSwitchedOn() {
        val s = BackupSettings(seen = setOf("prikey"))
        assertTrue(s.isTracked("prikey"))
        assertFalse(s.isTracked("lumen"))
        assertTrue(s.isTracked("lumen", foundNow = true))
        assertFalse(s.withTracked("prikey", false).isTracked("prikey", foundNow = true))
        assertTrue(s.withTracked("lumen", true).isTracked("lumen"))
    }

    @Test
    fun theThresholdChoicesIncludeOneDay() {
        assertTrue(1 in BackupSettings.THRESHOLDS)
        assertTrue(BackupSettings.DEFAULT_THRESHOLD_DAYS in BackupSettings.THRESHOLDS)
    }
}
