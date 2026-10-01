package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    @Test
    fun bucketsAtTheThresholds() {
        assertEquals(Proximity.NEAR, Proximity.of(-30))
        assertEquals(Proximity.NEAR, Proximity.of(Proximity.NEAR_DBM))
        assertEquals(Proximity.MEDIUM, Proximity.of(Proximity.NEAR_DBM - 1))
        assertEquals(Proximity.MEDIUM, Proximity.of(Proximity.FAR_DBM))
        assertEquals(Proximity.FAR, Proximity.of(Proximity.FAR_DBM - 1))
        assertEquals(Proximity.FAR, Proximity.of(-110))
        assertTrue(Proximity.entries.all { it.label.isNotBlank() && it.hint.isNotBlank() })
    }

    @Test
    fun meterFractionIsClampedAndLinear() {
        assertEquals(0f, RssiMeter.fraction(-120.0))
        assertEquals(0f, RssiMeter.fraction(RssiMeter.WORST_DBM.toDouble()))
        assertEquals(1f, RssiMeter.fraction(RssiMeter.BEST_DBM.toDouble()))
        assertEquals(1f, RssiMeter.fraction(-10.0))
        assertEquals(0.5f, RssiMeter.fraction(-70.0), 1e-6f)
    }

    @Test
    fun pulsesSpeedUpAsTheSignalRises() {
        assertEquals(RssiMeter.SLOWEST_PULSE_MS, RssiMeter.pulseIntervalMs(-100.0))
        assertEquals(RssiMeter.FASTEST_PULSE_MS, RssiMeter.pulseIntervalMs(-40.0))
        val far = RssiMeter.pulseIntervalMs(-85.0)
        val mid = RssiMeter.pulseIntervalMs(-70.0)
        val near = RssiMeter.pulseIntervalMs(-55.0)
        assertTrue(far > mid && mid > near)
        assertTrue(near >= RssiMeter.FASTEST_PULSE_MS && far <= RssiMeter.SLOWEST_PULSE_MS)
    }

    @Test
    fun smootherDampsJumps() {
        val s = RssiSmoother(0.5)
        assertNull(s.value)
        assertEquals(-60.0, s.add(-60), 1e-9)
        assertEquals(-70.0, s.add(-80), 1e-9)
        assertEquals(-65.0, s.add(-60), 1e-9)
        s.reset()
        assertNull(s.value)
        assertEquals(-50.0, s.add(-50), 1e-9)
        // Alpha 1 is a pass-through, alpha outside (0, 1] is refused.
        assertEquals(-77.0, RssiSmoother(1.0).apply { add(-20) }.add(-77), 1e-9)
        assertTrue(runCatching { RssiSmoother(0.0) }.isFailure)
        assertTrue(runCatching { RssiSmoother(1.5) }.isFailure)
    }

    @Test
    fun scanFailuresHaveWords() {
        assertTrue(ScanFailure.describe(ScanFailure.SCANNING_TOO_FREQUENTLY).contains("wait 30 seconds"))
        for (code in 1..6) assertTrue(ScanFailure.describe(code).isNotBlank() && !ScanFailure.describe(code).contains("code"))
        assertEquals("Bluetooth scan failed (code 42).", ScanFailure.describe(42))
    }

    @Test
    fun trendHasADeadband() {
        assertEquals(Trend.STEADY, Trend.of(null, -60.0))
        assertEquals(Trend.STEADY, Trend.of(-60.0, -61.9))
        assertEquals(Trend.STEADY, Trend.of(-60.0, -58.1))
        assertEquals(Trend.WARMER, Trend.of(-60.0, -57.0))
        assertEquals(Trend.COLDER, Trend.of(-60.0, -63.0))
    }
}
