package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.export.BundleObservation
import io.github.stronghorse44.tunnels.export.BundleSnapshot
import io.github.stronghorse44.tunnels.export.SnapshotBundle
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Moves snapshots between the encrypted store and a [SnapshotBundle]. Call on Dispatchers.IO. */
object StoreBundles {
    /** [skipped] snapshots were already in the store (same takenAt) and were left alone. */
    data class ImportResult(val snapshots: Int, val observations: Int, val skipped: Int)

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
     * Inserts each snapshot with its original takenAt, pinned so retention keeps what the user brought back, then
     * its observations under the new row id. A snapshot taken at a moment the store already holds is skipped, so
     * importing the same file twice does not double the history. Findings are re-derived by later scans, never
     * imported. If anything fails midway, the snapshots inserted so far are removed again before the error
     * propagates, so the store never keeps half an import.
     */
    suspend fun write(dao: TunnelsDao, bundle: SnapshotBundle): ImportResult {
        val clean = bundle.deduplicated()
        val byLocalId = clean.observations.groupBy { it.snapshotLocalId }
        val takenAts = dao.snapshots().mapTo(HashSet()) { it.takenAt }
        val inserted = ArrayList<Long>()
        var observations = 0
        var skipped = 0
        try {
            for (s in clean.snapshots.sortedWith(compareBy({ it.takenAt }, { it.localId }))) {
                if (!takenAts.add(s.takenAt)) {
                    skipped++
                    continue
                }
                val id = dao.insertSnapshot(SnapshotEntity(takenAt = s.takenAt, pinned = true))
                inserted += id
                val rows = byLocalId[s.localId].orEmpty().map { ObservationEntity(id, it.tunnelId, it.subject, it.key, it.value) }
                if (rows.isNotEmpty()) dao.insertObservations(rows)
                observations += rows.size
            }
        } catch (e: Exception) {
            if (inserted.isNotEmpty()) withContext(NonCancellable) { runCatching { dao.deleteSnapshots(inserted) } }
            throw e
        }
        return ImportResult(inserted.size, observations, skipped)
    }
}
