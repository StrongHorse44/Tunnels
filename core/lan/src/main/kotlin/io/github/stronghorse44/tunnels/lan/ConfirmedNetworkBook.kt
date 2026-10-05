package io.github.stronghorse44.tunnels.lan

import kotlin.coroutines.cancellation.CancellationException

/**
 * The text form of the confirmed-network set in the encrypted settings table (rule 5): one SHA-256 of a network's
 * [NetworkFingerprint] per line, lowercase hex, sorted. The same lines are `networks.txt` in an export.
 *
 * The row's presence is meaningful: once it exists, the set has moved out of the old plaintext preferences file
 * (see [ConfirmedNetworkBook.migrate]). An empty set is therefore stored as an empty value, never as a missing row.
 */
object ConfirmedNetworkCodec {
    /** The settings-table key, namespaced by module like `traffic.block`. */
    const val KEY = "homenet.confirmed_networks"

    private val HASH = Regex("[0-9a-f]{64}")

    fun isHash(text: String): Boolean = HASH.matches(text)

    /** Only the well-formed hashes of [held]. Anything else could never equal a fingerprint's hash. */
    fun valid(held: Collection<String>): Set<String> = held.filterTo(LinkedHashSet()) { isHash(it) }

    /** The hashes in [raw] (the stored row, possibly null); lines that are not a hash are skipped. */
    fun decode(raw: String?): Set<String> = if (raw == null) emptySet() else valid(raw.split('\n').map { it.trim() })

    /** [hashes] as stored: the well-formed ones, sorted, each line ended by a newline. */
    fun encode(hashes: Collection<String>): String = valid(hashes).sorted().joinToString("") { it + "\n" }

    /** The row after adding [incoming] to [stored], and how many hashes were new. */
    fun merge(stored: String?, incoming: Collection<String>): Pair<String, Int> {
        val have = decode(stored)
        val fresh = valid(incoming) - have
        return encode(have + fresh) to fresh.size
    }
}

/** The settings-table row, read and written as text. All three throw when the encrypted store cannot be used. */
interface ConfirmedNetworkTable {
    /** The row's value, or null when there is no row. */
    fun read(): String?

    fun write(text: String)

    /**
     * Replaces the row with [transform] of its current value (null when there is no row), **atomically**: the read and
     * the write are one database transaction, so a concurrent writer (an import's merge) can neither be lost nor lose
     * this change. Every change of the set after the migration goes through here.
     */
    fun update(transform: (stored: String?) -> String)
}

/** The old plaintext preferences file (`homenet_gate`), which exists only to be moved into the table and deleted. */
interface PlaintextNetworkFile {
    fun exists(): Boolean

    /** What the file holds, as stored (any text, not yet checked). */
    fun hashes(): Set<String>

    /** Empties and deletes the file; true when no trace of it is left. */
    fun delete(): Boolean
}

/** What [ConfirmedNetworkBook.migrate] did. */
sealed interface NetworkMigration {
    /** There was no plaintext file. */
    data object NothingToMove : NetworkMigration

    /** The hashes are in the table (and read back) and the plaintext file is gone. [copied] is 0 when the table already had its row. */
    data class Moved(val copied: Int) : NetworkMigration

    /** The hashes are in the table but the plaintext file could not be deleted; the next call retries only the deletion. */
    data class MovedKeptFile(val copied: Int) : NetworkMigration

    /** The table could not be written or read back, or the file not read: the plaintext file was left alone and nothing is trusted yet. */
    data class Failed(val cause: Throwable) : NetworkMigration
}

/** The confirmed networks cannot be read right now (the encrypted store, or its migration, failed). */
class ConfirmedNetworksUnavailable(cause: Throwable? = null) : Exception("The confirmed networks are not available.", cause)

/**
 * Which Wi-Fi networks the user confirmed as their own, kept as hashes in the encrypted settings table. Plain Kotlin,
 * so the migration and the fail-closed rules are tested on the JVM; the Android adapters are two small classes in
 * tunnels/homenet and tunnels/snapshots.
 *
 * - **Never both.** [migrate] copies the old preferences file's hashes into the table, reads the row back, and only
 *   then deletes the file. If the copy fails the file is untouched (and still unused); if only the deletion fails the
 *   hashes are not merged in again later (a network the user forgot in between must stay forgotten), only the
 *   deletion is retried. A well-formed row (exactly what [ConfirmedNetworkCodec.encode] writes) marks "moved"; a row
 *   that is not (a failed attempt's leftover) is copied over again and the file deleted only after a verified read-back.
 * - **Fail closed.** The file is never read for a decision. When the table cannot be read, or a pending migration could
 *   not complete, [lookup] answers null and [isConfirmed] false: no scan opens.
 * - Every call blocks (it reads the database): call off the main thread. A process-wide lock serialises the book's own
 *   calls (the migration, and the changes of the Home network gate); every change of the row is one atomic
 *   [ConfirmedNetworkTable.update], the same kind of database transaction as the Snapshots import's merge, so neither
 *   can overwrite the other.
 */
