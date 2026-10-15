package io.github.stronghorse44.tunnels.engine

import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * When the store's wrapped key may be replaced, and when it may not (audit M5). The store is reset only on a definite
 * loss of the old key: the wrapped-key file is gone, the Keystore no longer holds the wrapping key, or the Keystore says
 * the key was permanently invalidated. Any other failure (a Keystore error, a keystore2 restart, a damaged or
 * wrong-length file while the key is still there) may pass, so the store fails closed and nothing is deleted.
 * Plain JVM: the Android adapter (`core/store` DatabaseKey) passes the Keystore calls in.
 */
object StoreKey {
    /** Why the old key is gone for good. */
    enum class Loss { FILE_ABSENT, ALIAS_MISSING, KEY_INVALIDATED }

    sealed interface Outcome {
        class Opened(val passphrase: ByteArray) : Outcome

        /** The old key is gone: the store may be reset (its files set aside first, see [LostStoreFiles]). */
        data class Lost(val why: Loss) : Outcome

        /** Not readable now, for a reason that may pass: fail closed, delete nothing, try again later. [cause] is a class name or a shape. */
        data class Unavailable(val cause: String) : Outcome
    }

    const val PASSPHRASE_LENGTH = 32
    private const val TAG_LENGTH = 16

    /** AES-GCM nonces Android Keystore produces are 12 bytes; up to 16 is accepted so a longer one never locks the store. */
    private val IV_LENGTHS = 12..16

    /** The wrapped file as `DatabaseKey` writes it: one IV-length byte, the IV, the 32-byte passphrase sealed with a 16-byte tag. */
    fun wellFormed(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val ivLength = data[0].toInt()
        return ivLength in IV_LENGTHS && data.size == 1 + ivLength + PASSPHRASE_LENGTH + TAG_LENGTH
    }

    /**
     * Reads the passphrase. Only [Outcome.Lost] allows a reset, so each platform call must say "gone" only when it is sure:
     * - [fileExists]: true, false only when the file is known not to exist, null when that cannot be told (an IO error).
     *   A throw or a null is [Outcome.Unavailable]; `File.exists()` alone is not enough, since it also says false on an
     *   IO error.
     * - [aliasPresent]: false only when the Keystore answers "no such key". `KeyStore.containsAlias` must never be used
     *   here: in keystore2 it is `getKeyMetadata(alias) != null`, and `getKeyMetadata` swallows every
     *   `KeyStoreException` (binder death, SYSTEM_ERROR) and returns null, so a Keystore fault would read as a missing key
     *   and reset the store. `KeyStore.getKey` returns null only for KEY_NOT_FOUND and throws (UnrecoverableKeyException)
     *   for everything else; a throw here is [Outcome.Unavailable].
     * - [readFile] and [unwrap]: any throw is [Outcome.Unavailable], except one [isInvalidated] recognises as the Keystore's
     *   "permanently invalidated".
     */
    fun read(
        fileExists: () -> Boolean?,
        aliasPresent: () -> Boolean,
        readFile: () -> ByteArray,
        unwrap: (ByteArray) -> ByteArray,
        isInvalidated: (Throwable) -> Boolean,
    ): Outcome {
        val exists = try {
            fileExists()
        } catch (e: Exception) {
            return Outcome.Unavailable(e.javaClass.simpleName)
        } ?: return Outcome.Unavailable("wrapped key file state unknown")
        if (!exists) return Outcome.Lost(Loss.FILE_ABSENT)
        val alias = try {
            aliasPresent()
        } catch (e: Exception) {
            return Outcome.Unavailable(e.javaClass.simpleName)
        }
        if (!alias) return Outcome.Lost(Loss.ALIAS_MISSING)
        val data = try {
            readFile()
        } catch (e: Exception) {
            return Outcome.Unavailable(e.javaClass.simpleName)
        }
        if (!wellFormed(data)) return Outcome.Unavailable("wrapped key of ${data.size} bytes")
        val passphrase = try {
            unwrap(data)
        } catch (e: Exception) {
            return if (isInvalidated(e)) Outcome.Lost(Loss.KEY_INVALIDATED) else Outcome.Unavailable(e.javaClass.simpleName)
        }
        if (passphrase.size != PASSPHRASE_LENGTH) return Outcome.Unavailable("passphrase of ${passphrase.size} bytes")
        return Outcome.Opened(passphrase)
    }

