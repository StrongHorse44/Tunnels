package io.github.stronghorse44.tunnels.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SnapshotEntity::class, ObservationEntity::class, FindingEntity::class, EventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TunnelsDatabase : RoomDatabase() {
    abstract fun dao(): TunnelsDao
}
