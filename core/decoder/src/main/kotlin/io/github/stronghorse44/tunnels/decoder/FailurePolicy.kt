package io.github.stronghorse44.tunnels.decoder

/**
 * Nothing retries on its own, and a file that keeps breaking the reader stops being sent to it (audit M6). Counts only
 * [DecodeStatus.counts] failures (crash, timeout, bad reply):
 * - [PER_FILE] of them on one staged file refuse that file ([DecodeStatus.REFUSED_FILE]);
 * - [PAUSE_AFTER] of them within [WINDOW_MS], on any files, pause the reader until the app process restarts
 *   ([DecodeStatus.PAUSED]), so a stream of share intents carrying a crashing file cannot keep spawning decoders.
 * Held in memory only; nothing is stored. Thread-safe.
 */
class FailurePolicy(private val clock: () -> Long) {
    private val perFile = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_FILES
    }
    private val recent = ArrayDeque<Long>()
    private var paused = false

    /** Why [key] must not be sent to the reader now, or null when it may be. */
    @Synchronized
    fun refusal(key: String): DecodeStatus? = when {
        paused -> DecodeStatus.PAUSED
        (perFile[key] ?: 0) >= PER_FILE -> DecodeStatus.REFUSED_FILE
        else -> null
    }

    /** Notes how a job on [key] ended. Only counted failures change anything. */
    @Synchronized
    fun record(key: String, status: DecodeStatus) {
        if (!status.counts) return
        perFile[key] = (perFile[key] ?: 0) + 1
        val now = clock()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MS) recent.removeFirst()
        if (recent.size >= PAUSE_AFTER) paused = true
    }

    @get:Synchronized
    val isPaused: Boolean get() = paused

    companion object {
        const val PER_FILE = 2
        const val PAUSE_AFTER = 5
        const val WINDOW_MS = 10L * 60 * 1000

        /** Bounds the per-file map; a forgotten file is a new staged copy in practice (each open stages anew). */
        private const val MAX_FILES = 256
    }
}
