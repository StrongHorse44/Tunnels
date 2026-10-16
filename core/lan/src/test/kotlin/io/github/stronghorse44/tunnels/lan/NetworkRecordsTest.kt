package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Spec section 6, tests 1 (the v2 codec) and 5 (the book). */
class NetworkRecordsTest {
    private fun t(c: Char) = c.toString().repeat(32)
    private val kid = "0a1b2c3d"

    // ---- 1. codec -----------------------------------------------------------------------------------

    @Test
    fun recordsRoundTripSorted() {
        val records = listOf(
            NetworkRecord(t('c'), RecordMode.ADDRESS),
            NetworkRecord(t('a'), RecordMode.ROUTER, listOf(t('1'), t('2')), t('9')),
            NetworkRecord(t('b'), RecordMode.ROUTER, emptyList(), t('8')),
            NetworkRecord(t('d'), RecordMode.ROUTER, listOf(t('3')), null),
        )
        val text = NetworkRecordCodec.encode(kid, records)
        assertEquals(
            "k=$kid\n${t('a')} r ${t('1')},${t('2')} ${t('9')}\n${t('b')} r - ${t('8')}\n${t('c')} a - -\n${t('d')} r ${t('3')} -\n",
            text,
        )
        val rows = NetworkRecordCodec.decode(text)
        assertEquals(kid, rows.keyId)
        assertEquals(records.sortedBy { it.net }, rows.records.values.toList())
        assertEquals(text, NetworkRecordCodec.encode(kid, rows.records.values.reversed()))
    }

    @Test
    fun eachMalformedLineShapeIsSkipped() {
        val good = "${t('a')} r ${t('1')} -"
        val bad = listOf(
            "${"a".repeat(33)} r ${t('1')} -",
            "${t('A')} r ${t('1')} -",
            "${t('b')} r ${(1..5).joinToString(",") { t(it.digitToChar()) }} -",
            "${t('b')} r - -",
            "${t('b')} a ${t('1')} -",
            "${t('b')} a - ${t('1')}",
            "${t('b')} x - -",
            "${t('b')} r ${t('1')},${t('1')} -",
            "${t('b')}  r ${t('1')} -",
            "${t('b')} r ${t('1')} - ",
            "",
        )
        for (line in bad) {
            assertNull(line, NetworkRecordCodec.parseLine(line))
            val rows = NetworkRecordCodec.decode("k=$kid\n$line\n$good\n")
            assertEquals(line, listOf(t('a')), rows.records.keys.toList())
        }
    }

    @Test
    fun aMissingOrBadKeyLineMakesTheRowUnavailable() {
        for (raw in listOf("${t('a')} a - -\n", "k=0A1B2C3D\n${t('a')} a - -\n", "k=0a1b2c\n", "k=0a1b2c3d4\n", "\n", "x")) {
            try {
                NetworkRecordCodec.decode(raw)
                fail("decoded: $raw")
            } catch (_: NetworkRowsMalformed) {
            }
        }
    }

    @Test
    fun anEmptyOrMissingRowReadsAsNoRecords() {
        assertEquals(NetworkRows.EMPTY, NetworkRecordCodec.decode(""))
        assertEquals(NetworkRows.EMPTY, NetworkRecordCodec.decode(null))
        assertEquals("", NetworkRecordCodec.encode(kid, emptyList()))
        assertEquals(0, NetworkRecordCodec.decode("k=$kid\n").records.size)
    }

    @Test
    fun aDuplicateNetworkLineKeepsTheFirstInSortOrder() {
        val rows = NetworkRecordCodec.decode("k=$kid\n${t('a')} r ${t('1')} -\n${t('a')} a - -\n")
        assertEquals(listOf(NetworkRecord(t('a'), RecordMode.ADDRESS)), rows.records.values.toList())
    }

    @Test
    fun theCodecRefusesAThirtyThirdNetwork() {
        val records = (0 until 33).map { NetworkRecord("%032x".format(it), RecordMode.ADDRESS) }
        assertEquals(32, NetworkRecordCodec.decode(NetworkRecordCodec.encode(kid, records.take(32))).records.size)
        try {
            NetworkRecordCodec.encode(kid, records)
            fail("33 networks encoded")
        } catch (_: IllegalArgumentException) {
        }
        val handMade = "k=$kid\n" + records.joinToString("") { NetworkRecordCodec.encodeLine(it) + "\n" }
        assertEquals("a hand-made row is read up to the cap", 32, NetworkRecordCodec.decode(handMade).records.size)
    }

