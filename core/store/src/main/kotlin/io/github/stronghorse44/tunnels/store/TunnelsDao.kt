package io.github.stronghorse44.tunnels.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TunnelsDao {
    // Snapshots and observations

    @Insert
    abstract suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    abstract suspend fun insertObservations(observations: List<ObservationEntity>)

    @Query("SELECT * FROM snapshots ORDER BY taken_at DESC, id DESC")
    abstract suspend fun snapshots(): List<SnapshotEntity>

    @Query("SELECT * FROM snapshots ORDER BY taken_at DESC, id DESC")
    abstract fun snapshotsFlow(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE id = :id")
    abstract suspend fun snapshot(id: Long): SnapshotEntity?

    @Query("SELECT * FROM observations WHERE snapshot_id = :snapshotId")
    abstract suspend fun observations(snapshotId: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE snapshot_id = :snapshotId AND tunnel_id = :tunnelId")
    abstract suspend fun observations(snapshotId: Long, tunnelId: String): List<ObservationEntity>

    /** The newest snapshot (before [before], if given) that holds observations for [tunnelId]. */
    @Query(
        "SELECT MAX(snapshot_id) FROM observations WHERE tunnel_id = :tunnelId AND (:before IS NULL OR snapshot_id < :before)",
    )
    abstract suspend fun latestSnapshotIdFor(tunnelId: String, before: Long? = null): Long?

    @Query("SELECT DISTINCT tunnel_id FROM observations WHERE snapshot_id = :snapshotId")
    abstract suspend fun tunnelsIn(snapshotId: Long): List<String>

    /** Observation count per snapshot, for history lists (avoids loading rows just to count them). */
    @Query("SELECT snapshot_id, COUNT(*) AS count FROM observations GROUP BY snapshot_id")
    abstract suspend fun observationCounts(): List<SnapshotCount>

    @Query("SELECT DISTINCT snapshot_id, tunnel_id FROM observations")
    abstract suspend fun tunnelsPerSnapshot(): List<SnapshotTunnel>

    /** Inserts a snapshot with its observations atomically (imports). */
    @Transaction
    open suspend fun importSnapshot(snapshot: SnapshotEntity, observations: (snapshotId: Long) -> List<ObservationEntity>): Long {
        val id = insertSnapshot(snapshot)
        insertObservations(observations(id))
        return id
    }

    @Query("UPDATE snapshots SET pinned = :pinned WHERE id = :id")
    abstract suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM observations WHERE snapshot_id IN (:ids)")
    abstract suspend fun deleteObservationsOf(ids: List<Long>)

    @Query("DELETE FROM snapshots WHERE id IN (:ids)")
    abstract suspend fun deleteSnapshotRows(ids: List<Long>)

    @Transaction
    open suspend fun deleteSnapshots(ids: List<Long>) {
        deleteObservationsOf(ids)
        deleteSnapshotRows(ids)
    }

    // Findings

    @Upsert
    abstract suspend fun upsertFindings(findings: List<FindingEntity>)

    @Query("SELECT * FROM findings")
    abstract suspend fun findings(): List<FindingEntity>

    @Query("SELECT * FROM findings WHERE tunnel_id = :tunnelId")
    abstract suspend fun findingsFor(tunnelId: String): List<FindingEntity>

    @Query("SELECT * FROM findings WHERE tunnel_id = :tunnelId AND dismissed = 0 ORDER BY severity DESC, last_seen DESC")
    abstract fun findingsFlow(tunnelId: String): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE dismissed = 0 ORDER BY last_seen DESC")
    abstract fun allFindingsFlow(): Flow<List<FindingEntity>>

    @Query("SELECT tunnel_id, severity, COUNT(*) AS count FROM findings WHERE dismissed = 0 GROUP BY tunnel_id, severity")
    abstract fun findingCounts(): Flow<List<SeverityCount>>

    @Query("DELETE FROM findings WHERE id IN (:ids)")
    abstract suspend fun deleteFindings(ids: List<String>)

    @Query("UPDATE findings SET dismissed = 1 WHERE id = :id")
    abstract suspend fun dismissFinding(id: String)

    @Query("DELETE FROM findings WHERE sticky = 1 AND last_seen <= :cutoff")
    abstract suspend fun deleteStickyFindingsLastSeenBefore(cutoff: Long)

    // Events

    @Insert
    abstract suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events WHERE tunnel_id = :tunnelId ORDER BY at DESC LIMIT :limit")
    abstract fun events(tunnelId: String, limit: Int): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE at <= :cutoff")
    abstract suspend fun deleteEventsAtOrBefore(cutoff: Long)

    /** Drops one stream's rows older than [before]: for a stream that keeps only its latest row and no history. */
    @Query("DELETE FROM events WHERE tunnel_id = :tunnelId AND at < :before")
    abstract suspend fun deleteEventsBefore(tunnelId: String, before: Long)

    // Settings

    @Query("SELECT `value` FROM settings WHERE `key` = :key")
    abstract suspend fun setting(key: String): String?

    @Query("SELECT `value` FROM settings WHERE `key` = :key")
    abstract fun settingFlow(key: String): Flow<String?>

    @Upsert
    abstract suspend fun putSetting(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    abstract suspend fun deleteSetting(key: String)
}
