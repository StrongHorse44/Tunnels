package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkTable
import io.github.stronghorse44.tunnels.lan.PlaintextNetworkFile
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The confirmed-network row in the encrypted settings table. Blocking, so call off the main thread. Opening the store
 * can throw (Keystore, SQLCipher); the book reads that as "unavailable" and fails closed.
 */
internal class StoreNetworkTable(context: Context) : ConfirmedNetworkTable {
    private val app = context.applicationContext

    override fun read(): String? = runBlocking { TunnelsStore.get(app).setting(ConfirmedNetworkCodec.KEY) }

    override fun write(text: String) = runBlocking { TunnelsStore.get(app).putSetting(ConfirmedNetworkCodec.KEY, text) }
}

/**
 * The old plaintext preferences file, which only the migration touches. Reading it never creates it; deleting empties
 * it first, then removes the file, so no hash survives even if the removal fails.
 */
internal class PrefsNetworkFile(context: Context, private val name: String = ConfirmedNetworkBook.LEGACY_PREFS) : PlaintextNetworkFile {
    private val app = context.applicationContext
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
