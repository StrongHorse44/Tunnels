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
    }

    fun events(tunnelId: String, limit: Int = 50): Flow<List<EventEntity>> = dao.events(tunnelId, limit)

    /** Enforces retention: 30-day events and change findings, newest 12 unpinned snapshots. */
    suspend fun maintain(now: Instant = Instant.now()) {
        val cutoff = RetentionPolicy.eventCutoff(now).toEpochMilli()
        dao.deleteEventsAtOrBefore(cutoff)
        dao.deleteStickyFindingsLastSeenBefore(cutoff)
        val doomed = RetentionPolicy.snapshotsToDelete(dao.snapshots().map { it.toModel() })
        if (doomed.isNotEmpty()) dao.deleteSnapshots(doomed.map { it.id })
    }

    companion object {
        private const val DB_NAME = "tunnels.db"

        @Volatile private var instance: TunnelsStore? = null

        fun get(context: Context): TunnelsStore =
            instance ?: synchronized(this) { instance ?: open(context.applicationContext).also { instance = it } }

        fun keySecurityLevel(): String = DatabaseKey.securityLevel()

        private fun open(context: Context): TunnelsStore {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKey.passphrase(context) { context.deleteDatabase(DB_NAME) }
            val db = Room.databaseBuilder(context, TunnelsDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Pre-release: schema changes rebuild the store. Snapshots are short-lived by rule #4 anyway.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            return TunnelsStore(db)
        }
    }
}
