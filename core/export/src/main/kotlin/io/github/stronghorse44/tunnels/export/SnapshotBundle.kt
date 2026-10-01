package io.github.stronghorse44.tunnels.export

/** One snapshot inside a bundle. [localId] is only meaningful within the bundle: it links observations to their snapshot. */
data class BundleSnapshot(
    val localId: Long,
    /** Epoch milliseconds. */
    val takenAt: Long,
    val pinned: Boolean,
    val tunnelIds: List<String>,
)

/** One observation inside a bundle: a summary value, never raw data. */
data class BundleObservation(
    val snapshotLocalId: Long,
    val tunnelId: String,
    val subject: String,
    val key: String,
    val value: String,
)

/** Everything an export carries: snapshots and their observations. Findings are derived on device, never carried. */
data class SnapshotBundle(
    val snapshots: List<BundleSnapshot>,
    val observations: List<BundleObservation>,
) {
    fun observationsOf(localId: Long): List<BundleObservation> = observations.filter { it.snapshotLocalId == localId }

    /**
     * A copy safe to insert into a store whose observation key is (snapshot, tunnel, subject, key): duplicate
     * observations keep their last value, observations of unknown snapshots are dropped, and snapshot ids are unique.
     */
    fun deduplicated(): SnapshotBundle {
        val snapshots = snapshots.distinctBy { it.localId }
        val known = snapshots.map { it.localId }.toSet()
        val byKey = LinkedHashMap<List<Any>, BundleObservation>()
        for (o in observations) {
            if (o.snapshotLocalId !in known) continue
            byKey[listOf(o.snapshotLocalId, o.tunnelId, o.subject, o.key)] = o
        }
        return SnapshotBundle(snapshots, byKey.values.toList())
    }

    companion object {
        val EMPTY = SnapshotBundle(emptyList(), emptyList())
    }
}
