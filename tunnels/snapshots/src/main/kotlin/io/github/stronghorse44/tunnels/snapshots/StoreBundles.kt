package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.export.BundleObservation
import io.github.stronghorse44.tunnels.export.BundleSettings
import io.github.stronghorse44.tunnels.export.BundleSnapshot
import io.github.stronghorse44.tunnels.export.ImportCommitFailed
import io.github.stronghorse44.tunnels.export.ImportSummary
import io.github.stronghorse44.tunnels.export.SnapshotBundle
import io.github.stronghorse44.tunnels.export.TransferMessages
import io.github.stronghorse44.tunnels.export.TunnelsData
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
     * What an export carries: the snapshots, the carried settings and paired phones from the store. Values are in their
     * owners' canonical form. **No confirmed networks** (audit L8, L8-Q4): Home network's confirmations are keyed with
     * this phone's Keystore key, so they could never match on another phone or after a reinstall. The bundle format is
     * unchanged: `networks.txt` is empty and the manifest says `"networks":0`.
     */
    suspend fun gather(dao: TunnelsDao): TunnelsData {
        val snapshots = read(dao)
        val settings = BundleSettings.canonicalSettings(BundleSettings.KEYS.associateWith { dao.setting(it) })
        return TunnelsData(snapshots, settings, BundleSettings.canonicalPins(dao.setting(BundleSettings.PINS_KEY)), networks = emptySet())
    }

    /**
     * How many confirmed-network lines an older bundle carried. They are parsed as before but **not merged** into either
     * of Home network's rows (L8-Q4): the import screen says so instead.
     */
    fun networksLeftOut(data: TunnelsData): Int = data.networks.size

    /**
     * The one swap of an import (container spec, section 5.2), after the file has been verified and staged in memory:
     * in a single transaction every snapshot at a moment the store does not hold yet is added with the pin it had (an
     * unpinned one is subject to retention like any other), with its observations; each carried setting replaces the
     * phone's; and paired phones are merged with the phone's own (the more recently audited record wins). Snapshots
     * already in the store (same moment) are left alone, so importing a file twice does not double the history. Findings
     * are re-derived by later scans, never imported. Confirmed networks in an older bundle are not merged
     * ([networksLeftOut]); `newNetworks` is always 0. If the transaction throws, nothing is kept: throws
     * [ImportCommitFailed].
     */
    suspend fun commit(dao: TunnelsDao, data: TunnelsData): ImportSummary {
        val clean = data.snapshots.deduplicated()
        val byLocalId = clean.observations.groupBy { it.snapshotLocalId }
        val staged = clean.snapshots.sortedWith(compareBy({ it.takenAt }, { it.localId })).map { s ->
            StagedSnapshot(s.takenAt, s.pinned, byLocalId[s.localId].orEmpty().map { StagedObservation(it.tunnelId, it.subject, it.key, it.value) })
        }
        var newPhones = 0
        try {
            val outcome = dao.importAll(staged, BundleSettings.KEYS + BundleSettings.PINS_KEY) { key, stored ->
                when (key) {
                    BundleSettings.PINS_KEY ->
                        if (data.pairingPins.isBlank()) {
                            null
                        } else {
                            val (merged, added) = BundleSettings.mergePins(stored, data.pairingPins)
                            newPhones = added
                            merged
                        }
                    else -> data.settings[key]
                }
            }
            return ImportSummary(
                outcome.snapshots, outcome.observations, outcome.skipped, data.settings.size, newPhones, 0,
                BundleSettings.resolverLabel(data.settings),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ImportCommitFailed(e)
        }
    }
}

/** An import's outcome: the summary, and how many confirmed networks the file carried that were left out (L8-Q4). */
data class ImportReport(val summary: ImportSummary, val networksLeftOut: Int) {
    /** The import screen's message. */
    val message: String
        get() = TransferMessages.imported(summary) + if (networksLeftOut > 0) " $NETWORKS_NOT_CARRIED" else ""

    companion object {
        const val NETWORKS_NOT_CARRIED = "Confirmed networks aren't carried between phones any more: confirm your Wi-Fi in Home network on this phone."
    }
}
