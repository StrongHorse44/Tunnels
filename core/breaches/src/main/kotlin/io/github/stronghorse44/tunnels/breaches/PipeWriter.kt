package io.github.stronghorse44.tunnels.breaches

import java.io.IOException
import java.io.OutputStream

/**
 * Writes held bytes to a reader's pipe on a background thread, with a time limit: a reader that keeps the pipe open
 * and never reads must not keep the bytes alive. When [timeoutMs] passes before the write has finished, [abort]
 * runs (it must close the write end, which makes a blocked write fail) and the copy is zeroed. The copy is
 * zeroed when the write ends either way.
 */
object PipeWriter {
    const val TIMEOUT_MS = 60_000L

    fun start(bytes: ByteArray, out: OutputStream, abort: () -> Unit, timeoutMs: Long = TIMEOUT_MS): Thread {
        val writer = Thread({
            try {
                out.use { it.write(bytes) }
            } catch (_: IOException) {
                // The reader stopped early or the write was aborted: nothing is left to deliver.
            } finally {
                bytes.fill(0)
            }
        }, "breach-share")
        val watchdog = Thread({
            writer.join(timeoutMs)
            if (writer.isAlive) {
                try {
                    abort()
                } catch (_: IOException) {
                }
            }
        }, "breach-share-timeout").apply { isDaemon = true }
        writer.start()
        watchdog.start()
        return writer
    }
}
