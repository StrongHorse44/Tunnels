package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.ObservationKey

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
