package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSignaturesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A full Offline Finding payload: type, length 0x19, status, 22 key bytes, key bits, hint. */
    private fun findMySeparated(status: Int = 0x04): ByteArray = bytes(0x12, 0x19, status) + ByteArray(22) { (it + 1).toByte() } + bytes(0x00, 0x00)

    @Test
    fun appleSeparatedAndNearbyStates() {
        val separated = TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to findMySeparated())))!!
        assertEquals(TrackerType.APPLE_FINDMY, separated.type)
        assertEquals(TrackerState.SEPARATED, separated.state)
        assertEquals("mfr:004c", separated.idSource)
        assertEquals(Confidence.HIGH, separated.confidence)
        assertEquals("full", separated.battery)

        val nearby = TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x12, 0x02, 0x00, 0x00))))!!
        assertEquals(TrackerType.APPLE_FINDMY, nearby.type)
        assertEquals(TrackerState.WITH_OWNER, nearby.state)
        assertNull(nearby.battery)
    }

    @Test
    fun appleBatteryFromStatusByte() {
        assertEquals("full", TrackerSignatures.appleBattery(0x04))
        assertEquals("medium", TrackerSignatures.appleBattery(0x44))
        assertEquals("low", TrackerSignatures.appleBattery(0x84))
        assertEquals("critical", TrackerSignatures.appleBattery(0xC4))
        assertEquals("low", TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to findMySeparated(0x80))))!!.battery)
    }

    @Test
    fun otherApplePayloadsAreNotTrackers() {
        // iBeacon (0x02 0x15), Nearby Info (0x10), proximity pairing (0x07), truncated or odd lengths.
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x02, 0x15) + ByteArray(21)))))
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x10, 0x05, 0x01, 0x18, 0x22, 0x33, 0x44)))))
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x07, 0x19) + ByteArray(25)))))
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x12)))))
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x12, 0x19)))))
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to bytes(0x12, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)))))
        // Same payload from another company id is not Apple.
        assertNull(TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x0075 to findMySeparated()))))
    }

    @Test
    fun serviceUuidTrackers() {
        fun svc(short16: Int) = Advertisement(serviceUuids = listOf(BleUuid.full(short16)))
        assertEquals(TrackerType.SAMSUNG_SMARTTAG, TrackerSignatures.match(svc(0xFD5A))!!.type)
        assertEquals(Confidence.MEDIUM, TrackerSignatures.match(svc(0xFD5A))!!.confidence)
        assertEquals(TrackerType.TILE, TrackerSignatures.match(svc(0xFEED))!!.type)
        assertEquals("svc:feed", TrackerSignatures.match(svc(0xFEED))!!.idSource)
        assertEquals(TrackerType.CHIPOLO, TrackerSignatures.match(svc(0xFE33))!!.type)
        // Service data alone (no UUID list) also counts.
        assertEquals(TrackerType.TILE, TrackerSignatures.match(Advertisement(serviceData = mapOf(BleUuid.full(0xFEED) to bytes(0x01))))!!.type)
        // Upper-case UUID strings are tolerated.
        assertEquals(TrackerType.TILE, TrackerSignatures.match(Advertisement(serviceUuids = listOf("0000FEED-0000-1000-8000-00805F9B34FB")))!!.type)
        // Ordinary services (battery, heart rate) and Eddystone are not trackers.
        assertNull(TrackerSignatures.match(svc(0x180F)))
        assertNull(TrackerSignatures.match(svc(0x180D)))
        assertNull(TrackerSignatures.match(Advertisement(serviceData = mapOf(BleUuid.full(TrackerSignatures.EDDYSTONE_SERVICE) to bytes(0x00, 0xE0) + ByteArray(18)))))
        assertNull(TrackerSignatures.match(Advertisement()))
    }

    @Test
    fun googleFindMyDeviceFrames() {
        val fastPair = BleUuid.full(0xFE2C)
        val eid20 = Advertisement(serviceData = mapOf(fastPair to bytes(0x40) + ByteArray(20) + bytes(0x00)))
        val eid32 = Advertisement(serviceData = mapOf(fastPair to bytes(0x41) + ByteArray(32) + bytes(0x00)))
        assertEquals(TrackerType.GOOGLE_FMDN, TrackerSignatures.match(eid20)!!.type)
        assertEquals(TrackerType.GOOGLE_FMDN, TrackerSignatures.match(eid32)!!.type)
        assertEquals(Confidence.MEDIUM, TrackerSignatures.match(eid20)!!.confidence)
        // A plain Fast Pair model id (3 bytes) and a too-short 0x41 frame are not FMDN.
        assertNull(TrackerSignatures.match(Advertisement(serviceData = mapOf(fastPair to bytes(0x40, 0x12, 0x34)))))
        assertNull(TrackerSignatures.match(Advertisement(serviceData = mapOf(fastPair to bytes(0x41) + ByteArray(20)))))
        assertNull(TrackerSignatures.match(Advertisement(serviceData = mapOf(fastPair to bytes(0x00) + ByteArray(24)))))
        // The UUID alone, without frame data, is just a Fast Pair device.
        assertNull(TrackerSignatures.match(Advertisement(serviceUuids = listOf(fastPair))))
    }

    @Test
    fun catalogIsHonestAboutConfidence() {
        assertEquals(TrackerType.entries.toSet(), TrackerSignatures.all.map { it.type }.toSet())
        val pebblebee = TrackerSignatures.of(TrackerType.PEBBLEBEE)
        assertFalse(pebblebee.active)
        assertEquals(Confidence.LOW, pebblebee.confidence)
        assertTrue(TrackerSignatures.all.all { it.basis.isNotBlank() })
        assertTrue(TrackerSignatures.all.filter { it.active }.all { it.confidence != Confidence.LOW })
    }

    @Test
    fun uuidAliases() {
        assertEquals(0xFD5A, BleUuid.short16("0000fd5a-0000-1000-8000-00805f9b34fb"))
        assertEquals(0xFEED, BleUuid.short16("0000FEED-0000-1000-8000-00805F9B34FB"))
        assertNull(BleUuid.short16("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertNull(BleUuid.short16("fd5a"))
        assertEquals("0000fd5a-0000-1000-8000-00805f9b34fb", BleUuid.full(0xFD5A))
    }
}
