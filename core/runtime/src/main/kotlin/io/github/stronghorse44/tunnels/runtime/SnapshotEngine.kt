package io.github.stronghorse44.tunnels.runtime

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.engine.FindingsEngine
import io.github.stronghorse44.tunnels.engine.RetentionPolicy
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
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
    val snapshotId: Long,
    val observations: Int,
    val newFindings: Int,
    val clearedFindings: Int,
    val failures: Map<String, String>,
)

/**
 * Runs tunnel scans off the main thread, stores a snapshot, diffs each tunnel against the previous
 * snapshot that covered it, and re-derives that tunnel's findings through its rules.
 */
class SnapshotEngine(private val registry: TunnelRegistry, private val store: TunnelsStore) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    /** Scans [tunnelIds] (all registered modules when null). One scan at a time; later callers wait. */
    suspend fun scan(tunnelIds: Collection<String>? = null, now: Instant = Instant.now()): ScanResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val modules = (tunnelIds ?: registry.modules.keys).mapNotNull { registry[it] }
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
                val snapshotId = dao.insertSnapshot(SnapshotEntity(takenAt = now.toEpochMilli()))
                dao.insertObservations(
                    collected.values.flatten().map { ObservationEntity(snapshotId, it.tunnelId, it.subject, it.key, it.value) },
                )
                var added = 0
                var cleared = 0
                for (module in modules) {
                    val current = collected[module.id] ?: continue
                    val previousId = dao.latestSnapshotIdFor(module.id, before = snapshotId)
                    val previous = previousId?.let { dao.observations(it, module.id).map(ObservationEntity::toModel) }.orEmpty()
                    val context = RuleContext(module.id, current, DiffEngine.diff(previous, current), isFirstScan = previousId == null)
                    val existingEntities = dao.findingsFor(module.id)
                    val existing = existingEntities.map { it.toModel(module) }
                    val update = FindingsEngine.derive(context, module.rules, module::actionsFor, existing, now)
                    val existingIds = existingEntities.map { it.id }.toSet()
                    val dismissedIds = existingEntities.filter { it.dismissed }.map { it.id }.toSet()
                    added += update.upserts.count { it.id !in existingIds }
                    cleared += update.removals.size
                    // A dismissed finding that still holds stays dismissed rather than popping back up.
                    dao.upsertFindings(update.upserts.map { it.toEntity(dismissed = it.id in dismissedIds) })
                    if (update.removals.isNotEmpty()) dao.deleteFindings(update.removals)
                }
                store.maintain(now)
                ScanResult(snapshotId, collected.values.sumOf { it.size }, added, cleared, failures)
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
