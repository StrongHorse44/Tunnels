package io.github.stronghorse44.tunnels.updater

import android.content.Context
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The read-only GitHub token a private repository needs. It lives in the encrypted store (SQLCipher, Keystore-
 * wrapped key) as the single row of its own events stream, so it is never in an export, goes with an uninstall,
 * and, like every event, is forgotten after 30 days without use: each successful check saves it again.
 */
object UpdateToken {
    const val STREAM = "updater.github"
    private const val KIND = "token"
    private const val SUBJECT = "github"

    suspend fun read(context: Context): String? = withContext(Dispatchers.IO) {
        TunnelsStore.get(context).dao.events(STREAM, 1).first().firstOrNull()?.summary?.takeIf { it.isNotBlank() }
    }

    /** Saves [token] as the only row of the stream (also refreshes its 30-day clock). */
    suspend fun save(context: Context, token: String) = withContext(Dispatchers.IO) {
        val store = TunnelsStore.get(context)
        val now = System.currentTimeMillis()
        store.recordEvent(STREAM, KIND, SUBJECT, token, Instant.ofEpochMilli(now))
        store.dao.deleteEventsBefore(STREAM, now)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        TunnelsStore.get(context).dao.deleteEventsBefore(STREAM, Long.MAX_VALUE)
    }
}
