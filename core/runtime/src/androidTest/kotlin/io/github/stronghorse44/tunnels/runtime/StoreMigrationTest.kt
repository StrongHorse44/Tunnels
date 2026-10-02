package io.github.stronghorse44.tunnels.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.store.SettingEntity
import io.github.stronghorse44.tunnels.store.TunnelsDatabase
import io.github.stronghorse44.tunnels.store.TunnelsMigrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A store written by the previous build (schema 2) opens with this build's migration instead of being rebuilt:
 * its findings and pinned snapshots survive, and the settings table works. Without a fallback, so a migration Room
 * rejects fails here instead of wiping a phone's store.
 */
@RunWith(AndroidJUnit4::class)
class StoreMigrationTest {
    @Test
    fun aVersion2StoreKeepsItsRowsAndGainsSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            V2_SCHEMA.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO findings VALUES ('trust_store|user:1|CA_ADDED', 'trust_store', 'user:1', 'CA_ADDED', 'WARN', 1, 2, 'evidence', 1, 0)")
            db.execSQL("INSERT INTO snapshots (taken_at, pinned) VALUES (5, 1)")
            db.execSQL("INSERT INTO events (tunnel_id, at, kind, subject, summary) VALUES ('traffic', 7, 'SESSION', 'summary', 's=abc')")
            db.version = 2
        }
        val room = Room.databaseBuilder(context, TunnelsDatabase::class.java, name).addMigrations(*TunnelsMigrations.ALL).build()
        try {
            runBlocking {
                val dao = room.dao()
                assertEquals("trust_store|user:1|CA_ADDED", dao.findings().single().id)
                assertTrue("pinned snapshot kept", dao.snapshots().single().pinned)
                dao.putSetting(SettingEntity("test.key", "one"))
                dao.putSetting(SettingEntity("test.key", "two"))
                assertEquals("two", dao.setting("test.key"))
                dao.deleteSetting("test.key")
                assertEquals(null, dao.setting("test.key"))
            }
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        /** Schema 2 exactly as Room created it (entities as of the build before settings). */
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taken_at` INTEGER NOT NULL, `pinned` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `observations` (`snapshot_id` INTEGER NOT NULL, `tunnel_id` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
                "`obs_key` TEXT NOT NULL, `obs_value` TEXT NOT NULL, PRIMARY KEY(`snapshot_id`, `tunnel_id`, `subject`, `obs_key`))",
            "CREATE TABLE IF NOT EXISTS `findings` (`id` TEXT NOT NULL, `tunnel_id` TEXT NOT NULL, `subject` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`severity` TEXT NOT NULL, `first_seen` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `evidence` TEXT NOT NULL, " +
                "`sticky` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tunnel_id` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `subject` TEXT NOT NULL, `summary` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_events_tunnel_id_at` ON `events` (`tunnel_id`, `at`)",
        )
    }
}
