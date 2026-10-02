package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Severity

/** The user's choices for background checks. One encrypted setting, [KEY]. */
data class WatchSettings(
    val enabled: Boolean = false,
    val intervalHours: Int = WatchPolicy.DEFAULT_INTERVAL_HOURS,
    /** The lowest severity a new finding needs to raise a notification. */
    val notifyAt: Severity = Severity.WARN,
    /** Opt-in tunnels from [WatchPolicy.OPTIONAL]. */
    val extra: Set<String> = emptySet(),
) {
    fun encode(): String = Codec.encode(
        listOf(
            "enabled" to enabled.toString(),
            "interval" to intervalHours.toString(),
            "notify" to notifyAt.name,
            "extra" to extra.sorted().joinToString(","),
        ),
    )

    companion object {
        const val KEY = "watch.settings"

        /** Anything missing or unknown falls back to the defaults, so an older or damaged value never blocks the screen. */
        fun decode(s: String?): WatchSettings {
            val f = Codec.decode(s)
            val interval = f["interval"]?.toIntOrNull()?.takeIf { it in WatchPolicy.INTERVALS_HOURS } ?: WatchPolicy.DEFAULT_INTERVAL_HOURS
            val notify = f["notify"]?.let { n -> Severity.entries.firstOrNull { it.name == n } }?.takeIf { it in WatchPolicy.NOTIFY_LEVELS } ?: Severity.WARN
            return WatchSettings(
                enabled = f["enabled"] == "true",
                intervalHours = interval,
                notifyAt = notify,
                extra = Codec.list(f["extra"]).filter { it in WatchPolicy.OPTIONAL }.toSet(),
            )
        }
    }
}

/** What the last check did. Times, counts and tunnel ids only. One encrypted setting, [KEY]. */
data class WatchStatus(
    /** Epoch millis of the last finished check; 0 before the first. */
    val lastRunAt: Long = 0L,
    /** Epoch millis of the last check that read every app's files. */
    val lastAppFilesAt: Long = 0L,
    /** Settings.Global.BOOT_COUNT at the last check; -1 unknown. */
    val bootCount: Int = -1,
    /** PackageManager's change sequence number at the last check; -1 unknown. */
    val packageSequence: Int = -1,
    /** Tunnels the last check ran. */
    val tunnels: Int = 0,
    /** New findings the last check raised. */
    val added: Int = 0,
    /** Whether the last check changed anything and stored a snapshot. */
    val stored: Boolean = false,
    /** Tunnels that failed in the last check. */
    val failed: List<String> = emptyList(),
    /** Why the last check read every app's files, or ran only the quick tunnels. */
    val reason: String = "",
) {
    val neverRan: Boolean get() = lastRunAt <= 0L

    fun encode(): String = Codec.encode(
        listOf(
            "run" to lastRunAt.toString(),
            "files" to lastAppFilesAt.toString(),
            "boot" to bootCount.toString(),
            "seq" to packageSequence.toString(),
            "tunnels" to tunnels.toString(),
            "added" to added.toString(),
            "stored" to stored.toString(),
            "failed" to failed.joinToString(","),
            "reason" to reason.replace(',', ' '),
        ),
    )

    companion object {
        const val KEY = "watch.status"

        fun decode(s: String?): WatchStatus {
            val f = Codec.decode(s)
            return WatchStatus(
                lastRunAt = f["run"]?.toLongOrNull() ?: 0L,
                lastAppFilesAt = f["files"]?.toLongOrNull() ?: 0L,
                bootCount = f["boot"]?.toIntOrNull() ?: -1,
                packageSequence = f["seq"]?.toIntOrNull() ?: -1,
                tunnels = f["tunnels"]?.toIntOrNull() ?: 0,
                added = f["added"]?.toIntOrNull() ?: 0,
                stored = f["stored"] == "true",
                failed = Codec.list(f["failed"]),
                reason = f["reason"].orEmpty(),
            )
        }
    }
}
