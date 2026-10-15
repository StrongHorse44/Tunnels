package io.github.stronghorse44.tunnels.store

import android.content.Context
import androidx.room.Room
import io.github.stronghorse44.tunnels.engine.LostStoreFiles
import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import io.github.stronghorse44.tunnels.lan.Presence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.time.Instant
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
     * When the store was last reset because its key was lost (epoch millis as text), or null. The home screen shows
     * "The store was reset; earlier data is unreadable" once, until [dismissResetNotice].
     */
    fun resetNotice(): Flow<String?> = dao.settingFlow(RESET_NOTICE_KEY)

    /** The user read the reset notice: forget it and delete the old store's files kept aside (audit M5). */
    suspend fun dismissResetNotice() {
        dao.deleteSetting(RESET_NOTICE_KEY)
        runCatching { LostStoreFiles.discardAll(dbDir, DB_NAME) }
    }

    companion object {
        private const val DB_NAME = "tunnels.db"
        private const val MAINTAIN_EVERY_MS = 6 * 60 * 60 * 1000L

        /** What a screen says when [get] throws [StoreUnavailableException]: nothing was deleted, a restart may fix it. */
        const val UNAVAILABLE_MESSAGE = "Tunnels could not open its store; try again after a restart"

        /** The notice shown once after a reset ([resetNotice]). */
        const val RESET_MESSAGE = "The store was reset; earlier data is unreadable"

        /** Settings row set when a reset happened. Not exported (`BundleSettings.KEYS` does not list it). */
        const val RESET_NOTICE_KEY = "store.reset_at"

        @Volatile private var instance: TunnelsStore? = null

        /**
         * The store, opened on first use. Throws [StoreUnavailableException] when the key cannot be read now; nothing is
         * deleted then and the next call tries again.
         */
        fun get(context: Context): TunnelsStore =
            instance ?: synchronized(this) { instance ?: open(context.applicationContext).also { instance = it } }

        fun keySecurityLevel(): String = DatabaseKey.securityLevel()

        private fun open(context: Context): TunnelsStore {
            System.loadLibrary("sqlcipher")
            val dir = context.getDatabasePath(DB_NAME).parentFile ?: throw StoreUnavailableException("no database folder")
            var resetAt: Instant? = null
            val passphrase = DatabaseKey.passphrase(context) {
                // Only on a definite loss of the key: the old files are renamed aside, never deleted here.
                val at = Instant.now()
                dir.mkdirs()
                if (LostStoreFiles.setAside(dir, DB_NAME, at).isNotEmpty()) resetAt = at
            }
            runCatching { LostStoreFiles.prune(dir, DB_NAME, Instant.now()) }
            val db = Room.databaseBuilder(context, TunnelsDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*TunnelsMigrations.ALL)
                // Pre-release: a schema change without a migration step rebuilds the store. Snapshots are short-lived by rule #4 anyway.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            val store = TunnelsStore(db, dir)
            resetAt?.let { at ->
                runCatching { runBlocking { store.dao.putSetting(SettingEntity(RESET_NOTICE_KEY, at.toEpochMilli().toString())) } }
            }
            return store
        }
    }
}
