package io.github.stronghorse44.tunnels.deepmode

/**
 * One op from `appops get`: the effective mode (uid-level when printed, else package-level) plus how
 * long ago it was last used or rejected.
 */
data class OpEntry(
    val op: String,
    val mode: String,
    /** Milliseconds since the last access, or null when the op was never used. */
    val lastAccessAgo: Long?,
    val lastRejectAgo: Long? = null,
    val running: Boolean = false,
    /** The package-level mode when a uid-level mode overrode it; null when [mode] is the package line's own. */
    val packageMode: String? = null,
)

/**
 * Parsers for the text `appops` and `dumpsys appops` print. Plain Kotlin; tolerant of lines it does
 * not know, because the format is not a stable API and GrapheneOS may add to it.
 */
object AppOpsParser {
    /** Marker the batch command prints before each package's `appops get` output. */
    const val PACKAGE_MARKER = "## "

    private val opLine = Regex("""^([A-Z_0-9]+): (allow|ignore|deny|default|foreground|errored)\b(.*)$""")
    private val uidModeLine = Regex("""^Uid mode: ([A-Z_0-9]+): (allow|ignore|deny|default|foreground|errored)\b.*$""")
    private val timeField = Regex("""\btime=([+-]?[0-9a-z]+) ago""")
    private val rejectField = Regex("""\brejectTime=([+-]?[0-9a-z]+) ago""")
    private val durationPart = Regex("""(\d+)(ms|d|h|m|s)""")

    private val packageLine = Regex("""^\s*Package ([A-Za-z0-9_.]+):""")
    private val dumpOpLine = Regex("""^\s*([A-Z_0-9]+) \((\w+)""")
    private val accessLine = Regex("""^\s*Access: \[([a-z]+)-[a-z]+] \S+ \S+ \(([+-]?[0-9a-z]+)\)""")

    /** Uid states `dumpsys appops` prints for processes the user is not looking at. */
    private val backgroundStates = setOf("bg", "cch")

    /**
     * Parses one package's `appops get --user 0 <pkg>` output into op -> entry.
     *
     * Two kinds of line matter. `CAMERA: allow; time=+2h13m40s503ms ago; duration=+1s500ms` is the
     * package-level op: its mode, last access, last reject and `(running)`. `Uid mode: CAMERA: ignore` is
     * the uid-level op mode. Since Android 10 the system mirrors runtime permissions (camera, microphone,
     * location, contacts, SMS...) into app ops at the uid level, so when a uid mode is printed it is the
     * effective one and overrides the package line's mode, which usually stays at its default. Times
     * still come from the package line; an op with only a uid-mode line has no access time. `No
     * operations.` and anything unknown are skipped. With [only] set, other ops are dropped.
     */
    fun parseGet(text: String, only: Collection<String>? = null): Map<String, OpEntry> {
        val out = LinkedHashMap<String, OpEntry>()
        val uidModes = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val uid = uidModeLine.matchEntire(line)
            if (uid != null) {
                val op = uid.groupValues[1]
                if (only == null || op in only) uidModes[op] = uid.groupValues[2]
                continue
            }
            val m = opLine.matchEntire(line) ?: continue
            val op = m.groupValues[1]
            if (only != null && op !in only) continue
            val rest = m.groupValues[3]
            out[op] = OpEntry(
                op = op,
                mode = m.groupValues[2],
                lastAccessAgo = timeField.find(rest)?.let { parseDuration(it.groupValues[1]) },
                lastRejectAgo = rejectField.find(rest)?.let { parseDuration(it.groupValues[1]) },
                running = rest.contains("(running)"),
            )
        }
        for ((op, mode) in uidModes) {
            val pkgEntry = out[op]
            out[op] = if (pkgEntry == null) OpEntry(op = op, mode = mode, lastAccessAgo = null)
            else pkgEntry.copy(mode = mode, packageMode = pkgEntry.mode)
        }
        return out
    }

    /** Splits the output of a batch command (`## pkg` marker lines) into package -> its own output. */
    fun splitBatch(text: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in text.lineSequence()) {
            if (line.startsWith(PACKAGE_MARKER)) {
                val pkg = line.removePrefix(PACKAGE_MARKER).trim()
                current = StringBuilder().also { out[pkg] = it }
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return out.mapValues { it.value.toString() }
    }

    /**
     * Parses Android's `TimeUtils.formatDuration` text ("+2d3h4m5s678ms", "-1h", "0") into milliseconds,
     * ignoring the sign. Null for anything that is not a duration.
     */
    fun parseDuration(text: String): Long? {
        val body = text.trim().trimStart('+', '-')
        if (body == "0") return 0
        var total = 0L
        var consumed = 0
        for (m in durationPart.findAll(body)) {
            if (m.range.first != consumed) return null
            consumed = m.range.last + 1
            val n = m.groupValues[1].toLongOrNull() ?: return null
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                else -> n
            }
        }
        return if (consumed == body.length && consumed > 0) total else null
    }

    /**
     * Parses `dumpsys appops --op <OP>` and returns, per package, the most recent access made while the
     * app was in the background (uid state `bg` or `cch`), as milliseconds ago. Packages whose history
     * holds only foreground accesses are absent.
     */
    fun parseDumpsysBackground(text: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        var pkg: String? = null
        for (line in text.lineSequence()) {
            val header = packageLine.find(line)
            if (header != null) {
                pkg = header.groupValues[1]
                continue
            }
            val p = pkg ?: continue
            val a = accessLine.find(line) ?: continue
            if (a.groupValues[1] !in backgroundStates) continue
            val ago = parseDuration(a.groupValues[2]) ?: continue
            out[p] = minOf(out[p] ?: Long.MAX_VALUE, ago)
        }
        return out
    }

    /** Which op name `dumpsys appops --op <OP>` output is about, from its op header lines; null if none. */
    fun dumpsysOps(text: String): Set<String> =
        text.lineSequence().mapNotNull { dumpOpLine.find(it)?.groupValues?.get(1) }.toSet()
}