class ConfirmedNetworkBook(private val table: ConfirmedNetworkTable, private val plaintext: PlaintextNetworkFile) {
    /** Moves the plaintext file's hashes into the table. Safe to call any number of times; with no file it only checks that. */
    fun migrate(): NetworkMigration = synchronized(LOCK) { migrateLocked() }

    /** True when [hash] is confirmed. Any failure reads as false. */
    fun isConfirmed(hash: String): Boolean = lookup(hash) == true

    /** True or false when the table answered, null when it could not (store unavailable, or the migration is pending). */
    fun lookup(hash: String): Boolean? = try {
        hashes().contains(hash)
    } catch (e: ConfirmedNetworksUnavailable) {
        null
    }

    /** Every confirmed hash. Throws [ConfirmedNetworksUnavailable] rather than answering with a partial list (an export must not silently drop networks). */
    fun hashes(): Set<String> = synchronized(LOCK) { loadLocked() }

    fun count(): Int? = try {
        hashes().size
    } catch (e: ConfirmedNetworksUnavailable) {
        null
    }

    /** Confirms [hash]; false when it is not a hash or could not be saved. */
    fun confirm(hash: String): Boolean = ConfirmedNetworkCodec.isHash(hash) && change { it + hash }

    fun forget(hash: String): Boolean = change { it - hash }

    /** Forgets every network. The row stays, empty: its presence is what says the old file has been dealt with. */
    fun forgetAll(): Boolean = change { emptySet() }

    /** One atomic read-modify-write of the row ([ConfirmedNetworkTable.update]); false when the store or the migration failed. */
    private fun change(edit: (Set<String>) -> Set<String>): Boolean = synchronized(LOCK) {
        try {
            if (migrateLocked() is NetworkMigration.Failed) return@synchronized false
            table.update { stored -> ConfirmedNetworkCodec.encode(edit(ConfirmedNetworkCodec.decode(stored))) }
            true
        } catch (t: Throwable) {
            rethrowIfControl(t)
            false
        }
    }

    private fun loadLocked(): Set<String> {
        val moved = migrateLocked()
        if (moved is NetworkMigration.Failed) throw ConfirmedNetworksUnavailable(moved.cause)
        return try {
            ConfirmedNetworkCodec.decode(table.read())
        } catch (t: Throwable) {
            rethrowIfControl(t)
            throw ConfirmedNetworksUnavailable(t)
        }
    }

    /**
     * A row counts as "moved" only when it is exactly what [ConfirmedNetworkCodec.encode] writes. Anything else (a
     * half-written or mismatching row from an earlier failed attempt) is copied over again: the old file's hashes
     * and whatever hashes the row still decodes to are written, read back, and only then is the file deleted.
     */
    private fun migrateLocked(): NetworkMigration {
        try {
            if (!plaintext.exists()) return NetworkMigration.NothingToMove
            var copied = 0
            val stored = table.read()
            if (stored == null || ConfirmedNetworkCodec.encode(ConfirmedNetworkCodec.decode(stored)) != stored) {
                val old = ConfirmedNetworkCodec.valid(plaintext.hashes())
                val text = ConfirmedNetworkCodec.encode(old + ConfirmedNetworkCodec.decode(stored))
                table.write(text)
                if (table.read() != text) return NetworkMigration.Failed(IllegalStateException("The row did not read back as written."))
                copied = old.size
            }
            return if (plaintext.delete()) NetworkMigration.Moved(copied) else NetworkMigration.MovedKeptFile(copied)
        } catch (t: Throwable) {
            rethrowIfControl(t)
            return NetworkMigration.Failed(t)
        }
    }

    /**
     * Any failure of the store reads as "unavailable", including an Error such as the UnsatisfiedLinkError of a missing
     * SQLCipher library, so the gate refuses instead of crashing. Only cancellation and interruption pass through.
     */
    private fun rethrowIfControl(t: Throwable) {
        if (t is CancellationException || t is InterruptedException) throw t
    }

    companion object {
        /** The old preferences file and key. They exist only so the migration can find and delete them. */
        const val LEGACY_PREFS = "homenet_gate"
        const val LEGACY_KEY = "confirmed_network_hashes"

        private val LOCK = Any()
    }
}
