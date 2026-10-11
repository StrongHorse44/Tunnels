package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHintsTest {
    private fun kind(name: String) = DeviceHints.forName(name)?.kind

    @Test
    fun camerasNameThemselves() {
        for (n in listOf("HD WiFi Cam 1234", "HDWiFiCam", "HDWIFICAM", "MiniCam-77", "IPCam_A1", "Front Doorbell", "SMARTCAM-9F", "GoPro 4410", "Insta360 X4", "My Camera")) {
            assertEquals(n, HintKind.CAMERA, kind(n))
        }
        assertEquals(HintKind.MICROPHONE, kind("Voice Recorder 2"))
        assertEquals(HintKind.MICROPHONE, kind("VOICERECORDER"))
        assertEquals(HintKind.MICROPHONE, kind("Wireless Mic TX"))
        assertEquals(HintKind.SPY, kind("SpyCam"))
        assertEquals(HintKind.SPY, kind("hidden-camera"))
        assertEquals(HintKind.CAMERA_GLASSES, kind("Ray-Ban Meta 0A1B"))
        assertEquals(HintKind.CAMERA_GLASSES, kind("Spectacles 12AB"))
    }

    @Test
    fun ordinaryNamesGiveNoHint() {
        for (n in listOf("Cam's iPhone", "Cam’s Pixel", "Cambridge Guest", "Campus WiFi", "Microsoft Wireless", "Dynamic", "scam", "NETGEAR42", "Galaxy Buds2", "", WifiHeuristics.HIDDEN, "Micro speaker")) {
            assertNull(n, DeviceHints.forName(n))
        }
        assertNull(DeviceHints.forName(null))
    }

    @Test
    fun hintsSayWhyAndNeverClaimProof() {
        val h = DeviceHints.forName("HD WiFi Cam")!!
        assertEquals("name-cam", h.id)
        assertEquals("Its name says \"cam\": it may be a camera.", h.sentence)
        assertTrue(DeviceHints.ID.matches(h.id))
        assertTrue(SurroundingsRules.HINT_NOT_PROOF.contains("not proof"))
    }

    @Test
    fun makersByBluetoothCompanyId() {
        val gopro = DeviceHints.forMakers(listOf(0x004C, 0x02F2))!!
        assertEquals("maker-02f2", gopro.id)
        assertEquals(HintKind.CAMERA, gopro.kind)
        assertNull(DeviceHints.forMakers(listOf(0x004C, 0x0075)))
        // The name says more than the maker, so it wins.
        assertEquals("name-mic", DeviceHints.of("Wireless Mic", listOf(0x02F2))!!.id)
        assertEquals("maker-02f2", DeviceHints.of("", listOf(0x02F2))!!.id)
    }

    @Test
    fun storedIdsReadBackAndUnknownOnesAreDropped() {
        for (n in listOf("HD WiFi Cam", "Voice Recorder", "Ray-Ban Meta", "Insta360 X4")) {
            val h = DeviceHints.forName(n)!!
            assertEquals(h, DeviceHints.byId(h.id))
        }
        assertEquals(DeviceHints.forMakers(listOf(0x02F2)), DeviceHints.byId("maker-02f2"))
        assertNull(DeviceHints.byId("name-unknownword"))
        assertNull(DeviceHints.byId("maker-ffff"))
        assertNull(DeviceHints.byId("NAME CAM"))
        assertNull(DeviceHints.byId(null))
    }
}
