package io.github.stronghorse44.tunnels.emissions

/**
 * Which apps used a radio recently, from the app-op history (`dumpsys appops --op <OP>`, the format Deep mode reads).
 * Package names stay in memory for the screen; only the counts per kind are stored. Plain Kotlin.
 */
object RadioUse {
    /** "Recently" for the screen and the counts. */
    const val RECENT_MINUTES = 15L
    const val RECENT_MILLIS = RECENT_MINUTES * 60_000L

    enum class Kind(val id: String, val title: String, val ops: List<String>) {
        BLUETOOTH("bluetooth", "Bluetooth", listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT")),
        WIFI("wifi", "Wi-Fi", listOf("NEARBY_WIFI_DEVICES", "WIFI_SCAN", "CHANGE_WIFI_STATE")),

        /** Not an emission (GNSS listens), but a location request can start Wi-Fi and Bluetooth scans. */
        LOCATION("location", "Location", listOf("MONITOR_HIGH_POWER_LOCATION", "MONITOR_LOCATION")),
        UWB("uwb", "UWB", listOf("UWB_RANGING")),
        ;

        companion object {
            fun ofOp(op: String): Kind? = entries.firstOrNull { op in it.ops }
        }
    }

    /** One app's most recent use of one op: [agoMillis] since the last access, [running] when the op is active now. */
    data class Use(val packageName: String, val op: String, val agoMillis: Long?, val running: Boolean) {
        val kind: Kind? get() = Kind.ofOp(op)
        val recent: Boolean get() = running || (agoMillis != null && agoMillis <= RECENT_MILLIS)
    }

    fun command(op: String): String = "dumpsys appops --op $op"

    private val packageLine = Regex("""^\s*Package ([A-Za-z0-9_.]+):""")
    private val accessLine = Regex("""^\s*Access: \[[a-z]+-[a-z]+] \S+ \S+ \(([+-]?[0-9a-z]+)\)""")
    private val runningLine = Regex("""(?i)^\s*Running start at\b|\(running\)""")
    private val durationPart = Regex("""(\d+)(ms|d|h|m|s)""")

    /** Each package's most recent access to [op] in `dumpsys appops --op <op>` output. Unknown lines are skipped. */
    fun parse(op: String, text: String): List<Use> {
        val newest = LinkedHashMap<String, Long?>()
        val running = HashSet<String>()
        var pkg: String? = null
        for (line in text.lineSequence()) {
            val header = packageLine.find(line)
            if (header != null) {
                pkg = header.groupValues[1]
                newest.putIfAbsent(header.groupValues[1], null)
                continue
            }
            val p = pkg ?: continue
            if (runningLine.containsMatchIn(line)) running += p
            val ago = accessLine.find(line)?.let { parseDuration(it.groupValues[1]) } ?: continue
            newest[p] = minOf(newest[p] ?: Long.MAX_VALUE, ago)
        }
        return newest.map { (p, ago) -> Use(p, op, ago, p in running) }.filter { it.agoMillis != null || it.running }
    }

    /**
     * The recent uses per kind, one entry per app (its most recent op of that kind), newest first. [self] (Tunnels)
     * is left out: its own scans are not news.
     */
    fun recent(uses: List<Use>, self: String? = null): Map<Kind, List<Use>> =
        uses.filter { it.recent && it.packageName != self && it.kind != null }
            .groupBy { it.kind!! }
            .mapValues { (_, list) ->
                list.groupBy { it.packageName }.values
                    .map { perApp -> perApp.minWith(compareBy<Use>({ !it.running }, { it.agoMillis ?: Long.MAX_VALUE })) }
                    .sortedWith(compareBy<Use>({ !it.running }, { it.agoMillis ?: Long.MAX_VALUE }, { it.packageName }))
            }

    fun counts(recent: Map<Kind, List<Use>>): AppUseCounts = AppUseCounts(Kind.entries.associateWith { recent[it]?.size ?: 0 })

    /** "now", "2 min ago" or "under a minute ago" for the screen. */
    fun ago(use: Use): String = when {
        use.running -> "now"
        use.agoMillis == null -> "unknown"
        use.agoMillis < 60_000L -> "under a minute ago"
        else -> "${use.agoMillis / 60_000L} min ago"
    }

    /** Android's `TimeUtils.formatDuration` text ("+2h3m4s5ms", "-1m", "0") in milliseconds, sign ignored; null if not one. */
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
}
