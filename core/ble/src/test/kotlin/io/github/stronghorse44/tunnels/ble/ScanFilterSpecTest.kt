package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background monitor scans with hardware filters (an unfiltered scan is suspended while the screen is
 * off). These tests prove the filter list lets through every advertisement the active signatures match,
 * and nothing ordinary.
 */
class ScanFilterSpecTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun findMySeparated(status: Int = 0x04): ByteArray = bytes(0x12, 0x19, status) + ByteArray(22) { (it + 1).toByte() } + bytes(0x00, 0x00)
    private val fastPair = BleUuid.full(TrackerSignatures.GOOGLE_FAST_PAIR_SERVICE)

    /** One matching advertisement per way each active signature can be met. */
    private val trackerAds: Map<String, Advertisement> = mapOf(
        "apple separated" to Advertisement(manufacturerData = mapOf(0x004C to findMySeparated())),
        "apple separated low battery" to Advertisement(manufacturerData = mapOf(0x004C to findMySeparated(0x80))),
        "apple nearby" to Advertisement(manufacturerData = mapOf(0x004C to bytes(0x12, 0x02, 0x00, 0x00))),
        "samsung uuid" to Advertisement(serviceUuids = listOf(BleUuid.full(0xFD5A))),
        "samsung service data" to Advertisement(serviceData = mapOf(BleUuid.full(0xFD5A) to bytes(0x13, 0x01))),
        "tile uuid" to Advertisement(serviceUuids = listOf(BleUuid.full(0xFEED))),
        "tile service data" to Advertisement(serviceData = mapOf(BleUuid.full(0xFEED) to bytes(0x01))),
        "tile upper-case uuid" to Advertisement(serviceUuids = listOf("0000FEED-0000-1000-8000-00805F9B34FB")),
        "chipolo uuid" to Advertisement(serviceUuids = listOf(BleUuid.full(0xFE33))),
        "chipolo service data" to Advertisement(serviceData = mapOf(BleUuid.full(0xFE33) to bytes(0x00))),
        "fmdn 20-byte eid" to Advertisement(serviceData = mapOf(fastPair to bytes(0x40) + ByteArray(20) + bytes(0x00))),
        "fmdn 32-byte eid" to Advertisement(serviceData = mapOf(fastPair to bytes(0x41) + ByteArray(32) + bytes(0x00))),
    )

    private val ordinaryAds: Map<String, Advertisement> = mapOf(
        "empty" to Advertisement(),
        "ibeacon" to Advertisement(manufacturerData = mapOf(0x004C to bytes(0x02, 0x15) + ByteArray(21))),
        "apple nearby info" to Advertisement(manufacturerData = mapOf(0x004C to bytes(0x10, 0x05, 0x01, 0x18, 0x22, 0x33, 0x44))),
        "apple proximity pairing" to Advertisement(manufacturerData = mapOf(0x004C to bytes(0x07, 0x19) + ByteArray(25))),
        "other company, findmy-shaped" to Advertisement(manufacturerData = mapOf(0x0075 to findMySeparated())),
        "battery service" to Advertisement(serviceUuids = listOf(BleUuid.full(0x180F))),
        "heart rate" to Advertisement(serviceUuids = listOf(BleUuid.full(0x180D))),
        "eddystone" to Advertisement(serviceData = mapOf(BleUuid.full(TrackerSignatures.EDDYSTONE_SERVICE) to bytes(0x00, 0xE0) + ByteArray(18))),
        "fast pair model id" to Advertisement(serviceData = mapOf(fastPair to bytes(0x00, 0x12, 0x34))),
        "fast pair uuid only" to Advertisement(serviceUuids = listOf(fastPair)),
        "vendor 128-bit service" to Advertisement(serviceUuids = listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")),
    )

    @Test
    fun everyActiveSignatureHasFiltersAndInactiveOnesDoNot() {
        for (sig in TrackerSignatures.all) {
            if (sig.active) assertTrue(sig.type.name, sig.filters.isNotEmpty()) else assertTrue(sig.type.name, sig.filters.isEmpty())
        }
        assertEquals(TrackerSignatures.all.filter { it.active }.sumOf { it.filters.size }, TrackerSignatures.scanFilters.size)
        // Well inside the per-app filter budget of the Bluetooth stack.
        assertTrue(TrackerSignatures.scanFilters.size <= 16)
    }

    @Test
    fun filtersLetEveryMatchingAdvertisementThrough() {
        for ((name, ad) in trackerAds) {
            val match = TrackerSignatures.match(ad)
            assertTrue("$name is a tracker", match != null)
            val own = TrackerSignatures.of(match!!.type).filters
            assertTrue("$name passes its own signature's filters", own.any { it.accepts(ad) })
            assertTrue("$name passes the scan filter list", TrackerSignatures.scanFilters.any { it.accepts(ad) })
        }
    }

    @Test
    fun filtersRejectOrdinaryDevices() {
        for ((name, ad) in ordinaryAds) {
            assertTrue("$name is not a tracker", TrackerSignatures.match(ad) == null)
            assertFalse("$name is kept out by the filters", TrackerSignatures.scanFilters.any { it.accepts(ad) })
        }
    }

    @Test
    fun eachFilterOnlyAdmitsItsOwnType() {
        // A filter may let through more than its signature accepts (that is what the matcher is for), but never another type.
        for (sig in TrackerSignatures.all.filter { it.active }) {
            for ((name, ad) in trackerAds) {
                val type = TrackerSignatures.match(ad)!!.type
                if (type != sig.type) assertFalse("${sig.type.name} filter admits $name", sig.filters.any { it.accepts(ad) })
            }
        }
    }

    @Test
    fun prefixMatchingFollowsThePlatform() {
        // Shorter advertised data never matches; equal or longer compares only the filter's bytes.
        assertFalse(ScanFilterSpec.matchesPrefix(bytes(0x12), null, null))
        assertFalse(ScanFilterSpec.matchesPrefix(bytes(0x12, 0x19), null, bytes(0x12)))
        assertTrue(ScanFilterSpec.matchesPrefix(bytes(0x12), null, bytes(0x12, 0x19, 0x04)))
        assertFalse(ScanFilterSpec.matchesPrefix(bytes(0x12), null, bytes(0x13, 0x19)))
        // Mask bits that are zero are ignored on both sides.
        assertTrue(ScanFilterSpec.matchesPrefix(bytes(0x40), bytes(0xFE), bytes(0x41, 0x00)))
        assertFalse(ScanFilterSpec.matchesPrefix(bytes(0x40), bytes(0xFE), bytes(0x42, 0x00)))
        // Empty data accepts any present payload, including an empty one, but not a missing one.
        assertTrue(ScanFilterSpec.matchesPrefix(ByteArray(0), null, ByteArray(0)))
        assertFalse(ScanFilterSpec.matchesPrefix(ByteArray(0), null, null))
        // A mask must be as long as the data.
        try {
            ScanFilterSpec.ServiceData(0xFEED, bytes(0x01, 0x02), bytes(0xFF))
            throw AssertionError("mismatched mask accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
