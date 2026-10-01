package io.github.stronghorse44.tunnels.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Snapshot
import java.time.Instant

@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    val pinned: Boolean = false,
) {
    fun toModel() = Snapshot(id, Instant.ofEpochMilli(takenAt), pinned)
}

@Entity(
    tableName = "observations",
    primaryKeys = ["snapshot_id", "tunnel_id", "subject", "obs_key"],
)
data class ObservationEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: Long,
    @ColumnInfo(name = "tunnel_id") val tunnelId: String,
    val subject: String,
    @ColumnInfo(name = "obs_key") val key: String,
    @ColumnInfo(name = "obs_value") val value: String,
) {
    fun toModel() = Observation(tunnelId, subject, key, value)
}

/** Findings keep evidence summaries only. Actions are recomputed by the owning tunnel. */
@Entity(tableName = "findings")
data class FindingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "tunnel_id") val tunnelId: String,
    val subject: String,
    val kind: String,
    val severity: String,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    val evidence: String,
    /** Change findings stay until dismissed or expired; state findings clear with the state. */
    val sticky: Boolean = false,
    val dismissed: Boolean = false,
)

data class SeverityCount(
    @ColumnInfo(name = "tunnel_id") val tunnelId: String,
    val severity: String,
    val count: Int,
)

/** Short-lived summary events (e.g. "installed X 1.2"). Expire after 30 days. */
@Entity(tableName = "events", indices = [Index("tunnel_id", "at")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tunnel_id") val tunnelId: String,
    val at: Long,
    val kind: String,
    val subject: String,
    val summary: String,
)
