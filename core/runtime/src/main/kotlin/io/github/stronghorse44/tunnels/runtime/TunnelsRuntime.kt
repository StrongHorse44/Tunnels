package io.github.stronghorse44.tunnels.runtime

import android.content.Context
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide wiring: the registry of tunnel modules, the encrypted store and the snapshot engine. */
class TunnelsRuntime private constructor(val registry: TunnelRegistry, val store: TunnelsStore) {
    val engine = SnapshotEngine(registry, store)

    companion object {
        @Volatile private var instance: TunnelsRuntime? = null
        private val lock = Any()

        /** Opens the store (Keystore work): call off the main thread. */
        suspend fun get(context: Context): TunnelsRuntime =
            instance ?: withContext(Dispatchers.IO) {
                instance ?: synchronized(lock) {
                    instance ?: TunnelsRuntime(TunnelRegistry(context), TunnelsStore.get(context)).also { instance = it }
                }
            }

        /** The runtime if it has already been opened; null before the first [get]. */
        fun peek(): TunnelsRuntime? = instance
    }
}
