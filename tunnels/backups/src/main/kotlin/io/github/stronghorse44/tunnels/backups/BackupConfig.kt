package io.github.stronghorse44.tunnels.backups

import android.content.Context
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Backups tunnel's two settings in the encrypted store: the picked folder's tree URI and the [BackupSettings].
 * Updates are serialised, because a scan and the screen both write (a scan notes which apps it has seen).
 */
internal class BackupConfig(context: Context) {
    private val app = context.applicationContext

    private suspend fun store(): TunnelsStore = withContext(Dispatchers.IO) { TunnelsStore.get(app) }

    suspend fun folder(): String? = store().setting(BackupSettings.FOLDER_KEY)

    suspend fun setFolder(uri: String?) = lock.withLock { store().putSetting(BackupSettings.FOLDER_KEY, uri) }

    suspend fun settings(): BackupSettings = BackupSettings.decode(store().setting(BackupSettings.KEY))

    suspend fun update(change: (BackupSettings) -> BackupSettings): BackupSettings = lock.withLock {
        val store = store()
        change(BackupSettings.decode(store.setting(BackupSettings.KEY))).also { store.putSetting(BackupSettings.KEY, it.encode()) }
    }

    private companion object {
        val lock = Mutex()
    }
}
