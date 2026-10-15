package io.github.stronghorse44.tunnels.store

import android.content.Context
import android.provider.Settings
import androidx.room.Room
import io.github.stronghorse44.tunnels.engine.LostStoreFiles
import io.github.stronghorse44.tunnels.engine.Presence
import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import io.github.stronghorse44.tunnels.engine.StoreFailures
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.ZoneId

/** The encrypted store. Open it off the main thread: [get] touches the Keystore. */
class TunnelsStore private constructor(private val db: TunnelsDatabase, private val dbDir: File) {
    val dao: TunnelsDao get() = db.dao()

    suspend fun recordEvent(tunnelId: String, kind: String, subject: String, summary: String, at: Instant = Instant.now()) {
        dao.insertEvent(EventEntity(tunnelId = tunnelId, at = at.toEpochMilli(), kind = kind, subject = subject, summary = summary))
        maintainIfDue(at)
    }

    @Volatile private var lastMaintain: Long = 0L

    /**
     * Rule #4 enforced in code, not by convention: a long-lived writer (a notification listener, a
     * traffic session) prunes expired rows every few hours even if the app is never opened or scanned.
     */
    private suspend fun maintainIfDue(now: Instant) {
        if (now.toEpochMilli() - lastMaintain < MAINTAIN_EVERY_MS) return
        lastMaintain = now.toEpochMilli()
        runCatching { maintain(now) }
    }

    fun events(tunnelId: String, limit: Int = 50): Flow<List<EventEntity>> = dao.events(tunnelId, limit)

    /** A setting's value, or null when it was never set. Keys are namespaced by module, e.g. `traffic.block`. */
    suspend fun setting(key: String): String? = dao.setting(key)

    fun settingFlow(key: String): Flow<String?> = dao.settingFlow(key)

    /** Stores [value] under [key]; null removes it. */
    suspend fun putSetting(key: String, value: String?) {
        if (value == null) dao.deleteSetting(key) else dao.putSetting(SettingEntity(key, value))
    }

    /**
     * Changes one setting atomically: [transform] gets the stored value (null when absent) and returns the new one
     * (null removes the row). For a row with more than one writer; see [TunnelsDao.updateSetting].
     */
    suspend fun updateSetting(key: String, transform: (String?) -> String?) = dao.updateSetting(key, transform)

    /**
     * Enforces retention: 30-day events and change findings, newest 12 unpinned snapshots, and Home network's presence
     * counts of devices not seen for 30 days (rule 4, audit L5: before, only a scan of the same network pruned them).
     */
    suspend fun maintain(now: Instant = Instant.now()) {
        lastMaintain = now.toEpochMilli()
        val cutoff = RetentionPolicy.eventCutoff(now).toEpochMilli()
        dao.deleteEventsAtOrBefore(cutoff)
        dao.deleteStickyFindingsLastSeenBefore(cutoff)
        val doomed = RetentionPolicy.snapshotsToDelete(dao.snapshots().map { it.toModel() })
        if (doomed.isNotEmpty()) dao.deleteSnapshots(doomed.map { it.id })
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        for (key in dao.settingKeysWithPrefix(Presence.SETTINGS_PREFIX)) {
            dao.updateSetting(key) { old -> old?.let { Presence.prune(it, today) } }
        }
    }

    /**
     * True while files of a store that was reset are kept aside (review L1: derived from the files themselves, not from
     * a row written in the same run). The home screen then shows "The store was reset; earlier data is unreadable" until
     * [dismissResetNotice], or until the files are pruned 30 days after the reset. Touches the disk: off the main thread.
     */
    fun resetNotice(context: Context): ResetNotice? = runCatching {
        val newest = LostStoreFiles.present(dbDir, DB_NAME).maxOfOrNull { it.second } ?: return@runCatching null
        ResetNotice(newest, byUser = prefs(context).getLong(USER_RESET_KEY, -1L) == newest.toEpochMilli())
    }.getOrNull()

    /** The user read the reset notice: delete the old store's files kept aside (audit M5). */
    fun dismissResetNotice(context: Context) {
        runCatching { LostStoreFiles.discardAll(dbDir, DB_NAME) }
        runCatching { prefs(context).edit().remove(USER_RESET_KEY).apply() }
    }

    /** A reset whose old files are still kept aside: when ([at], the files' stamp), and whether the user asked for it. */
    data class ResetNotice(val at: Instant, val byUser: Boolean)

