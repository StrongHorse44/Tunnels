package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Duration
import java.time.Instant

/**
 * What a background check runs and when it speaks up. Offline only: every tunnel here reads the phone itself.
 * Session tunnels (Traffic, Home network), radio tunnels (Surroundings) and Explore never run in the background.
 */
object WatchPolicy {
    /** The JobScheduler id of the periodic check. */
    const val JOB_ID = 4201

    /** Cheap, and their state changes without any app update: a permission granted, a CA added, an OTA, an export that ages (Backups reads only a few hundred header bytes per file). */
    val ALWAYS: List<String> = listOf("permissions", "trust_store", "system_packages", "silicon", "backups")

    /** Read every app's files: run after package changes, after a reboot, and at least once every [APP_FILES_MAX_AGE]. */
    val APP_FILES: List<String> = listOf("apk_excavation", "doors", "hardening")

    /** Need special access and move all day, so every check would store a snapshot: the user opts in. */
    val OPTIONAL: List<String> = listOf("timeline", "notifications")

    val INTERVALS_HOURS: List<Int> = listOf(6, 12, 24)
    const val DEFAULT_INTERVAL_HOURS = 12
    val APP_FILES_MAX_AGE: Duration = Duration.ofHours(24)

    /** Severities a notification threshold may be set to. */
    val NOTIFY_LEVELS: List<Severity> = listOf(Severity.NOTICE, Severity.WARN, Severity.CRITICAL)

    data class Plan(val tunnels: List<String>, val appFiles: Boolean, val reason: String)

    /**
     * The tunnels for one check. [available] are registered tunnels whose access is granted; [packagesChanged] says
     * whether PackageManager reports installs, updates or removals since the last check (true when unknown).
     */
    fun plan(available: Set<String>, settings: WatchSettings, status: WatchStatus, packagesChanged: Boolean, bootCount: Int, now: Instant): Plan {
        val rebooted = status.bootCount >= 0 && bootCount >= 0 && bootCount != status.bootCount
        val stale = status.lastAppFilesAt <= 0L || now.toEpochMilli() - status.lastAppFilesAt >= APP_FILES_MAX_AGE.toMillis()
        val reason = when {
            status.neverRan -> "first check"
            rebooted -> "after a restart"
            packagesChanged -> "apps changed"
            stale -> "daily app-file check"
            else -> null
        }
        val appFiles = reason != null
        val ids = ALWAYS + (if (appFiles) APP_FILES else emptyList()) + OPTIONAL.filter { it in settings.extra }
        return Plan(ids.filter { it in available }, appFiles, reason ?: "quick check")
    }

    /** New findings worth a notification, most severe first. */
    fun toNotify(added: List<Finding>, threshold: Severity): List<Finding> =
        added.filter { it.severity >= threshold }.sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.tunnelId }.thenBy { it.subject })

    /**
     * The notification for [findings], or null for none. The private text names tunnels and kinds, never apps or
     * evidence; the lock screen gets only [WatchNotification.publicText].
     */
    fun notification(findings: List<Finding>, titleOf: (String) -> String): WatchNotification? {
        if (findings.isEmpty()) return null
        val n = findings.size
        val counts = findings.groupingBy { it.severity }.eachCount()
        val summary = Severity.entries.reversed().mapNotNull { s -> counts[s]?.let { severityCount(it, s) } }.joinToString(", ")
        val lines = findings.take(MAX_LINES).map { "${titleOf(it.tunnelId)}: ${kindLabel(it.kind)}" } +
            (if (n > MAX_LINES) listOf("and ${n - MAX_LINES} more") else emptyList())
        return WatchNotification(
            title = if (n == 1) "A new finding to review" else "$n new findings to review",
            text = summary,
            lines = lines,
            publicTitle = "Tunnels",
            publicText = "New findings to review",
        )
    }

    private const val MAX_LINES = 5

    /** "1 critical", "2 warnings", "1 notice". */
    fun severityCount(n: Int, s: Severity): String {
        val word = when (s) {
            Severity.CRITICAL -> "critical"
            Severity.WARN -> if (n == 1) "warning" else "warnings"
            Severity.NOTICE -> if (n == 1) "notice" else "notices"
            Severity.INFO -> "info"
        }
        return "$n $word"
    }

    /** "USER_CA_INSTALLED" -> "User CA installed". */
    fun kindLabel(kind: String): String =
        kind.split('_').joinToString(" ") { w -> if (w.length <= 3 && w in SHOUTED) w else w.lowercase() }.replaceFirstChar { it.uppercase() }

    private val SHOUTED = setOf("CA", "SDK", "DNS", "ADB", "OTA", "UPNP", "IGD", "OWE", "ABI", "IPC")
}

data class WatchNotification(val title: String, val text: String, val lines: List<String>, val publicTitle: String, val publicText: String)