    /**
     * The reset, in the only safe order: [loadKeystore] first, so a Keystore that cannot be loaded changes nothing on
     * disk (the store stays fail-closed, nothing set aside, the wrapped file kept); then [setAside] the old store, then
     * [deleteWrapped] the old wrapped file (before the key changes: a crash part-way leaves "file absent", a definite
     * loss, never a file sealed under another key), then [deleteEntry] (a missing entry is no error), then [create] the
     * new key and wrapped passphrase. Any throw propagates; the caller maps it to fail-closed.
     */
    fun <K> reset(
        loadKeystore: () -> K,
        setAside: () -> Unit,
        deleteWrapped: () -> Unit,
        deleteEntry: (K) -> Unit,
        create: (K) -> ByteArray,
    ): ByteArray {
        val keystore = loadKeystore()
        setAside()
        deleteWrapped()
        runCatching { deleteEntry(keystore) }
        return create(keystore)
    }
}

/**
 * A reset store's old files, kept aside instead of deleted (audit M5): `tunnels.db`, `-wal`, `-shm` and `-journal` are
 * renamed to `tunnels.db.lost-<UTC stamp>` (plus the same suffix). Nothing can read them without the lost key; they are
 * kept until the user dismisses the reset notice or for [KEEP], whichever comes first (rule 4).
 */
object LostStoreFiles {
    val KEEP: Duration = Duration.ofDays(30)

    private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun stamp(at: Instant): String = STAMP.format(at)

    private fun pattern(dbName: String) = Regex(Regex.escape("$dbName.lost-") + "(\\d{8}T\\d{6}Z)(-\\d+)?(-wal|-shm|-journal)?")

    /**
     * Renames [dbName]'s files in [dir] aside, stamped [at]. Returns the renamed files (empty when there was no store).
     * Throws [IOException] when a file cannot be moved; then nothing was deleted, and files already moved stay moved.
     */
    fun setAside(dir: File, dbName: String, at: Instant): List<File> {
        val present = SUFFIXES.map { File(dir, dbName + it) }.filter { it.exists() }
        if (present.isEmpty()) return emptyList()
        var base = "$dbName.lost-${stamp(at)}"
        var n = 1
        while (SUFFIXES.any { File(dir, base + it).exists() }) base = "$dbName.lost-${stamp(at)}-${n++}"
        return present.map { file ->
            val target = File(dir, base + file.name.removePrefix(dbName))
            if (!file.renameTo(target)) throw IOException("Could not set aside ${file.name}")
            target
        }
    }

    /** The lost files in [dir], with the time each was set aside. */
    fun present(dir: File, dbName: String): List<Pair<File, Instant>> {
        val regex = pattern(dbName)
        return dir.listFiles().orEmpty().mapNotNull { f ->
            val m = regex.matchEntire(f.name) ?: return@mapNotNull null
            val at = runCatching { Instant.from(STAMP.parse(m.groupValues[1])) }.getOrNull() ?: return@mapNotNull null
            f to at
        }
    }

    /** Deletes lost files set aside more than [KEEP] before [now]. Returns how many went. */
    fun prune(dir: File, dbName: String, now: Instant): Int =
        present(dir, dbName).filter { (_, at) -> at.isBefore(now.minus(KEEP)) }.count { (f, _) -> f.delete() }

    /** Deletes every lost file (the user dismissed the notice). */
    fun discardAll(dir: File, dbName: String): Int = present(dir, dbName).count { (f, _) -> f.delete() }
}

/**
 * When the fail-closed message may offer "Reset the store" (review Q3): only once the failure has survived a restart of
 * the phone, so a passing Keystore fault is never answered with a reset. The record is a flag kept outside the store
 * (it cannot be in it): the boot the first failure was seen in, and on how many app starts it was seen since. When the
 * boot count cannot be read, three app starts that all failed stand in for a restart.
 */
object StoreFailures {
    data class Record(val firstBoot: Int, val starts: Int) {
        fun encode(): String = "$firstBoot;$starts"
    }

    const val STARTS_WITHOUT_BOOT_COUNT = 3

    fun parse(text: String?): Record? {
        val parts = text?.split(';') ?: return null
        if (parts.size != 2) return null
        val boot = parts[0].toIntOrNull() ?: return null
        val starts = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Record(boot, starts)
    }

    /** The record after one more app start whose store failed, on boot [boot] (-1 when unknown). */
    fun failed(record: Record?, boot: Int): Record = record?.copy(starts = record.starts + 1) ?: Record(boot, 1)

    /** Whether the failure has survived a restart: seen on an earlier boot than [boot], or on enough starts. */
    fun offerReset(record: Record?, boot: Int): Boolean {
        if (record == null) return false
        if (boot >= 0 && record.firstBoot >= 0) return boot != record.firstBoot
        return record.starts >= STARTS_WITHOUT_BOOT_COUNT
    }
}
