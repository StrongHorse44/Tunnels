package io.github.stronghorse44.tunnels.deepmode

/** Parsers for `settings list <table>` and `cmd package list packages`. Plain Kotlin. */
object SettingsParser {
    private val packageLine = Regex("""^package:(\S+)""")

    /**
     * `key=value` lines filtered to [allow]. Values are trimmed and capped at [DeepKeys.MAX_SETTING_VALUE]
     * characters; "null" stays as the string Android prints. Keys outside the allowlist are dropped so
     * nothing unexpected (such as a Wi-Fi name in some vendor key) reaches the store.
     */
    fun parseList(text: String, allow: Set<String> = DeepKeys.WATCHED_SETTINGS): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val cut = raw.indexOf('=')
            if (cut <= 0) continue
            val key = raw.substring(0, cut).trim()
            if (key !in allow) continue
            val value = raw.substring(cut + 1).trim()
            out[key] = if (value.length > DeepKeys.MAX_SETTING_VALUE) value.take(DeepKeys.MAX_SETTING_VALUE - 1) + "…" else value
        }
        return out
    }

    /** Number of `package:<name>` lines, e.g. from `cmd package list packages -d`. */
    fun countPackages(text: String): Int = text.lineSequence().count { packageLine.containsMatchIn(it.trim()) }

    /** Package names from `package:<name>` lines. */
    fun packages(text: String): List<String> =
        text.lineSequence().mapNotNull { packageLine.find(it.trim())?.groupValues?.get(1) }.toList()

    /**
     * Readable names for `enabled_accessibility_services`: "com.example.app/com.example.app.svc.MyService"
     * becomes "com.example.app/.svc.MyService" (like ComponentName.flattenToShortString); entries are `:`-separated.
     */
    fun accessibilityServices(value: String?): List<String> {
        if (value.isNullOrBlank() || value == "null") return emptyList()
        return value.split(':').map { it.trim() }.filter { it.isNotEmpty() }.map { component ->
            val slash = component.indexOf('/')
            if (slash <= 0) return@map component
            val pkg = component.substring(0, slash)
            val cls = component.substring(slash + 1)
            val short = if (cls.startsWith("$pkg.")) cls.substring(pkg.length) else "." + cls.substringAfterLast('.')
            "$pkg/$short"
        }
    }
}
