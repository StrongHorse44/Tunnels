package io.github.stronghorse44.tunnels.model

import java.time.Instant

/** Layers of the cross-section, surface to core. EXPLORE is the side shaft for curiosity-only tunnels. */
enum class Stratum { SURFACE, TOPSOIL, BEDROCK, CORE, EXPLORE }

enum class Severity { INFO, NOTICE, WARN, CRITICAL }

/** A runtime permission a tunnel needs, with the one-line reason shown when it is requested. */
data class PermissionSpec(val permission: String, val reason: String)

/**
 * One fact a tunnel saw. Observations are the unit that snapshots store and the diff engine compares.
 * [value] is a normalized summary, never raw data.
 */
data class Observation(
    val tunnelId: String,
    val subject: String,
    val key: String,
    val value: String,
) {
    val identity: ObservationKey get() = ObservationKey(tunnelId, subject, key)
}

data class ObservationKey(val tunnelId: String, val subject: String, val key: String)

data class Snapshot(
    val id: Long,
    val takenAt: Instant,
    val pinned: Boolean,
)

/** Something the user can do about a finding. Every security finding carries at least one. */
sealed interface FindingAction {
    val label: String

    data class OpenAppDetails(val packageName: String) : FindingAction {
        override val label = "Open app settings"
    }

    data class RequestUninstall(val packageName: String) : FindingAction {
        override val label = "Uninstall"
    }

    /** A Settings screen, by `android.provider.Settings` action string. */
    data class OpenSettings(val action: String, override val label: String) : FindingAction
}

data class Finding(
    val tunnelId: String,
    val subject: String,
    val kind: String,
    val severity: Severity,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val evidence: String,
    val actions: List<FindingAction>,
) {
    /** Stable across scans so a recurring finding keeps its firstSeen. */
    val id: String get() = findingId(tunnelId, subject, kind)

    companion object {
        fun findingId(tunnelId: String, subject: String, kind: String) = "$tunnelId|$subject|$kind"
    }
}

interface TunnelModule {
    val id: String
    val stratum: Stratum
    val requiredPermissions: List<PermissionSpec>
    suspend fun scan(): List<Observation>
    fun actionsFor(finding: Finding): List<FindingAction>
}
