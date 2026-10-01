package io.github.stronghorse44.tunnels.trackers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexTypeReaderTest {
    private val descriptors = listOf(
        "Lcom/example/app/MainActivity;",
        "Lcom/google/firebase/analytics/FirebaseAnalytics;",
        "Lcom/google/firebase/analytics/connector/AnalyticsConnector;",
        "Lcom/appsflyer/AppsFlyerLib;",
        "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;",
        "Lcom/google/android/gms/ads/AdView;",
        "Ljava/lang/Object;",
        "I",
        "[B",
    )

    @Test
    fun readsDescriptorsAndMatchesTrackers() {
        val dex = SyntheticDex.build(descriptors, extraStrings = listOf("unrelated string", "onCreate"))
        assertEquals(descriptors, DexTypeReader.descriptors(dex))

        val scan = DexTypeReader.scan(dex)
        assertTrue(scan.valid)
        assertEquals(35, scan.version)
        assertEquals(descriptors.size, scan.types)
        assertFalse(scan.capped)
        assertEquals(2, scan.hits["firebase_analytics"])
        assertEquals(1, scan.hits["appsflyer"])
        // Deepest prefix wins: the ad-id client is not counted as AdMob.
        assertEquals(1, scan.hits["google_ad_id"])
        assertEquals(1, scan.hits["google_admob"])
        assertEquals(4, scan.hits.size)
    }

    @Test
    fun acceptsEveryKnownVersionAndRejectsOthers() {
        for (v in listOf("035", "037", "038", "039", "040", "041")) {
            val scan = DexTypeReader.scan(SyntheticDex.build(descriptors, version = v))
            assertTrue("version $v", scan.valid)
            assertEquals(v.toInt(), scan.version)
        }
        assertFalse(DexTypeReader.scan(SyntheticDex.build(descriptors, version = "042")).valid)
        assertFalse(DexTypeReader.scan(SyntheticDex.build(descriptors, version = "009")).valid)
        assertFalse(DexTypeReader.scan(SyntheticDex.build(descriptors, version = "0x5")).valid)
    }

    @Test
    fun rejectsGarbageWithoutThrowing() {
        assertFalse(DexTypeReader.scan(ByteArray(0)).valid)
        assertFalse(DexTypeReader.scan(ByteArray(10)).valid)
        assertFalse(DexTypeReader.scan(ByteArray(0x70)).valid)
        assertFalse(DexTypeReader.scan("PK\u0003\u0004 definitely a zip".toByteArray()).valid)
        val random = ByteArray(4096) { (it * 31 + 7).toByte() }
        "dex\n035\u0000".toByteArray().copyInto(random)
        val scan = DexTypeReader.scan(random)
        assertFalse(scan.valid)
        assertNotNull(scan.reason)
        assertFalse(DexTypeReader.scan(SyntheticDex.build(descriptors, endianTag = 0x78563412)).valid)
        assertFalse(DexTypeReader.scan(SyntheticDex.build(descriptors, headerSize = 0x10)).valid)
    }

    @Test
    fun truncatedFileIsRejectedOrReadPartially() {
        val full = SyntheticDex.build(descriptors)
        // Cut inside the index tables: the header promises more than the file holds.
        val cutInTables = full.copyOf(DexTypeReader.HEADER_SIZE + 4 * descriptors.size + 6)
        val a = DexTypeReader.scan(cutInTables)
        assertFalse(a.valid)
        assertEquals("type table out of bounds", a.reason)
        // Cut inside the string data: tables are intact, so the readable descriptors are visited and nothing escapes.
        for (cut in listOf(full.size - 1, full.size - 20, full.size - 60)) {
            val b = DexTypeReader.scan(full.copyOf(cut))
            assertTrue("cut at $cut", b.valid)
            assertTrue(b.types <= descriptors.size)
        }
        // Every truncation length must be survivable.
        for (len in 0 until full.size) DexTypeReader.scan(full.copyOf(len))
    }

    @Test
    fun boundsStringIndexesAndOffsets() {
        val dex = SyntheticDex.build(descriptors)
        // Point type 0 at a string index past the table and type 1 at an offset past the file.
        val typeIdsOff = DexTypeReader.HEADER_SIZE + 4 * descriptors.size
        putInt(dex, typeIdsOff, 999_999)
        putInt(dex, DexTypeReader.HEADER_SIZE + 4 * 1, Int.MAX_VALUE)
        val scan = DexTypeReader.scan(dex)
        assertTrue(scan.valid)
        assertEquals(descriptors.size - 2, scan.types)
        assertEquals(1, scan.hits["firebase_analytics"]) // the connector class, not the one whose string was broken
    }

    @Test
    fun matcherHandlesNonAsciiAndLongDescriptors() {
        val weird = listOf("Léè/中文/Klasse;", "Lcom/appsflyer/" + "a/".repeat(300) + "X;", "Lcom/appsflyerx/NotIt;")
        val scan = DexTypeReader.scan(SyntheticDex.build(weird))
        assertTrue(scan.valid)
        assertEquals(3, scan.types)
        assertEquals(mapOf("appsflyer" to 1), scan.hits)
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte(); b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }
}
