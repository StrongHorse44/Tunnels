package io.github.stronghorse44.tunnels.model

import java.time.Instant

/** Layers of the cross-section, surface to core. EXPLORE is the side shaft for curiosity-only tunnels. */
enum class Stratum { SURFACE, TOPSOIL, BEDROCK, CORE, EXPLORE }

enum class Severity { INFO, NOTICE, WARN, CRITICAL }

/** A runtime permission a tunnel needs, with the one-line reason shown when it is requested. */
data class PermissionSpec(val permission: String, val reason: String)

/**
 * Access the user grants from a Settings screen rather than a permission dialog (Usage Access,
 * notification listener, VPN consent, Shizuku). [isGranted] is re-checked whenever the tunnel resumes.
 */
data class SpecialAccess(
    val id: String,
    val label: String,
    val reason: String,
    /** `android.provider.Settings` action string, or a package-qualified intent action. */
    val settingsAction: String,
    val isGranted: () -> Boolean,
    /** A Settings screen for this app alone, opened instead of [settingsAction]'s list when the phone has it. */
    val direct: DirectSettings? = null,
    /**
     * A "restricted setting": for an app installed from a downloaded file, Android refuses to switch it on
     * ("App was denied access") until the user allows restricted settings in the app's App info screen.
     */
    val restricted: Boolean = false,
)

/** A Settings screen for one app: [action], with this app's `package:` URI as data and/or string [extras]. */
data class DirectSettings(
    val action: String,
    val packageUri: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
)

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

    /** An action the tunnel performs itself (e.g. Deep mode revoking a permission). Returns a one-line result. */
    class Perform(override val label: String, val destructive: Boolean = false, val run: suspend () -> String) : FindingAction
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
    /** Change findings (something appeared or changed) stay until dismissed or expired; state findings clear when the state does. */
    val sticky: Boolean = false,
) {
    /** Stable across scans so a recurring finding keeps its firstSeen. */
    val id: String get() = findingId(tunnelId, subject, kind)

    companion object {
        fun findingId(tunnelId: String, subject: String, kind: String) = "$tunnelId|$subject|$kind"
    }
}

/** What a rule says about one subject, before timestamps and actions are attached. */
data class FindingDraft(
    val tunnelId: String,
    val subject: String,
    val kind: String,
    val severity: Severity,
    val evidence: String,
    val sticky: Boolean = false,
)

sealed interface DiffEntry {
    val key: ObservationKey

    data class Added(val observation: Observation) : DiffEntry {
        override val key get() = observation.identity
    }

    data class Removed(val observation: Observation) : DiffEntry {
        override val key get() = observation.identity
    }

    data class Changed(val before: Observation, val after: Observation) : DiffEntry {
        override val key get() = after.identity
    }
}

/** Everything a rule may look at for one tunnel after a scan. */
data class RuleContext(
    val tunnelId: String,
    /** This scan's observations for the tunnel. */
    val current: List<Observation>,
    /** Changes since the previous snapshot that covered this tunnel; empty on the first scan. */
    val diff: List<DiffEntry>,
    val isFirstScan: Boolean,
) {
    fun bySubject(): Map<String, List<Observation>> = current.groupBy { it.subject }
}

/** Turns a tunnel's observations (and their changes) into finding drafts. */
fun interface FindingRule {
    fun evaluate(context: RuleContext): List<FindingDraft>
}

fun interface ScanProgress {
    fun report(done: Int, total: Int, label: String)

    companion object {
        val NONE = ScanProgress { _, _, _ -> }
    }
}

interface TunnelModule {
    val id: String
    val info: TunnelInfo get() = TunnelCatalog.byId(id) ?: error("Unknown tunnel $id")
    val requiredPermissions: List<PermissionSpec>
    val specialAccess: List<SpecialAccess> get() = emptyList()
    suspend fun scan(progress: ScanProgress): List<Observation>
    val rules: List<FindingRule>
    fun actionsFor(draft: FindingDraft): List<FindingAction>
}

/**
 * A tunnel that reads other tunnels' latest observations and open findings instead of the phone. The snapshot
 * engine runs it after the tunnels it reads, inside the same snapshot, whenever one of them was scanned (or when
 * it is scanned by name), so its findings never lag the data they join. Its observations are stored and diffed
 * like any other tunnel's, and its [rules] turn them into findings.
 */
interface DerivedTunnel : TunnelModule {
    /** Tunnel ids whose observations and findings [derive] reads. */
    val sources: Set<String>

    /** Observations of this tunnel (tunnelId = [id]) computed from [input]. Pure: no system calls. */
    fun derive(input: DerivedInput): List<Observation>

    /** Never called: the engine calls [derive] with the sources' data instead. */
    override suspend fun scan(progress: ScanProgress): List<Observation> = emptyList()
}

/** One source tunnel's latest observations and when they were taken. */
data class SourceData(val observations: List<Observation>, val takenAt: Instant)

data class DerivedInput(
    /** Per source tunnel that has data: this scan's observations when it ran now, else its newest stored snapshot's. */
    val sources: Map<String, SourceData>,
    /** Open (not dismissed) findings of the source tunnels as they stand after this scan. Actions are not attached. */
    val findings: List<Finding>,
    val now: Instant,
)
