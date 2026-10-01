package io.github.stronghorse44.tunnels.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TunnelsDao {
    @Insert
    abstract suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    abstract suspend fun insertObservations(observations: List<ObservationEntity>)

    @Query("SELECT * FROM snapshots ORDER BY taken_at DESC, id DESC")
    abstract suspend fun snapshots(): List<SnapshotEntity>

    @Query("SELECT * FROM observations WHERE snapshot_id = :snapshotId")
    abstract suspend fun observations(snapshotId: Long): List<ObservationEntity>

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

    @Upsert
    abstract suspend fun upsertFindings(findings: List<FindingEntity>)

    @Query("SELECT * FROM findings")
    abstract suspend fun findings(): List<FindingEntity>

    @Insert
    abstract suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events WHERE tunnel_id = :tunnelId ORDER BY at DESC LIMIT :limit")
    abstract fun events(tunnelId: String, limit: Int): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE at <= :cutoff")
    abstract suspend fun deleteEventsAtOrBefore(cutoff: Long)
}
