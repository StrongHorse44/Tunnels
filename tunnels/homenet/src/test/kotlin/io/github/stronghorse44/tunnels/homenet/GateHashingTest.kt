package io.github.stronghorse44.tunnels.homenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateHashingTest {
    @Test
    fun normalizesWifiInfoSsids() {
        assertEquals("Home Wi-Fi", GateHashing.normalizeSsid("\"Home Wi-Fi\""))
        assertEquals("Home", GateHashing.normalizeSsid("Home"))
        assertEquals("\"", GateHashing.normalizeSsid("\""))
        assertNull(GateHashing.normalizeSsid(GateHashing.UNKNOWN_SSID))
        assertNull(GateHashing.normalizeSsid("\"\""))
        assertNull(GateHashing.normalizeSsid("   "))
        assertNull(GateHashing.normalizeSsid(null))
    }
}
