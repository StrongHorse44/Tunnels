package io.github.stronghorse44.tunnels.homenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun hashesAreStableAndNotReversibleByEye() {
        val a = GateHashing.ssidHash("Home")
        assertEquals(a, GateHashing.ssidHash("Home"))
        assertNotEquals(a, GateHashing.ssidHash("home"))
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(!a.contains("Home"))
        assertEquals(GateHashing.PREFIX_LENGTH, GateHashing.prefix(a).length)
        assertTrue(a.startsWith(GateHashing.prefix(a)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", GateHashing.sha256Hex(""))
    }
}