    companion object {
        private const val DB_NAME = "tunnels.db"
        private const val MAINTAIN_EVERY_MS = 6 * 60 * 60 * 1000L

        /** What a screen says when [get] throws [StoreUnavailableException]: nothing was deleted, a restart may fix it. */
        const val UNAVAILABLE_MESSAGE = "Tunnels could not open its store; try again after a restart"

        /** The notice shown once after a reset ([resetNotice]). */
        const val RESET_MESSAGE = "The store was reset; earlier data is unreadable"

        /**
         * The fail-closed flag (review Q3), kept outside the store because the store is what cannot be opened: the boot
         * the failure was first seen on and how many app starts saw it ([StoreFailures]). No observation or finding.
         */
        private const val FAILURE_PREFS = "tunnels.store"
        private const val FAILURE_KEY = "unavailable"
        /** The stamp of the reset the user asked for on the fail-closed message, so the notice can say so. */
        private const val USER_RESET_KEY = "reset_by_user"

        @Volatile private var instance: TunnelsStore? = null
        @Volatile private var failureCounted = false

        /**
         * The store, opened on first use. Throws [StoreUnavailableException] when the key cannot be read now; nothing is
         * deleted then and the next call tries again.
         */
        fun get(context: Context): TunnelsStore =
            instance ?: synchronized(this) {
                instance ?: try {
                    open(context.applicationContext, resetKey = false).also {
                        instance = it
                        runCatching { prefs(context).edit().remove(FAILURE_KEY).apply() }
                    }
                } catch (e: StoreUnavailableException) {
                    countFailure(context)
                    throw e
                }
            }

        /**
         * True when the store still cannot be opened and the failure has survived a restart ([StoreFailures.offerReset]):
         * only then does the home offer "Reset the store", behind a confirmation.
         */
        fun resetOffered(context: Context): Boolean =
            instance == null && runCatching { StoreFailures.offerReset(record(context), bootCount(context)) }.getOrDefault(false)

        /**
         * The user's "Reset the store" on the fail-closed message: the same reset as a lost key (the old files are set
         * aside, never deleted; a new key). Only from that confirmed tap, never from a background path, and only while
         * [resetOffered]. Blocking: off the main thread.
         */
        fun resetAfterFailure(context: Context): TunnelsStore = synchronized(this) {
            instance ?: run {
                if (!resetOffered(context)) throw StoreUnavailableException("reset not offered")
                open(context.applicationContext, resetKey = true).also {
                    instance = it
                    runCatching { prefs(context).edit().remove(FAILURE_KEY).apply() }
                }
            }
        }

        fun keySecurityLevel(): String = DatabaseKey.securityLevel()

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE)

        private fun record(context: Context): StoreFailures.Record? = StoreFailures.parse(prefs(context).getString(FAILURE_KEY, null))

        private fun bootCount(context: Context): Int =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

        /** Counts this app start once, however many callers hit the failure in it. */
        private fun countFailure(context: Context) {
            if (failureCounted) return
            failureCounted = true
            runCatching {
                val next = StoreFailures.failed(record(context), bootCount(context))
                prefs(context).edit().putString(FAILURE_KEY, next.encode()).apply()
            }
        }

        private fun open(context: Context, resetKey: Boolean): TunnelsStore {
            System.loadLibrary("sqlcipher")
            val dir = context.getDatabasePath(DB_NAME).parentFile ?: throw StoreUnavailableException("no database folder")
            // Only on a definite loss of the key, or the user's confirmed reset: the old files are renamed aside, never deleted.
            val setAside = {
                dir.mkdirs()
                // Seconds, like the stamp in the file names, so the notice can match the user's reset to its files.
                val at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                if (LostStoreFiles.setAside(dir, DB_NAME, at).isNotEmpty() && resetKey) {
                    runCatching { prefs(context).edit().putLong(USER_RESET_KEY, at.toEpochMilli()).apply() }
                }
                Unit
            }
            val passphrase = if (resetKey) DatabaseKey.newKey(context, setAside) else DatabaseKey.passphrase(context, setAside)
            runCatching { LostStoreFiles.prune(dir, DB_NAME, Instant.now()) }
            val db = Room.databaseBuilder(context, TunnelsDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*TunnelsMigrations.ALL)
                // Pre-release: a schema change without a migration step rebuilds the store. Snapshots are short-lived by rule #4 anyway.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            return TunnelsStore(db, dir)
        }
    }
}
