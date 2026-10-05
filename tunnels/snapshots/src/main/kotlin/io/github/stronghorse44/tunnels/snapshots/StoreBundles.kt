package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.export.BundleObservation
import io.github.stronghorse44.tunnels.export.BundleSettings
import io.github.stronghorse44.tunnels.export.BundleSnapshot
import io.github.stronghorse44.tunnels.export.ImportCommitFailed
import io.github.stronghorse44.tunnels.export.ImportSummary
import io.github.stronghorse44.tunnels.export.SnapshotBundle
import io.github.stronghorse44.tunnels.export.TunnelsData
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.store.StagedObservation
import io.github.stronghorse44.tunnels.store.StagedSnapshot
import io.github.stronghorse44.tunnels.store.TunnelsDao
import kotlin.coroutines.cancellation.CancellationException

/** Moves a Tunnels bundle between the encrypted store and a [TunnelsData]. Call on Dispatchers.IO. */
object StoreBundles {
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
     * What an export carries: the snapshots, the carried settings and paired phones from the store, and the confirmed
     * networks ([networks], the hashes in the settings table's `homenet.confirmed_networks` row). Values are in their
     * owners' canonical form.
     */
    suspend fun gather(dao: TunnelsDao, networks: Set<String>): TunnelsData {
        val snapshots = read(dao)
        val settings = BundleSettings.canonicalSettings(BundleSettings.KEYS.associateWith { dao.setting(it) })
        return TunnelsData(snapshots, settings, BundleSettings.canonicalPins(dao.setting(BundleSettings.PINS_KEY)), BundleSettings.validNetworks(networks))
    }

    /**
     * The one swap of an import (container spec, section 5.2), after the file has been verified and staged in memory:
     * in a single transaction every snapshot at a moment the store does not hold yet is added with the pin it had (an
     * unpinned one is subject to retention like any other), with its observations; each carried setting replaces the phone's; paired phones are
     * merged with the phone's own (the more recently audited record wins); and the confirmed networks are added to
     * those the phone has (all in the same transaction: they are a row of the settings table). Snapshots already in the
     * store (same moment) are left alone, so importing a file twice does not double the history. Findings are re-derived by later scans, never imported.
     *
     * Before the transaction, [networks] moves an older build's plaintext list into the table if it is still there, so
     * the merge sees the phone's own networks. If that or the transaction throws, nothing is kept: throws
     * [ImportCommitFailed].
     */
    suspend fun commit(dao: TunnelsDao, networks: ConfirmedNetworks, data: TunnelsData): ImportSummary {
        val clean = data.snapshots.deduplicated()
        val byLocalId = clean.observations.groupBy { it.snapshotLocalId }
        val staged = clean.snapshots.sortedWith(compareBy({ it.takenAt }, { it.localId })).map { s ->
            StagedSnapshot(s.takenAt, s.pinned, byLocalId[s.localId].orEmpty().map { StagedObservation(it.tunnelId, it.subject, it.key, it.value) })
        }
        var newPhones = 0
        var newNetworks = 0
        try {
            networks.ready()
            val outcome = dao.importAll(staged, BundleSettings.KEYS + BundleSettings.PINS_KEY + ConfirmedNetworkCodec.KEY) { key, stored ->
                when (key) {
                    BundleSettings.PINS_KEY ->
                        if (data.pairingPins.isBlank()) {
                            null
                        } else {
                            val (merged, added) = BundleSettings.mergePins(stored, data.pairingPins)
                            newPhones = added
                            merged
                        }
                    ConfirmedNetworkCodec.KEY ->
                        if (data.networks.isEmpty()) {
                            null
                        } else {
                            val (merged, added) = ConfirmedNetworkCodec.merge(stored, data.networks)
                            newNetworks = added
                            merged
                        }
                    else -> data.settings[key]
                }
            }
            return ImportSummary(
                outcome.snapshots, outcome.observations, outcome.skipped, data.settings.size, newPhones, newNetworks,
                BundleSettings.resolverLabel(data.settings),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ImportCommitFailed(e)
        }
    }
}
