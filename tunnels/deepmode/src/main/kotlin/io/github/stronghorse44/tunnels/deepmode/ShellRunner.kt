package io.github.stronghorse44.tunnels.deepmode

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Runs `sh -c <command>` with a wall-clock timeout and an output cap. Used inside the Shizuku user
 * service (shell uid); plain Kotlin so the limits can be tested on any JVM.
 */
class ShellRunner(
    private val shell: String = "/system/bin/sh",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    /**
     * Combined stdout and stderr. A non-zero exit appends `[exit N]`; running out of time kills the
     * process and appends `[timed out]`; passing the cap kills it and appends `[truncated]`.
     */
    fun run(command: String): String {
        val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(true).start()
        val buffer = ByteArrayOutputStream()
        var truncated = false
        val reader = Thread({
            try {
                val chunk = ByteArray(8192)
                val input = process.inputStream
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    val room = maxOutputBytes - buffer.size()
                    if (n > room) {
                        buffer.write(chunk, 0, room.coerceAtLeast(0))
                        truncated = true
                        process.destroyForcibly()
                        break
                    }
                    buffer.write(chunk, 0, n)
                }
            } catch (_: Exception) {
                // Stream closed by the kill below; whatever was read stands.
            }
        }, "deep-shell-reader").apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_GRACE_MILLIS)
        if (!finished) process.waitFor(READER_GRACE_MILLIS, TimeUnit.MILLISECONDS)

        val text = StringBuilder(buffer.toString(Charsets.UTF_8.name()).trimEnd())
        when {
            !finished -> text.append("\n[timed out]")
            truncated -> text.append("\n[truncated]")
            else -> runCatching { process.exitValue() }.getOrNull()?.takeIf { it != 0 }?.let { text.append("\n[exit $it]") }
        }
        return text.toString().trim()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 2 * 1024 * 1024
        private const val READER_GRACE_MILLIS = 1_000L

        /** Shell-safe check for package names and other arguments interpolated into commands. */
        private val safeArgument = Regex("""^[A-Za-z0-9_.]+$""")

        fun isSafeArgument(value: String): Boolean = safeArgument.matches(value)

        /** The first non-empty line of a command's output for the UI, or [empty] when there is none. */
        fun oneLine(output: String, empty: String = "Done."): String {
            val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return empty
            return if (line.length > 160) line.take(159) + "…" else line
        }
    }
}
