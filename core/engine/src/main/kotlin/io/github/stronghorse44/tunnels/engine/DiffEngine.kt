package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.Observation

object DiffEngine {
    /** Compares two snapshots' observations, keyed by (tunnel, subject, key). Output is sorted for stable display. */
    fun diff(before: Collection<Observation>, after: Collection<Observation>): List<DiffEntry> {
        val old = before.associateBy { it.identity }
        val new = after.associateBy { it.identity }
        val entries = mutableListOf<DiffEntry>()
        for ((key, obs) in new) {
            val prev = old[key]
            when {
                prev == null -> entries += DiffEntry.Added(obs)
                prev.value != obs.value -> entries += DiffEntry.Changed(prev, obs)
            }
        }
        for ((key, obs) in old) {
            if (key !in new) entries += DiffEntry.Removed(obs)
        }
        return entries.sortedWith(compareBy({ it.key.tunnelId }, { it.key.subject }, { it.key.key }))
    }
}
