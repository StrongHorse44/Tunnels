package io.github.stronghorse44.tunnels.archive

/** Turns archive entry names into safe relative path segments (blocks zip-slip). */
object SafePath {
    private const val MAX_SEGMENT_CHARS = 200
    private val illegal = Regex("""[\u0000-\u001f"*:<>?|]""")

    /**
     * Returns the sanitized segments, or null when the entry must be skipped: it climbs out with "..",
     * names a drive, or is empty. A leading "/" is treated as relative.
     */
    fun segments(name: String): List<String>? {
        if ('\u0000' in name) return null
        val parts = name.replace('\\', '/').split('/')
        val out = ArrayList<String>(parts.size)
        for ((i, raw) in parts.withIndex()) {
            when {
                raw.isEmpty() || raw == "." -> continue
                raw == ".." -> return null
                i == 0 && raw.length == 2 && raw[1] == ':' -> return null
                else -> {
                    val clean = raw.replace(illegal, "_").trim().take(MAX_SEGMENT_CHARS)
                    if (clean.isEmpty() || clean == "." || clean == "..") return null
                    out += clean
                }
            }
        }
        return out.ifEmpty { null }
    }
}
