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

        override fun read(): String? {
            if (unavailable) throw IOException("store unavailable")
            return row
        }

        override fun write(text: String) {
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
