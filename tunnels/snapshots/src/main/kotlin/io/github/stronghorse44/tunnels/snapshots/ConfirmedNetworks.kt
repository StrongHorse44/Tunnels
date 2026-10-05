package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkTable
import io.github.stronghorse44.tunnels.lan.NetworkMigration
import io.github.stronghorse44.tunnels.lan.PlaintextNetworkFile
import io.github.stronghorse44.tunnels.store.SettingEntity
import io.github.stronghorse44.tunnels.store.TunnelsDao
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * The Wi-Fi networks the user confirmed as their own for Home network's scan, as SHA-256 hashes of each network's
 * fingerprint, for an export and an import. They are one row of the encrypted settings table
 * ([ConfirmedNetworkCodec.KEY], the same store as the snapshots, so an import's one transaction covers them too); an
 * older build kept them in a plaintext preferences file (`homenet_gate`), which [ready] moves into the table and deletes
 * before anything is read or merged. The names come from core:lan, shared with Home network's gate, so they cannot drift.
 * The values are hashes, never an SSID or an address.
 *
 * Blocking calls (they read the database): call off the main thread.
 */
class ConfirmedNetworks(
    context: Context,
    dao: TunnelsDao,
    /** The old preferences file's name; tests pass their own so they never touch the real one. */
    legacyPrefsName: String = ConfirmedNetworkBook.LEGACY_PREFS,
) {
    private val book = ConfirmedNetworkBook(DaoTable(dao), LegacyFile(context.applicationContext, legacyPrefsName))

    /**
     * Makes sure the old plaintext list (if any) is in the table, then returns. Throws [IOException] when it could not
     * be moved: an import must not merge into a row that is missing the phone's own networks, and an export must not
     * leave them out. (A file that moved but could not be deleted is not an error: the table is complete.)
     */
    fun ready() {
        val moved = book.migrate()
        if (moved is NetworkMigration.Failed) throw IOException("The confirmed networks couldn't be read.", moved.cause)
    }

    /** The well-formed hashes the phone has confirmed: what an export carries. Throws if they cannot be read. */
    fun all(): Set<String> = try {
        book.hashes()
    } catch (e: Exception) {
        throw IOException("The confirmed networks couldn't be read.", e)
    }

    private class DaoTable(private val dao: TunnelsDao) : ConfirmedNetworkTable {
        override fun read(): String? = runBlocking { dao.setting(ConfirmedNetworkCodec.KEY) }

        override fun write(text: String) = runBlocking { dao.putSetting(SettingEntity(ConfirmedNetworkCodec.KEY, text)) }
    }

    /** Same file, same rules as Home network's own adapter (tunnels/homenet cannot be depended on from here). */
    private class LegacyFile(private val app: Context, private val name: String) : PlaintextNetworkFile {
        private val file: File get() = File(File(app.dataDir, "shared_prefs"), "$name.xml")

        override fun exists(): Boolean = file.exists()

        override fun hashes(): Set<String> =
            app.getSharedPreferences(name, Context.MODE_PRIVATE).getStringSet(ConfirmedNetworkBook.LEGACY_KEY, emptySet()).orEmpty().toSet()

        override fun delete(): Boolean {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            app.deleteSharedPreferences(name)
            return !file.exists()
        }
    }
}
