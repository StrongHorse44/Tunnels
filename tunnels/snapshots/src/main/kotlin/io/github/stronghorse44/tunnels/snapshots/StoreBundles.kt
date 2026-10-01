package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.export.BundleObservation
import io.github.stronghorse44.tunnels.export.BundleSnapshot
import io.github.stronghorse44.tunnels.export.SnapshotBundle
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsDao

/** Moves snapshots between the encrypted store and a [SnapshotBundle]. Call on Dispatchers.IO. */
object StoreBundles {
    data class ImportResult(val snapshots: Int, val observations: Int)

    /** Every snapshot in the store, oldest first, with all its observations. Findings are not part of a bundle. */
    suspend fun read(dao: TunnelsDao): SnapshotBundle {
        val snapshots = ArrayList<BundleSnapshot>()
        val observations = ArrayList<BundleObservation>()
        for (s in dao.snapshots().sortedWith(compareBy({ it.takenAt }, { it.id }))) {
            snapshots += BundleSnapshot(s.id, s.takenAt, s.pinned, dao.tunnelsIn(s.id).sorted())
            dao.observations(s.id).forEach { o -> observations += BundleObservation(s.id, o.tunnelId, o.subject, o.key, o.value) }
        }
        return SnapshotBundle(snapshots, observations)
    }

    /**
     * Inserts each snapshot with its original takenAt and pinned, so retention keeps what the user brought back,
     * then its observations under the new row id. Findings are re-derived by later scans, never imported.
     */
    suspend fun write(dao: TunnelsDao, bundle: SnapshotBundle): ImportResult {
        val clean = bundle.deduplicated()
        val byLocalId = clean.observations.groupBy { it.snapshotLocalId }
        var inserted = 0
        for (s in clean.snapshots.sortedWith(compareBy({ it.takenAt }, { it.localId }))) {
            val id = dao.insertSnapshot(SnapshotEntity(takenAt = s.takenAt, pinned = true))
            val rows = byLocalId[s.localId].orEmpty().map { ObservationEntity(id, it.tunnelId, it.subject, it.key, it.value) }
            if (rows.isNotEmpty()) dao.insertObservations(rows)
            inserted += rows.size
        }
        return ImportResult(clean.snapshots.size, inserted)
    }
}
