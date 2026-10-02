package io.github.stronghorse44.tunnels.store

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SnapshotEntity::class, ObservationEntity::class, FindingEntity::class, EventEntity::class, SettingEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class TunnelsDatabase : RoomDatabase() {
    abstract fun dao(): TunnelsDao
}

/** Schema steps that keep the user's snapshots and findings. Anything without a step here rebuilds the store. */
object TunnelsMigrations {
    /** 2 -> 3: the settings table. The statement matches what Room generates for [SettingEntity]. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
