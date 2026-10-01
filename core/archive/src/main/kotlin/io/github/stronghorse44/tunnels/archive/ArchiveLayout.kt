package io.github.stronghorse44.tunnels.archive

import java.io.OutputStream

object ArchiveLayout {
    /**
     * The single top-level folder every entry lives under (e.g. "pusher" for pusher/a.py, pusher/b/c.js),
     * or null when entries sit at the root or under several folders. Unsafe entries are ignored.
     */
    fun singleRoot(entries: List<ArchiveEntry>): String? {
        var root: String? = null
        var sawNested = false
        for (e in entries) {
            val segments = SafePath.segments(e.path) ?: continue
            if (segments.size == 1 && !e.isDirectory) return null
            if (root == null) root = segments[0] else if (root != segments[0]) return null
            if (segments.size > 1) sawNested = true
        }
        return if (sawNested) root else null
    }
}

/** Drops the first path segment, for extracting a single-root archive straight into its own folder. */
class StripFirstSegmentSink(private val inner: ExtractSink) : ExtractSink {
    override fun directory(segments: List<String>) {
        val rest = segments.drop(1)
        if (rest.isNotEmpty()) inner.directory(rest)
    }

    override fun file(segments: List<String>): OutputStream = inner.file(segments.drop(1))
}
