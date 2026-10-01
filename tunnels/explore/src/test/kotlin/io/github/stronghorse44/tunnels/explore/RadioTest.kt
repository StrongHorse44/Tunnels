package io.github.stronghorse44.tunnels.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    @Test
    fun networkTypeNames() {
        assertEquals("unknown", Radio.networkTypeName(0))
        assertEquals("LTE", Radio.networkTypeName(13))
        assertEquals("NR", Radio.networkTypeName(20))
        assertEquals("HSPA+", Radio.networkTypeName(15))
        assertEquals("IWLAN", Radio.networkTypeName(18))
        assertEquals("type 99", Radio.networkTypeName(99))
    }

    @Test
    fun otherNameTables() {
        assertEquals("GSM", Radio.phoneTypeName(1))
        assertEquals("none", Radio.phoneTypeName(0))
        assertEquals("Wi-Fi 6 (802.11ax)", Radio.wifiStandardName(6))
        assertEquals("Wi-Fi 7 (802.11be)", Radio.wifiStandardName(8))
        assertEquals("legacy (802.11a/b/g)", Radio.wifiStandardName(1))
        assertEquals("standard 3", Radio.wifiStandardName(3))
        assertEquals("on", Radio.bluetoothStateName(12))
        assertEquals("off", Radio.bluetoothStateName(10))
        assertEquals("state -1", Radio.bluetoothStateName(-1))
        assertEquals("4 (great)", Radio.signalLevelLabel(4))
        assertEquals("0 (none)", Radio.signalLevelLabel(0))
        assertEquals("7", Radio.signalLevelLabel(7))
    }

    @Test
    fun bandsSignalKindsAndDbm() {
        assertEquals("2.4 GHz", Radio.band(2437))
        assertEquals("5 GHz", Radio.band(5500))
        assertEquals("6 GHz", Radio.band(6115))
        assertEquals("?", Radio.band(-1))
        assertEquals("lte", Radio.signalKind("CellSignalStrengthLte"))
        assertEquals("nr", Radio.signalKind("CellSignalStrengthNr"))
        assertEquals("unknown", Radio.signalKind("CellSignalStrength"))
        assertTrue(Radio.validDbm(-97))
        assertFalse(Radio.validDbm(Int.MAX_VALUE))
        assertFalse(Radio.validDbm(5))
        assertEquals(listOf("cellular", "wifi", "bluetooth", "nfc", "uwb"), Radio.subjects)
    }
}
