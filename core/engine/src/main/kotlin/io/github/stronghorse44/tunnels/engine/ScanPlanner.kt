package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.SourceData
import io.github.stronghorse44.tunnels.model.TunnelModule
import java.time.Instant

/** What one scan should write. The snapshot engine reads the store, asks [ScanPlanner] for this, and executes it. */
data class ScanPlan(
    /** Observations for the new snapshot: every scanned tunnel's, then every derived tunnel's. */
    val observations: List<Observation>,
    /**
     * True when some tunnel's observations differ from its newest stored snapshot (or it had none stored yet) in a key
     * other than its [TunnelModule.volatileKeys].
     */
    val observationsChanged: Boolean,
    /** Findings to insert or refresh. */
    val upserts: List<Finding>,
    /** Ids of findings that no longer hold. */
    val removals: List<String>,
    /** The upserts that did not exist before this scan. */
    val added: List<Finding>,
    /** Tunnels that contributed observations, in planning order. */
    val tunnels: List<String>,
    /** Derived tunnels whose [DerivedTunnel.derive] threw, with the reason. Their findings are left as they were. */
    val failures: Map<String, String>,
) {
    /** True when a finding appeared or cleared. Refreshing lastSeen alone does not count. */
    val findingsChanged: Boolean get() = added.isNotEmpty() || removals.isNotEmpty()
}

/**
 * Diffs each scanned tunnel against its newest stored snapshot and re-derives its findings, then runs the derived
 * tunnels over the freshest data of their sources. Pure: everything it needs from the store is passed in.
 */
object ScanPlanner {
    /**
     * @param scanned tunnels scanned now, with this scan's observations (already limited to the tunnel's own id)
     * @param derived derived tunnels to run after them
     * @param previous newest stored snapshot data per tunnel, for every scanned and derived tunnel and every source
     *   of a derived one; a tunnel that was never scanned is absent
     * @param existing every finding in the store, any tunnel, dismissed ones included
     * @param dismissed ids of the dismissed findings among [existing]
     */
    fun plan(
        scanned: List<Pair<TunnelModule, List<Observation>>>,
        derived: List<DerivedTunnel>,
        previous: Map<String, SourceData>,
        existing: List<Finding>,
        dismissed: Set<String>,
        now: Instant,
    ): ScanPlan {
        val observations = ArrayList<Observation>()
        val tunnels = ArrayList<String>()
        val upserts = ArrayList<Finding>()
        val removals = ArrayList<String>()
        val failures = LinkedHashMap<String, String>()
        var changed = false
        val existingIds = existing.mapTo(HashSet()) { it.id }
        // Findings as they stand after each step, so a derived tunnel sees what this scan found.
        val open = LinkedHashMap<String, Finding>().apply { existing.forEach { put(it.id, it) } }

        fun apply(module: TunnelModule, current: List<Observation>) {
            val prev = previous[module.id]
            val diff = DiffEngine.diff(prev?.observations.orEmpty(), current)
            // A tunnel's first stored data always counts, so its baseline exists for the next scan to diff against.
            if ((prev == null && current.isNotEmpty()) || diff.any { it.key.key !in module.volatileKeys }) changed = true
            val context = RuleContext(module.id, current, diff, isFirstScan = prev == null)
            val update = FindingsEngine.derive(context, module.rules, module::actionsFor, existing.filter { it.tunnelId == module.id }, now)
            upserts += update.upserts
            removals += update.removals
            update.removals.forEach { open.remove(it) }
            update.upserts.forEach { open[it.id] = it }
            observations += current
            tunnels += module.id
        }

        val fresh = HashMap<String, List<Observation>>()
        for ((module, current) in scanned) {
            val own = current.filter { it.tunnelId == module.id }
            apply(module, own)
            fresh[module.id] = own
        }
        for (d in derived) {
            val sources = d.sources.mapNotNull { id ->
                val data = fresh[id]?.let { SourceData(it, now) } ?: previous[id]
                data?.let { id to it }
            }.toMap()
            val findings = open.values.filter { it.tunnelId in d.sources && it.id !in dismissed }.map { it.copy(actions = emptyList()) }
            val current = try {
                d.derive(DerivedInput(sources, findings, now)).filter { it.tunnelId == d.id }
            } catch (e: Exception) {
                failures[d.id] = e.message ?: e.javaClass.simpleName
                continue
            }
            apply(d, current)
        }
        return ScanPlan(
            observations = observations,
            observationsChanged = changed,
            upserts = upserts,
            removals = removals,
            added = upserts.filter { it.id !in existingIds },
            tunnels = tunnels,
            failures = failures,
        )
    }
}
