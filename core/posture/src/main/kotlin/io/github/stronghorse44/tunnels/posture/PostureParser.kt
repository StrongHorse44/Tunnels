package io.github.stronghorse44.tunnels.posture

/** What one command's output gave: the allowlisted keys it held, or the reason it cannot be trusted at all. */
sealed interface TableRead {
    /**
     * [values] holds allowlisted keys only. [conflicts] names keys that appeared twice with different values; the
     * reader treats them as malformed.
     */
    data class Ok(val values: Map<String, String>, val conflicts: Set<String> = emptySet()) : TableRead

    /** [reason] is one of [PostureParser.TIMED_OUT], [PostureParser.TRUNCATED], [PostureParser.EXIT], [PostureParser.ERROR], [PostureParser.NOT_RUN]. */
    data class Failed(val reason: String) : TableRead
}

/**
 * Parsers for `settings list <table>` and the getprop command. Partial output is never trusted: a table that timed
 * out, was cut, exited non-zero or starts with an error is [TableRead.Failed] as a whole. Plain Kotlin.
 */
object PostureParser {
    const val TIMED_OUT = "timed out"
    const val TRUNCATED = "truncated"
    const val EXIT = "exit"
    const val ERROR = "error"
    const val NOT_RUN = "not run"

    /** The read word of a table that parsed. */
    const val OK = "ok"

    /** A table the scan never ran. */
    val notRun: TableRead = TableRead.Failed(NOT_RUN)

    private val exitMarker = Regex("""^\[exit -?\d+]$""")

    // Copy of ShellRunner's error pattern (that class lives in the Android module, which this one cannot see).
    private val errorLine = Regex("""^(Error|Exception|java\.|Security|Unknown|Bad |Failure|\[exit |\[timed out]|\[error])""")

    /** Output of `settings list <table>`, limited to the keys in [allow]. */
    fun table(output: String?, allow: Set<String> = PostureKeys.SETTINGS_KEYS): TableRead {
        if (output == null) return TableRead.Failed(ERROR)
        val lines = output.lines()
        failure(lines)?.let { return it }
        val values = LinkedHashMap<String, String>()
        val conflicts = LinkedHashSet<String>()
        var anyPair = false
        for (raw in lines) {
            // A key starts the line: an indented line is the rest of a multi-line value, never a key.
            if (raw.isEmpty() || raw[0].isWhitespace()) continue
            val line = raw.trimEnd()
            val cut = line.indexOf('=')
            if (cut <= 0) continue
            anyPair = true
            val key = line.substring(0, cut)
            if (key !in allow) continue
            val value = line.substring(cut + 1).trim()
            val seen = values[key]
            if (seen != null && seen != value) conflicts += key
            values[key] = value
        }
        if (!anyPair) return TableRead.Failed(ERROR)
        return TableRead.Ok(values, conflicts)
    }

    /** Output of [PostureKeys.PROPS_COMMAND]: one `name=value` line per property, the value empty when unset. */
    fun props(output: String?): TableRead = table(output, PostureKeys.PROPS.toSet())

    private fun failure(lines: List<String>): TableRead.Failed? {
        val trimmed = lines.map { it.trim() }
        return when {
            trimmed.any { it == "[timed out]" } -> TableRead.Failed(TIMED_OUT)
            trimmed.any { it == "[truncated]" } -> TableRead.Failed(TRUNCATED)
            trimmed.any { exitMarker.matches(it) } -> TableRead.Failed(EXIT)
            trimmed.firstOrNull { it.isNotEmpty() }?.let { errorLine.containsMatchIn(it) } == true -> TableRead.Failed(ERROR)
            else -> null
        }
    }
}
