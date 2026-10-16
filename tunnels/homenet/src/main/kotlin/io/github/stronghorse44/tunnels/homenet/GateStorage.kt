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
 * A confirmed-network row in the encrypted settings table: the old v1 row ([ConfirmedNetworkCodec.KEY], the default) or
 * the keyed v2 row (`homenet.networks.v2`, [io.github.stronghorse44.tunnels.lan.NetworkRecordCodec.KEY]). Blocking, so
 * call off the main thread. Opening the store can throw (Keystore, SQLCipher); the books read that as "unavailable" and
 * fail closed.
 */
internal class StoreNetworkTable(context: Context, private val key: String = ConfirmedNetworkCodec.KEY) : ConfirmedNetworkTable {
    private val app = context.applicationContext

    override fun read(): String? = runBlocking { TunnelsStore.get(app).setting(key) }

    override fun write(text: String) = runBlocking { TunnelsStore.get(app).putSetting(key, text) }

    /**
     * The read and the write in one Room transaction. TunnelsDao has no generic transaction, but importAll with no
     * snapshots and this one key is exactly "read the row, write what [transform] says", the same transaction an
     * import's merge runs in, so the two cannot overwrite each other.
     */
    override fun update(transform: (String?) -> String) = runBlocking {
        TunnelsStore.get(app).dao.importAll(emptyList(), listOf(key)) { _, stored -> transform(stored) }
        Unit
    }
}

/**
 * The old plaintext preferences file, which only the migration touches. Reading it never creates it; deleting empties
 * it first, then removes the file, so no hash survives even if the removal fails.
 *
 * It counts `homenet_gate.xml.bak` (SharedPreferences' backup while it writes) as the file and takes
 * `deleteSharedPreferences`' return value as the answer: it removes the file and its backup and reports whether neither
 * is left. (Snapshots' mirror of this adapter went with 0.1.16: exports and imports no longer touch confirmed networks.)
 */
internal class PrefsNetworkFile(context: Context, private val name: String = ConfirmedNetworkBook.LEGACY_PREFS) : PlaintextNetworkFile {
    private val app = context.applicationContext
    private val dir: File get() = File(app.dataDir, "shared_prefs")

    override fun exists(): Boolean = File(dir, "$name.xml").exists() || File(dir, "$name.xml.bak").exists()

    override fun hashes(): Set<String> =
        app.getSharedPreferences(name, Context.MODE_PRIVATE).getStringSet(ConfirmedNetworkBook.LEGACY_KEY, emptySet()).orEmpty().toSet()

    override fun delete(): Boolean {
        app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        return app.deleteSharedPreferences(name)
    }
}
