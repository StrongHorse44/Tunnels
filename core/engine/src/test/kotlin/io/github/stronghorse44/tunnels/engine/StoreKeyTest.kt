package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.engine.StoreKey.Loss
import io.github.stronghorse44.tunnels.engine.StoreKey.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.ProviderException
import java.time.Instant
import javax.crypto.AEADBadTagException

class StoreKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Stands in for android.security.keystore.KeyPermanentlyInvalidatedException, which the adapter recognises. */
    private class Invalidated : Exception()

    private val wrapped = ByteArray(1 + 12 + 32 + 16).also { it[0] = 12 }
    private val passphrase = ByteArray(32) { it.toByte() }

    private fun read(
        fileExists: Boolean = true,
        aliasPresent: Boolean = true,
        file: ByteArray = wrapped,
        unwrap: (ByteArray) -> ByteArray = { passphrase },
        aliasCheck: () -> Boolean = { aliasPresent },
    ): Outcome = StoreKey.read(
        fileExists = { fileExists },
        aliasPresent = aliasCheck,
        readFile = { file },
        unwrap = unwrap,
        isInvalidated = { it is Invalidated },
    )

    @Test
    fun aReadableKeyOpensTheStore() {
        val outcome = read()
        assertTrue(outcome is Outcome.Opened)
        assertArrayEquals(passphrase, (outcome as Outcome.Opened).passphrase)
    }

    @Test
    fun resetsOnlyOnDefiniteKeyLoss_fileAbsent() {
        assertEquals(Outcome.Lost(Loss.FILE_ABSENT), read(fileExists = false))
    }

    @Test
    fun resetsOnlyOnDefiniteKeyLoss_aliasMissing() {
        assertEquals(Outcome.Lost(Loss.ALIAS_MISSING), read(aliasPresent = false))
    }

    @Test
    fun resetsOnlyOnDefiniteKeyLoss_keyPermanentlyInvalidated() {
        assertEquals(Outcome.Lost(Loss.KEY_INVALIDATED), read(unwrap = { throw Invalidated() }))
    }

    @Test
    fun transientKeystoreFailureFailsClosed() {
        // A keystore2 restart or StrongBox hiccup: the key is still there, so nothing may be reset.
        assertTrue(read(unwrap = { throw ProviderException("keystore busy") }) is Outcome.Unavailable)
        assertTrue(read(unwrap = { throw AEADBadTagException() }) is Outcome.Unavailable)
        assertTrue(read(unwrap = { throw IllegalStateException("no key") }) is Outcome.Unavailable)
        assertTrue(read(aliasCheck = { throw ProviderException("keystore down") }) is Outcome.Unavailable)
        assertTrue(
            StoreKey.read({ true }, { true }, { throw IOException("read failed") }, { passphrase }, { false }) is Outcome.Unavailable,
        )
    }

    @Test
    fun truncatedOrWrongLengthFileWithAliasPresentFailsClosed() {
        assertTrue(read(file = wrapped.copyOf(40)) is Outcome.Unavailable)
        assertTrue(read(file = ByteArray(0)) is Outcome.Unavailable)
        assertTrue(read(file = wrapped + 0) is Outcome.Unavailable)
        assertTrue(read(file = wrapped.copyOf().also { it[0] = 0x7f }) is Outcome.Unavailable)
        assertTrue(read(unwrap = { ByteArray(16) }) is Outcome.Unavailable)
    }

    @Test
    fun resetSetsTheOldStoreAsideInsteadOfDeletingIt() {
        val dir = tmp.newFolder()
        File(dir, "tunnels.db").writeText("db")
        File(dir, "tunnels.db-wal").writeText("wal")
        File(dir, "tunnels.db-shm").writeText("shm")
        File(dir, "other.db").writeText("keep")
        val at = Instant.parse("2026-10-15T12:34:56Z")

        val moved = LostStoreFiles.setAside(dir, "tunnels.db", at)

        assertEquals(
            setOf("tunnels.db.lost-20261015T123456Z", "tunnels.db.lost-20261015T123456Z-wal", "tunnels.db.lost-20261015T123456Z-shm"),
            moved.map { it.name }.toSet(),
        )
        assertFalse(File(dir, "tunnels.db").exists())
        assertFalse(File(dir, "tunnels.db-wal").exists())
        assertEquals("db", File(dir, "tunnels.db.lost-20261015T123456Z").readText())
        assertEquals("wal", File(dir, "tunnels.db.lost-20261015T123456Z-wal").readText())
        assertEquals("keep", File(dir, "other.db").readText())
        assertEquals(3, LostStoreFiles.present(dir, "tunnels.db").size)
    }

    @Test
    fun aSecondResetInTheSameSecondKeepsTheFirstLostFiles() {
        val dir = tmp.newFolder()
        val at = Instant.parse("2026-10-15T12:34:56Z")
        File(dir, "tunnels.db").writeText("one")
        LostStoreFiles.setAside(dir, "tunnels.db", at)
        File(dir, "tunnels.db").writeText("two")
        LostStoreFiles.setAside(dir, "tunnels.db", at)
        assertEquals(setOf("one", "two"), LostStoreFiles.present(dir, "tunnels.db").map { it.first.readText() }.toSet())
    }

    @Test
    fun noStoreMeansNothingSetAside() {
        assertTrue(LostStoreFiles.setAside(tmp.newFolder(), "tunnels.db", Instant.now()).isEmpty())
    }

    @Test
    fun lostFilesAreKeptThirtyDaysThenPruned() {
        val dir = tmp.newFolder()
        val at = Instant.parse("2026-10-01T00:00:00Z")
        File(dir, "tunnels.db").writeText("db")
        LostStoreFiles.setAside(dir, "tunnels.db", at)
        assertEquals(0, LostStoreFiles.prune(dir, "tunnels.db", at.plus(LostStoreFiles.KEEP)))
        assertEquals(1, LostStoreFiles.present(dir, "tunnels.db").size)
        assertEquals(1, LostStoreFiles.prune(dir, "tunnels.db", at.plus(LostStoreFiles.KEEP).plusSeconds(1)))
        assertTrue(LostStoreFiles.present(dir, "tunnels.db").isEmpty())
    }

    @Test
    fun dismissingTheNoticeDiscardsTheLostFiles() {
        val dir = tmp.newFolder()
        File(dir, "tunnels.db").writeText("db")
        File(dir, "tunnels.db-wal").writeText("wal")
        LostStoreFiles.setAside(dir, "tunnels.db", Instant.now())
        assertEquals(2, LostStoreFiles.discardAll(dir, "tunnels.db"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
