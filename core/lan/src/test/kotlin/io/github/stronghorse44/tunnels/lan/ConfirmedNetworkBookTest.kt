package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ConfirmedNetworkBookTest {
    private val a = "0f".repeat(32)
    private val b = "a1".repeat(32)
    private val c = "c3".repeat(32)

    /** The settings row, with switches for the failures the encrypted store can have. */
    private class FakeTable(var row: String? = null) : ConfirmedNetworkTable {
        var unavailable = false
        var failWrites = false
        var corruptWrites = false
        var writes = 0

        /** When set, read/write called outside [update] fail: a change must be one atomic update, never a read then a write. */
        var forbidSplitAccess = false
        private var inUpdate = false

        /** Thrown instead of an IOException when set (an Error such as the missing SQLCipher library, or a cancellation). */
        var failure: Throwable? = null

        override fun read(): String? {
            failure?.let { throw it }
            if (unavailable) throw IOException("store unavailable")
            if (forbidSplitAccess && !inUpdate) throw AssertionError("a change read the row outside the atomic update")
            return row
        }

        @Synchronized
        override fun update(transform: (String?) -> String) {
            failure?.let { throw it }
            if (unavailable || failWrites) throw IOException("store unavailable")
            inUpdate = true
            try {
                row = transform(row)
                writes++
            } finally {
                inUpdate = false
            }
        }

        override fun write(text: String) {
            failure?.let { throw it }
            if (forbidSplitAccess && !inUpdate) throw AssertionError("a change wrote the row outside the atomic update")
            if (unavailable || failWrites) throw IOException("store unavailable")
            writes++
            row = if (corruptWrites) "tampered" else text
        }
    }

    /** The old preferences file: present or not, holding whatever it held. */
    private class FakePlaintext(var present: Boolean, var held: Set<String> = emptySet()) : PlaintextNetworkFile {
        var refuseDelete = false
        var reads = 0
        var deletes = 0

        override fun exists() = present

        override fun hashes(): Set<String> {
            reads++
            return held
        }

        override fun delete(): Boolean {
            deletes++
            if (refuseDelete) return false
            present = false
            held = emptySet()
            return true
        }
    }

    private fun book(table: FakeTable, file: FakePlaintext) = ConfirmedNetworkBook(table, file)

    @Test
    fun codecIsSortedLowercaseAndIgnoresJunk() {
        assertEquals("$a\n$b\n", ConfirmedNetworkCodec.encode(setOf(b, a, "not-a-hash", "A1".repeat(32))))
        assertEquals(setOf(a, b), ConfirmedNetworkCodec.decode("$b\njunk\n\n  $a  \n${"A1".repeat(32)}\n"))
        assertEquals(emptySet<String>(), ConfirmedNetworkCodec.decode(null))
        assertEquals("", ConfirmedNetworkCodec.encode(emptySet()))
        assertEquals("$a\n$b\n$c\n" to 1, ConfirmedNetworkCodec.merge("$a\n$b\n", listOf(b, c, "x")))
        assertEquals("" to 0, ConfirmedNetworkCodec.merge(null, emptyList()))
        assertEquals("homenet.confirmed_networks", ConfirmedNetworkCodec.KEY)
    }

    @Test
    fun oldHashesMoveIntoTheTableAndTheFileIsDeleted() {
        val table = FakeTable()
        val file = FakePlaintext(present = true, held = setOf(a, b, "junk", "SSID-Home"))
        val result = book(table, file).migrate()
        assertEquals(NetworkMigration.Moved(2), result)
        assertEquals("$a\n$b\n", table.row)
        assertFalse("the plaintext file is gone", file.present)
        assertTrue(file.held.isEmpty())
        assertEquals(1, file.deletes)
    }

    @Test
    fun anEmptyOldFileStillLeavesTheRowAndNoFile() {
        val table = FakeTable()
        val file = FakePlaintext(present = true)
        assertEquals(NetworkMigration.Moved(0), book(table, file).migrate())
        assertEquals("", table.row)
        assertFalse(file.present)
    }

    @Test
    fun noOldFileMeansNothingIsReadOrWritten() {
        val table = FakeTable()
        val file = FakePlaintext(present = false)
        val book = book(table, file)
        assertEquals(NetworkMigration.NothingToMove, book.migrate())
        assertNull("a fresh install has no row until something is confirmed", table.row)
        assertEquals(0, table.writes)
        assertEquals(emptySet<String>(), book.hashes())
        assertEquals(0, file.reads)
    }

    @Test
    fun secondRunIsAnIdempotentNoOp() {
        val table = FakeTable()
        val file = FakePlaintext(present = true, held = setOf(a))
        val book = book(table, file)
        book.migrate()
        val before = table.writes
        assertEquals(NetworkMigration.NothingToMove, book.migrate())
        assertEquals(before, table.writes)
        assertEquals(setOf(a), book.hashes())
    }

    @Test
    fun aFailedCopyLeavesTheFileUntouchedAndConfirmsNothing() {
        val table = FakeTable().apply { failWrites = true }
        val file = FakePlaintext(present = true, held = setOf(a))
        val book = book(table, file)
        assertTrue(book.migrate() is NetworkMigration.Failed)
        assertTrue("never deleted before the copy is safe", file.present)
        assertEquals(0, file.deletes)
        assertEquals(setOf(a), file.held)
        assertNull("table has no row to trust", table.row)
        // Fail closed: the plaintext file is not a fallback for the decision.
        assertEquals(null, book.lookup(a))
        assertFalse(book.isConfirmed(a))
        assertEquals(null, book.count())
        try {
            book.hashes()
            fail("answered from a pending migration")
        } catch (_: ConfirmedNetworksUnavailable) {
        }
        // The store comes back: the next call completes the move.
        table.failWrites = false
        assertTrue(book.isConfirmed(a))
        assertFalse(file.present)
    }

    @Test
    fun aRowThatDoesNotReadBackKeepsTheFile() {
        val table = FakeTable().apply { corruptWrites = true }
        val file = FakePlaintext(present = true, held = setOf(a))
        assertTrue(book(table, file).migrate() is NetworkMigration.Failed)
        assertTrue(file.present)
        assertEquals(0, file.deletes)
    }

    /** The row a failed read-back left behind is not "moved": the next run copies again and deletes only after a verified read-back. */
    @Test
    fun aMismatchingRowIsCopiedAgainNotTrusted() {
        val table = FakeTable().apply { corruptWrites = true }
        val file = FakePlaintext(present = true, held = setOf(a, b))
        val book = book(table, file)
        assertTrue(book.migrate() is NetworkMigration.Failed)
        assertEquals("tampered", table.row)
        assertTrue("never confirmed from a row that is not the set", !book.isConfirmed(a))

        table.corruptWrites = false
        assertEquals(NetworkMigration.Moved(2), book.migrate())
        assertEquals("$a\n$b\n", table.row)
        assertFalse(file.present)
        assertEquals(setOf(a, b), book.hashes())
    }

    @Test
    fun aRowThatIsNotCanonicalIsCopiedOverAndKeepsWhatItDecodes() {
        // Half a write: one good hash, then junk. The old file's hashes are added, the decodable one is kept.
        val table = FakeTable(row = "$c\njunk-without-newline")
        val file = FakePlaintext(present = true, held = setOf(a))
        assertEquals(NetworkMigration.Moved(1), book(table, file).migrate())
        assertEquals("$a\n$c\n", table.row)
        assertFalse(file.present)
        // A canonical row, even an empty one, is trusted (the forgotten network must not come back).
        val table2 = FakeTable(row = "")
        val file2 = FakePlaintext(present = true, held = setOf(a))
        assertEquals(NetworkMigration.Moved(0), book(table2, file2).migrate())
        assertEquals("", table2.row)
    }

    /** The reviewer's probe: another writer (an import's merge) between a change's read and write must not be lost, because there is no gap. */
    @Test
    fun aChangeIsOneAtomicUpdateSoAConcurrentMergeIsNotLost() {
        val table = FakeTable(row = "$a\n").apply { forbidSplitAccess = true }
        val book = book(table, FakePlaintext(present = false))
        assertTrue(book.confirm(b))
        assertTrue(book.forget(a))
        assertTrue(book.forgetAll())
        assertTrue(book.confirm(c))
        assertEquals("$c\n", table.row)

        // Many confirms against many merges on another thread (what an import's transaction does): every hash survives.
        val hashes = (0 until 60).map { "%064x".format(it + 1) }
        val merges = (60 until 120).map { "%064x".format(it + 1) }
        val t2 = FakeTable()
        val book2 = book(t2, FakePlaintext(present = false))
        val merger = Thread { merges.forEach { h -> t2.update { stored -> ConfirmedNetworkCodec.merge(stored, listOf(h)).first } } }
        merger.start()
        hashes.forEach { assertTrue(book2.confirm(it)) }
        merger.join()
        assertEquals((hashes + merges).toSet(), book2.hashes())
    }

    @Test
    fun aMissingLibraryErrorMeansUnavailableNotACrash() {
        val table = FakeTable(row = "$a\n").apply { failure = UnsatisfiedLinkError("dlopen failed: libsqlcipher.so") }
        val file = FakePlaintext(present = true, held = setOf(b))
        val book = book(table, file)
        assertEquals(null, book.lookup(a))
        assertFalse(book.isConfirmed(a))
        assertFalse(book.confirm(a))
        assertFalse(book.forget(a))
        assertFalse(book.forgetAll())
        assertEquals(null, book.count())
        val migrated = book.migrate()
        assertTrue(migrated is NetworkMigration.Failed && migrated.cause is UnsatisfiedLinkError)
        assertTrue("the old file is untouched", file.present && file.deletes == 0)
        try {
            book.hashes()
            fail("answered without the store")
        } catch (e: ConfirmedNetworksUnavailable) {
            assertTrue(e.cause is UnsatisfiedLinkError)
        }
        // And when the file is already gone, a store that dies later is the same answer.
        val table2 = FakeTable(row = "$a\n")
        val book2 = book(table2, FakePlaintext(present = false))
        assertTrue(book2.isConfirmed(a))
        table2.failure = UnsatisfiedLinkError("x")
        assertEquals(null, book2.lookup(a))
        assertFalse(book2.confirm(b))
    }

    @Test
    fun cancellationAndInterruptionAreNotSwallowed() {
        for (control in listOf<Throwable>(kotlin.coroutines.cancellation.CancellationException("cancelled"), InterruptedException("interrupted"))) {
            val table = FakeTable(row = "$a\n").apply { failure = control }
            val book = book(table, FakePlaintext(present = false))
            try {
                book.isConfirmed(a)
                fail("swallowed $control")
            } catch (e: Throwable) {
                assertTrue(e === control)
            }
            try {
                book.confirm(b)
                fail("swallowed $control")
            } catch (e: Throwable) {
                assertTrue(e === control)
            }
        }
    }

    @Test
    fun anUnreadableStoreNeverLetsTheFileDecide() {
        val table = FakeTable().apply { unavailable = true }
        val file = FakePlaintext(present = true, held = setOf(a))
        val book = book(table, file)
        assertFalse(book.isConfirmed(a))
        assertEquals(null, book.lookup(a))
        assertFalse(book.confirm(b))
        assertFalse(book.forget(a))
        assertFalse(book.forgetAll())
        assertEquals(0, file.deletes)
        assertEquals(setOf(a), file.held)

        // A store that dies after the migration also answers "not confirmed", with no file to fall back on.
        val table2 = FakeTable(row = "$a\n")
        val book2 = book(table2, FakePlaintext(present = false))
        assertTrue(book2.isConfirmed(a))
        table2.unavailable = true
        assertFalse(book2.isConfirmed(a))
        assertEquals(null, book2.lookup(a))
    }

    @Test
    fun aFileThatWillNotDeleteIsRetriedAndNeverMergedBackIn() {
        val table = FakeTable()
        val file = FakePlaintext(present = true, held = setOf(a, b)).apply { refuseDelete = true }
        val book = book(table, file)
        assertEquals(NetworkMigration.MovedKeptFile(2), book.migrate())
        assertEquals(setOf(a, b), book.hashes())
        // The user forgets one while the file is still there: it must not come back from the file.
        assertTrue(book.forget(a))
        assertEquals(setOf(b), book.hashes())
        assertEquals(setOf(b), ConfirmedNetworkCodec.decode(table.row))
        file.refuseDelete = false
        assertEquals(NetworkMigration.Moved(0), book.migrate())
        assertFalse(file.present)
        assertEquals(setOf(b), book.hashes())
    }

    @Test
    fun theRowWinsOverALeftoverFile() {
        val table = FakeTable(row = "$c\n")
        val file = FakePlaintext(present = true, held = setOf(a))
        val book = book(table, file)
        assertEquals(setOf(c), book.hashes())
        assertFalse(file.present)
        assertEquals(0, file.reads)
    }

    @Test
    fun confirmingBeforeTheMigrationKeepsTheOldHashes() {
        val table = FakeTable()
        val file = FakePlaintext(present = true, held = setOf(a))
        val book = book(table, file)
        assertTrue(book.confirm(b))
        assertEquals(setOf(a, b), book.hashes())
        assertFalse(file.present)
    }

    @Test
    fun confirmForgetAndForgetAllWorkOnTheTable() {
        val table = FakeTable()
        val book = book(table, FakePlaintext(present = false))
        assertFalse(book.isConfirmed(a))
        assertEquals(0, book.count())
        assertTrue(book.confirm(a))
        assertTrue(book.confirm(b))
        assertTrue(book.confirm(a))
        assertEquals(2, book.count())
        assertEquals("$a\n$b\n", table.row)
        assertTrue(book.isConfirmed(a))
        assertFalse(book.isConfirmed(c))
        assertTrue(book.forget(a))
        assertFalse(book.isConfirmed(a))
        assertTrue(book.forgetAll())
        assertEquals("the row stays, empty", "", table.row)
        assertEquals(0, book.count())
        assertFalse(book.confirm("192.168.1.1"))
        assertFalse(book.confirm("SSID"))
    }

    @Test
    fun aWriteThatFailsReportsFalseAndChangesNothing() {
        val table = FakeTable(row = "$a\n")
        val book = book(table, FakePlaintext(present = false))
        table.failWrites = true
        assertFalse(book.confirm(b))
        assertFalse(book.forget(a))
        table.failWrites = false
        assertEquals(setOf(a), book.hashes())
    }

    @Test
    fun anOldFileThatCannotBeReadIsLeftAlone() {
        val table = FakeTable()
        val file = object : PlaintextNetworkFile {
            override fun exists() = true
            override fun hashes(): Set<String> = throw IOException("unreadable")
            override fun delete(): Boolean = throw AssertionError("deleted a file that was never copied")
        }
        val result = ConfirmedNetworkBook(table, file).migrate()
        assertTrue(result is NetworkMigration.Failed)
        assertNull(table.row)
    }
}
