package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The opt-in address sweep's switch: one settings row in the encrypted store. Off unless the user turns it on in the
 * Home network panel; a store that cannot be read reads as off, so the sweep never runs by accident.
 */
object SweepSetting {
    const val KEY = "homenet.sweep"
    private const val ON = "on"

    suspend fun isOn(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            TunnelsStore.get(context).setting(KEY) == ON
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun set(context: Context, on: Boolean) = withContext(Dispatchers.IO) {
        TunnelsStore.get(context).putSetting(KEY, if (on) ON else null)
    }
}
