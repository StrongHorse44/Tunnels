package io.github.stronghorse44.tunnels.runtime

import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import io.github.stronghorse44.tunnels.engine.ScanPlanner
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.SourceData
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.store.FindingEntity
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Live progress of a scan, for any screen that wants to show it. */
data class ScanState(
    val running: Boolean = false,
    val tunnelId: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val label: String = "",
    /** Items the current tunnel has read (apps, libraries, certificates) out of [itemsTotal]; 0 of 0 until it reports. */
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    /** What the current tunnel is reading now, without the tunnel's name or the counts. */
    val item: String = "",
)

data class ScanResult(
    /** The stored snapshot, or [NOT_STORED] when nothing changed and the caller asked not to store an unchanged one. */
    val snapshotId: Long,
    val observations: Int,
    val newFindings: Int,
    val clearedFindings: Int,
    val failures: Map<String, String>,
    /** Findings this scan raised that did not exist before, derived tunnels' included. */
    val added: List<Finding> = emptyList(),
) {
    val stored: Boolean get() = snapshotId != NOT_STORED

    companion object {
        const val NOT_STORED = -1L
    }
}

/**
 * Runs tunnel scans off the main thread, diffs each tunnel against the newest snapshot that covered it, re-derives
 * its findings through its rules, then runs the derived tunnels ([DerivedTunnel], such as Crossroads) whose sources
 * were scanned, and stores it all as one snapshot. The planning is [ScanPlanner]'s; this class reads and writes.
 */
class SnapshotEngine(private val registry: TunnelRegistry, private val store: TunnelsStore) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    /**
     * Scans [tunnelIds] (all registered modules when null). One scan at a time; later callers wait. With
     * [storeIfUnchanged] false (background checks), a scan that changed no observation stores no snapshot, so
     * routine checks do not push the user's snapshots out of the retention window.
     */
    suspend fun scan(tunnelIds: Collection<String>? = null, now: Instant = Instant.now(), storeIfUnchanged: Boolean = true): ScanResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val requested = (tunnelIds ?: registry.modules.keys).mapNotNull { registry[it] }
            val modules = requested.filterNot { it is DerivedTunnel }
            val failures = mutableMapOf<String, String>()
            val collected = mutableMapOf<String, List<Observation>>()
            modules.forEachIndexed { index, module ->
                _state.value = ScanState(true, module.id, index, modules.size, module.info.title)
                val progress = ScanProgress { done, total, label ->
                    _state.value = ScanState(true, module.id, index, modules.size, "${module.info.title}: $label ($done/$total)", done, total, label)
                }
                try {
                    // A radio or system service that hangs must not stall the whole snapshot.
                    val observations = withTimeoutOrNull(SCAN_TIMEOUT_MS) { module.scan(progress) }
                    if (observations == null) {
                        failures[module.id] = "timed out after ${SCAN_TIMEOUT_MS / 1000} s"
                    } else {
                        collected[module.id] = observations.filter { it.tunnelId == module.id }
                    }
                } catch (e: Exception) {
                    failures[module.id] = e.message ?: e.javaClass.simpleName
                }
            }
            try {
                val dao = store.dao
                // A derived tunnel runs when asked for by name or when one of its sources was just scanned.
                val derived = registry.modules.values.filterIsInstance<DerivedTunnel>()
                    .filter { d -> d in requested || d.sources.any { it in collected } }
                derived.firstOrNull()?.let { _state.value = ScanState(true, it.id, modules.size, modules.size, "${it.info.title}: joining tunnels") }
                val involved = collected.keys + derived.map { it.id } + derived.flatMap { it.sources }
                val previous = HashMap<String, SourceData>()
                for (id in involved) {
                    val snapshotId = dao.latestSnapshotIdFor(id) ?: continue
                    val takenAt = dao.snapshot(snapshotId)?.takenAt ?: continue
                    previous[id] = SourceData(dao.observations(snapshotId, id).map(ObservationEntity::toModel), Instant.ofEpochMilli(takenAt))
                }
                val entities = dao.findings()
                val dismissed = entities.filter { it.dismissed }.mapTo(HashSet()) { it.id }
                val plan = ScanPlanner.plan(
                    scanned = modules.mapNotNull { m -> collected[m.id]?.let { m to it } },
                    derived = derived,
                    previous = previous,
                    // Rules only need ids, firstSeen and stickiness of what exists; actions are attached on display.
                    existing = entities.map { it.toModel(null) },
                    dismissed = dismissed,
                    now = now,
                )
                failures += plan.failures
                val storeSnapshot = storeIfUnchanged || plan.observationsChanged
                val snapshotId = if (storeSnapshot) {
                    val id = dao.insertSnapshot(SnapshotEntity(takenAt = now.toEpochMilli()))
                    dao.insertObservations(plan.observations.map { ObservationEntity(id, it.tunnelId, it.subject, it.key, it.value) })
                    id
                } else {
                    ScanResult.NOT_STORED
                }
                if (storeSnapshot || plan.findingsChanged) {
                    // A dismissed finding that still holds stays dismissed rather than popping back up.
                    dao.upsertFindings(plan.upserts.map { it.toEntity(dismissed = it.id in dismissed) })
                    if (plan.removals.isNotEmpty()) dao.deleteFindings(plan.removals)
                }
                store.maintain(now)
                ScanResult(snapshotId, plan.observations.size, plan.added.size, plan.removals.size, failures, plan.added)
            } finally {
                _state.value = ScanState()
            }
        }
    }

    companion object {
        /** Generous: the home-network scan alone budgets about 70 s. */
        const val SCAN_TIMEOUT_MS = 150_000L

        fun FindingEntity.toModel(module: TunnelModule?): Finding = Finding(
            tunnelId = tunnelId,
            subject = subject,
            kind = kind,
            severity = runCatching { Severity.valueOf(severity) }.getOrDefault(Severity.INFO),
            firstSeen = Instant.ofEpochMilli(firstSeen),
            lastSeen = Instant.ofEpochMilli(lastSeen),
            evidence = evidence,
            actions = module?.actionsFor(io.github.stronghorse44.tunnels.model.FindingDraft(tunnelId, subject, kind, Severity.INFO, evidence, sticky)).orEmpty(),
            sticky = sticky,
        )

        fun Finding.toEntity(dismissed: Boolean = false) = FindingEntity(
            id = id,
            tunnelId = tunnelId,
            subject = subject,
            kind = kind,
            severity = severity.name,
            firstSeen = firstSeen.toEpochMilli(),
            lastSeen = lastSeen.toEpochMilli(),
            evidence = evidence,
            sticky = sticky,
            dismissed = dismissed,
        )
    }
}

/** Snapshot retention is applied by [TunnelsStore.maintain]; exposed here for the snapshots tunnel. */
val snapshotRetention: Int get() = RetentionPolicy.KEEP_SNAPSHOTS