    @Test
    fun anUnsortedRowDecodesSortedAndTheCapKeepsTheLowestTokens() {
        val records = (0 until 33).map { NetworkRecord("%032x".format(it), RecordMode.ADDRESS) }
        val handMade = "k=$kid\n" + records.reversed().joinToString("") { NetworkRecordCodec.encodeLine(it) + "\n" }
        assertEquals(records.take(32).map { it.net }, NetworkRecordCodec.decode(handMade).records.keys.toList())
    }

    // ---- 5. the book ---------------------------------------------------------------------------------

    private class FakeTable(var row: String? = null) : ConfirmedNetworkTable {
        var unavailable = false
        var writes = 0

        override fun read(): String? {
            if (unavailable) throw IOException("store unavailable")
            return row
        }

        override fun write(text: String) {
            if (unavailable) throw IOException("store unavailable")
            writes++
            row = text
        }

        @Synchronized
        override fun update(transform: (String?) -> String) {
            if (unavailable) throw IOException("store unavailable")
            row = transform(row)
            writes++
        }
    }

    private object NoFile : PlaintextNetworkFile {
        override fun exists() = false
        override fun hashes() = emptySet<String>()
        override fun delete() = true
    }

    private fun macOf(seed: Int): (String) -> String {
        val key = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "HmacSHA256")
        return { input -> Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) } }
    }

    private val home = NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("192.168.1.1"), "192.168.1.0/24")!!
    private val other = NetworkFingerprint.of("10.0.0.1", null, listOf("10.0.0.1"), "10.0.0.0/24")!!

    private val v2 = FakeTable()
    private val v1 = FakeTable("")
    private val legacy = ConfirmedNetworkBook(v1, NoFile)
    private val book = NetworkRecordBook(v2, legacy, macOf(0))
    private val seen = book.tokens.seen(listOf("3a2f9c00-1111-4b4b-8c8c-0123456789ab"), "ab".repeat(32), probed = true, lost = false)

    @Test
    fun confirmByRouterDeletesTheLegacyLine() {
        assertTrue(legacy.confirm(home.hash))
        assertTrue(legacy.confirm(other.hash))
        assertEquals(GateRecord(book.tokens.net(home), null, legacy = true, keyIdOk = true), book.lookup(home))
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, seen))
        val found = book.lookup(home)
        assertEquals(NetworkRecord(book.tokens.net(home), RecordMode.ROUTER, seen.upnp, seen.tls), found.record)
        assertFalse(found.legacy)
        assertEquals("the other network's v1 line stays", setOf(other.hash), legacy.hashes())
        assertTrue("the row holds tokens only", v2.row!!.lines().drop(1).all { it.isEmpty() || it.split(' ').all { p -> p.length in setOf(1, 32, 65) || p == "-" } })
        assertTrue(v2.row!!.startsWith("k=${book.tokens.keyId()}\n"))
    }

    @Test
    fun confirmByAddressOnlyStoresNoFactor() {
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ADDRESS, seen))
        assertEquals(NetworkRecord(book.tokens.net(home), RecordMode.ADDRESS), book.lookup(home).record)
    }

    @Test
    fun aRouterConfirmationWithNothingSeenSavesNothing() {
        assertEquals(SaveResult.FAILED, book.confirm(home, RecordMode.ROUTER, SeenTokens()))
        assertNull(book.lookup(home).record)
    }

    @Test
    fun aNewRouterReplacesTheRecord() {
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, seen))
        val newer = book.tokens.seen(listOf("00112233-4455-6677-8899-aabbccddeeff"), null, probed = true, lost = false)
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, newer))
        assertEquals(newer.upnp, book.lookup(home).record!!.upnp)
        assertNull(book.lookup(home).record!!.tls)
        assertEquals(1, book.count())
    }

    @Test
    fun forgetRemovesBothAndForgetAllEmptiesBothRows() {
        assertTrue(legacy.confirm(home.hash))
        assertEquals(SaveResult.SAVED, book.confirm(other, RecordMode.ADDRESS, seen))
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, seen))
        assertTrue(legacy.confirm(home.hash)) // as if an older build had added it again
        assertTrue(book.forget(home))
        assertEquals(GateRecord(book.tokens.net(home), null, legacy = false, keyIdOk = true), book.lookup(home))
        assertEquals(1, book.count())
        assertTrue(legacy.confirm(home.hash))
        assertTrue(book.forgetAll())
        assertEquals("", v2.row)
        assertEquals("", v1.row)
        assertEquals(0, book.count())
    }

    @Test
    fun aThirtyThirdNetworkIsRefused() {
        repeat(32) { i ->
            val fp = NetworkFingerprint.of("192.168.$i.1", null, emptyList(), "192.168.$i.0/24")!!
            assertEquals(SaveResult.SAVED, book.confirm(fp, RecordMode.ADDRESS, seen))
        }
        val before = v2.row
        assertEquals(SaveResult.FULL, book.confirm(home, RecordMode.ADDRESS, seen))
        assertEquals(before, v2.row)
        val first = NetworkFingerprint.of("192.168.0.1", null, emptyList(), "192.168.0.0/24")!!
        assertEquals("a network already there can still be replaced", SaveResult.SAVED, book.confirm(first, RecordMode.ROUTER, seen))
    }

    @Test
    fun aFullRowKeepsTheOldLine() {
        repeat(32) { i ->
            val fp = NetworkFingerprint.of("192.168.$i.1", null, emptyList(), "192.168.$i.0/24")!!
            assertEquals(SaveResult.SAVED, book.confirm(fp, RecordMode.ADDRESS, seen))
        }
        assertTrue(legacy.confirm(home.hash))
        assertEquals(SaveResult.FULL, book.confirm(home, RecordMode.ROUTER, seen))
        assertTrue("nothing changed, the v1 line included", book.lookup(home).legacy)
    }

    @Test
    fun aV1LineThatCannotBeRemovedSavesNothing() {
        assertTrue(legacy.confirm(home.hash))
        v1.unavailable = true
        assertEquals(SaveResult.FAILED, book.confirm(home, RecordMode.ROUTER, seen))
        assertNull(v2.row)
    }

    @Test
    fun aThrowingTableIsUnavailable() {
        v2.unavailable = true
        try {
            book.lookup(home)
            fail("looked up")
        } catch (_: ConfirmedNetworksUnavailable) {
        }
        assertEquals(SaveResult.FAILED, book.confirm(home, RecordMode.ROUTER, seen))
        assertFalse(book.forget(home))
        assertFalse(book.forgetAll())
        assertNull(book.count())
    }

    @Test
    fun anUnreadableV1RowIsUnavailableToo() {
        v1.unavailable = true
        try {
            book.lookup(home)
            fail("looked up")
        } catch (_: ConfirmedNetworksUnavailable) {
        }
    }

    @Test
    fun anAddressOnlyRecordWithAFactorIsNeverWritten() {
        val bad = NetworkRecord(t('a'), RecordMode.ADDRESS, listOf(t('1')), null)
        assertFalse(bad.valid)
        assertFalse(NetworkRecord(t('a'), RecordMode.ADDRESS, emptyList(), t('1')).valid)
        assertEquals("", NetworkRecordCodec.encode(kid, listOf(bad)))
    }

    @Test
    fun aThrowingMacIsUnavailableAndSavesNothing() {
        val broken = NetworkRecordBook(v2, legacy) { throw IllegalStateException("keystore") }
        try {
            broken.lookup(home)
            fail("looked up")
        } catch (_: ConfirmedNetworksUnavailable) {
        }
        assertEquals(SaveResult.FAILED, broken.confirm(home, RecordMode.ADDRESS, seen))
        assertNull(v2.row)
    }

    @Test
    fun aMalformedRowIsUnavailableUntilForgetAll() {
        v2.row = "garbage\n"
        try {
            book.lookup(home)
            fail("looked up")
        } catch (_: ConfirmedNetworksUnavailable) {
        }
        assertEquals("never silently wiped", SaveResult.FAILED, book.confirm(home, RecordMode.ADDRESS, seen))
        assertTrue(legacy.confirm(home.hash))
        assertFalse("forget answers false, so the panel resets nothing", book.forget(home))
        assertTrue("and the v1 line stays", legacy.hashes().contains(home.hash))
        assertEquals("garbage\n", v2.row)
        assertTrue(book.forgetAll())
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ADDRESS, seen))
    }

    @Test
    fun aRowUnderAnotherKeyIsKeyChangedAndAConfirmationStartsItOver() {
        val oldBook = NetworkRecordBook(v2, legacy, macOf(7))
        assertEquals(SaveResult.SAVED, oldBook.confirm(other, RecordMode.ADDRESS, seen))
        assertEquals(SaveResult.SAVED, oldBook.confirm(home, RecordMode.ADDRESS, seen))
        val found = book.lookup(home)
        assertFalse(found.keyIdOk)
        assertNull(found.record)
        assertEquals(Verdict.KeyChanged, RouterIdentity.judge(found.record, found.legacy, found.keyIdOk, null, Purpose.SCAN, false))
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ADDRESS, seen))
        assertTrue(book.lookup(home).keyIdOk)
        assertEquals("the old key's records are gone", 1, book.count())
        assertTrue(book.lookup(other).keyIdOk)
        assertNull(book.lookup(other).record)
    }

    @Test
    fun forgettingInTheKeyChangedStateEmptiesTheOldKeysRow() {
        val oldBook = NetworkRecordBook(v2, legacy, macOf(7))
        assertEquals(SaveResult.SAVED, oldBook.confirm(home, RecordMode.ADDRESS, seen))
        assertEquals(SaveResult.SAVED, oldBook.confirm(other, RecordMode.ROUTER, seen))
        assertFalse(book.lookup(home).keyIdOk)
        assertTrue(book.forget(home))
        assertEquals("the old key's row is emptied", "", v2.row)
        val after = book.lookup(home)
        assertTrue(after.keyIdOk)
        assertEquals("not confirmed any more", Verdict.NotConfirmed, RouterIdentity.judge(after.record, after.legacy, after.keyIdOk, null, Purpose.SCAN, false))
    }

    @Test
    fun forgettingUnderTheCurrentKeyKeepsTheOtherNetworks() {
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ADDRESS, seen))
        assertEquals(SaveResult.SAVED, book.confirm(other, RecordMode.ROUTER, seen))
        assertTrue(book.forget(home))
        assertEquals(1, book.count())
        assertTrue(book.lookup(other).record != null)
    }

    @Test
    fun anEmptyRowUnderAnyKeyIsNotAKeyChange() {
        v2.row = ""
        assertTrue(book.lookup(home).keyIdOk)
        v2.row = "k=deadbeef\n"
        assertTrue(book.lookup(home).keyIdOk)
    }

    @Test
    fun refreshWritesOnlyAChange() {
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, seen))
        val record = book.lookup(home).record!!
        val writes = v2.writes
        assertTrue(book.refresh(record, seen))
        assertEquals("unchanged input writes nothing", writes, v2.writes)
        val newKey = book.tokens.seen(emptyList(), "cd".repeat(32), probed = true, lost = false)
        assertTrue(book.refresh(record, newKey))
        assertEquals(writes + 1, v2.writes)
        assertEquals(newKey.tls, book.lookup(home).record!!.tls)
        assertEquals(record.upnp, book.lookup(home).record!!.upnp)
        // A refresh made against the record as it was before that write changes nothing (the newer record wins).
        val otherId = book.tokens.seen(listOf("00112233-4455-6677-8899-aabbccddeeff"), null, probed = true, lost = false)
        assertTrue(book.refresh(record, otherId))
        assertEquals(newKey.tls, book.lookup(home).record!!.tls)
        assertEquals(record.upnp, book.lookup(home).record!!.upnp)
    }

    @Test
    fun aFailedRefreshReportsFalse() {
        assertEquals(SaveResult.SAVED, book.confirm(home, RecordMode.ROUTER, seen))
        val record = book.lookup(home).record!!
        v2.unavailable = true
        assertFalse(book.refresh(record, book.tokens.seen(emptyList(), "cd".repeat(32), probed = true, lost = false)))
    }
}
