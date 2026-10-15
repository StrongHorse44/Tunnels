package io.github.stronghorse44.tunnels.convert

import io.github.stronghorse44.tunnels.decoder.DecoderClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [block] and, if the coroutine is cancelled while it runs, aborts [client]'s job: the sealed decoder's calls block a
 * thread, so cancellation alone would not stop them. The aborted call then fails with CANCELLED, which is not counted.
 */
internal suspend fun <T> abortOnCancel(client: DecoderClient, block: suspend () -> T): T = coroutineScope {
    val done = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!done.get()) client.abort()
        }
    }
    try {
        block()
    } finally {
        done.set(true)
        watcher.cancel()
    }
}
