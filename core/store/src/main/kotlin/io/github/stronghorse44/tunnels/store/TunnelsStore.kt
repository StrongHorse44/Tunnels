package io.github.stronghorse44.tunnels.store

import android.content.Context
import androidx.room.Room
import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.Instant

/** The encrypted store. Open it off the main thread: [get] touches the Keystore. */
class TunnelsStore private constructor(private val db: TunnelsDatabase) {
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

    /** Enforces retention: 30-day events and change findings, newest 12 unpinned snapshots. */
    suspend fun maintain(now: Instant = Instant.now()) {
        lastMaintain = now.toEpochMilli()
        val cutoff = RetentionPolicy.eventCutoff(now).toEpochMilli()
        dao.deleteEventsAtOrBefore(cutoff)
        dao.deleteStickyFindingsLastSeenBefore(cutoff)
        val doomed = RetentionPolicy.snapshotsToDelete(dao.snapshots().map { it.toModel() })
        if (doomed.isNotEmpty()) dao.deleteSnapshots(doomed.map { it.id })
    }

    companion object {
        private const val DB_NAME = "tunnels.db"
        private const val MAINTAIN_EVERY_MS = 6 * 60 * 60 * 1000L

        @Volatile private var instance: TunnelsStore? = null

        fun get(context: Context): TunnelsStore =
            instance ?: synchronized(this) { instance ?: open(context.applicationContext).also { instance = it } }

        fun keySecurityLevel(): String = DatabaseKey.securityLevel()

        private fun open(context: Context): TunnelsStore {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKey.passphrase(context) { context.deleteDatabase(DB_NAME) }
            val db = Room.databaseBuilder(context, TunnelsDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*TunnelsMigrations.ALL)
                // Pre-release: a schema change without a migration step rebuilds the store. Snapshots are short-lived by rule #4 anyway.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            return TunnelsStore(db)
        }
    }
}
