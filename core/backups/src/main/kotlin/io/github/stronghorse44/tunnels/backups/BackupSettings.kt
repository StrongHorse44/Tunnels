package io.github.stronghorse44.tunnels.backups

import java.time.LocalDate

/**
 * What the user chose, apart from the folder itself. One encrypted setting, [KEY]; the folder's tree URI is its own
 * setting, [FOLDER_KEY]. Neither is part of a Tunnels export bundle: a folder grant belongs to one install.
 *
 * Only tracked apps can go stale or missing. An app is tracked when the user said so ([overrides]) or, by default,
 * when one of its bundles has been seen in the folder ([seen]).
 */
data class BackupSettings(
    val thresholdDays: Int = DEFAULT_THRESHOLD_DAYS,
    /** The day of the last restore drill, as the user set it; null until they do. */
    val drill: LocalDate? = null,
    /** App ID to true (track) or false (do not track), overriding the default. */
    val overrides: Map<String, Boolean> = emptyMap(),
    /** App IDs whose bundles have been found in the folder at some scan. */
    val seen: Set<String> = emptySet(),
) {
    fun isTracked(appId: String, foundNow: Boolean = false): Boolean = overrides[appId] ?: (foundNow || appId in seen)

    fun withTracked(appId: String, tracked: Boolean) = copy(overrides = overrides + (appId to tracked))

    fun encode(): String = listOf(
        "days" to thresholdDays.toString(),
        "drill" to (drill?.toString() ?: ""),
        "track" to overrides.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${if (it.value) "on" else "off"}" },
        "seen" to seen.sorted().joinToString(","),
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        const val KEY = "backups.config"
        const val FOLDER_KEY = "backups.folder"

        const val DEFAULT_THRESHOLD_DAYS = 30
        val THRESHOLDS: List<Int> = listOf(1, 7, 14, 30, 60, 90, 180)

        /** A restore drill is due this many days after the last one. */
        const val DRILL_EVERY_DAYS = 90

        /** Anything missing, unknown or damaged falls back to the default, so a bad value never blocks the screen. */
        fun decode(s: String?): BackupSettings {
            val f = s.orEmpty().split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }.toMap()
            fun list(v: String?) = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return BackupSettings(
                thresholdDays = f["days"]?.toIntOrNull()?.takeIf { it in THRESHOLDS } ?: DEFAULT_THRESHOLD_DAYS,
                drill = f["drill"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                overrides = list(f["track"]).mapNotNull { item ->
                    val id = item.substringBefore(':')
                    when (item.substringAfter(':', "")) {
                        "on" -> id.takeIf(BackupApps::isKnown)?.let { it to true }
                        "off" -> id.takeIf(BackupApps::isKnown)?.let { it to false }
                        else -> null
                    }
                }.toMap(),
                seen = list(f["seen"]).filter(BackupApps::isKnown).toSet(),
            )
        }
    }
}
