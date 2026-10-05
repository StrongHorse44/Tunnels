package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.lan.CensusMessages
import io.github.stronghorse44.tunnels.lan.CensusPins
import io.github.stronghorse44.tunnels.lan.DeviceCensus
import io.github.stronghorse44.tunnels.lan.DeviceIdentity
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.lan.LanSummary
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The census's reads and writes in the encrypted store, and nothing else: it touches only [TunnelsStore.dao] (snapshots,
 * observations, pins, events, findings), never a settings row and never a file. The logic is in `core/lan`
 * ([DeviceCensus], [CensusPins]); this is the adapter. Blocking, so call off the main thread. The reads throw when
 * the store cannot be read (the caller then reports `unavailable` and judges nothing); the actions that write catch that
 * and answer with a message, because their callers show one line.
 */
class CensusStore(context: Context) {
    private val app = context.applicationContext
    private val store: TunnelsStore get() = TunnelsStore.get(app)

    /** A stored Home network snapshot, as the census reads it. */
    private class Walked(val id: Long, val takenAt: Long, val pinned: Boolean, val observations: List<Observation>)

    /** The newest [limit] snapshots that hold Home network rows, newest first (taken_at, then id). */
    private suspend fun walk(limit: Int = MAX_WALK): List<Walked> {
        val dao = store.dao
        val out = ArrayList<Walked>()
        var before: Long? = null
        while (out.size < limit) {
            val id = dao.latestSnapshotIdFor(LanKeys.TUNNEL_ID, before) ?: break
            before = id
            val snapshot = dao.snapshot(id) ?: continue
            out += Walked(id, snapshot.takenAt, snapshot.pinned, dao.observations(id, LanKeys.TUNNEL_ID).map(ObservationEntity::toModel))
        }
        return out
    }

    private suspend fun eventsNow(): List<DeviceCensus.CensusEvent> =
        store.dao.events(DeviceCensus.STREAM, MAX_EVENTS).first().map { DeviceCensus.CensusEvent(it.at, it.kind, it.subject, it.summary) }

    /**
     * The newest snapshot of the network [tag] that carries a list, any pin state, Home-network-only or not, looking back at
     * most [MAX_WALK] snapshots. A snapshot whose census was `unavailable` carries no list and is skipped.
     */
    fun baseline(tag: String): DeviceCensus.Baseline? = runBlocking { baselineNow(tag) }

    private suspend fun baselineNow(tag: String): DeviceCensus.Baseline? =
        walk().firstNotNullOfOrNull { DeviceCensus.baselineOf(tag, it.takenAt, it.observations, it.pinned) }

    /** The acknowledgement and reset events, newest first. */
    fun events(): List<DeviceCensus.CensusEvent> = runBlocking { eventsNow() }

    /**
     * The list for a scan of the network [tag]: pins first (a failure there never touches the list, and is retried at the
     * next scan), then the baseline and the events folded by [DeviceCensus.compute]. Throws when the store cannot be read; the scan then records `census:state` as `unavailable`, never an empty list.
     */
    fun census(tag: String): DeviceCensus.Census = runBlocking {
        try {
            settleNow(tag)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Pins are bookkeeping; the baseline lookup does not need them.
        }
        listNow(tag)
    }

    /** The list as the next scan would compute it, without moving any pin. */
    private suspend fun listNow(tag: String): DeviceCensus.Census {
        val folded = DeviceCensus.fold(tag, eventsNow())
        return DeviceCensus.compute(baselineNow(tag), folded.lastReset, folded.acks, System.currentTimeMillis())
    }

    /**
     * Moves the census pin of the network [tag] to its newest Home-network-only snapshot that holds a list taken after the
     * last reset, and unpins the others of that network (see [CensusPins]). Pins the new one before unpinning the old, so
     * an interruption leaves two pins rather than none.
     */
    fun settle(tag: String) = runBlocking { settleNow(tag) }

    private suspend fun settleNow(tag: String) {
        val dao = store.dao
        val lastReset = DeviceCensus.fold(tag, eventsNow()).lastReset
        val snaps = walk().map { w ->
            val snap = CensusPins.snapOf(w.id, w.takenAt, w.pinned, homenetOnly = false, observations = w.observations)
            // Which tunnels a snapshot holds is one more query; ask it only of the snapshots the plan could touch.
            if (snap.tag == tag && snap.state != null) snap.copy(homenetOnly = dao.tunnelsIn(w.id) == listOf(LanKeys.TUNNEL_ID)) else snap
        }
        val plan = CensusPins.plan(snaps, tag, lastReset, System.currentTimeMillis())
        plan.pin?.let { dao.setPinned(it, true) }
        plan.unpin.forEach { dao.setPinned(it, false) }
    }

    /**
     * "Mine" on one device: finds the host whose tokens include the primary token in [subject] among the newest Home
     * network snapshots, records an acknowledgement with all of the host's tokens (the event carries the network's tag),
     * then dismisses the finding. The list changes at the next scan. Answers a one-line result; nothing is written when
     * the device is not in a recent scan.
     */
    fun ack(subject: String): String = runBlocking {
        guarded {
            val primary = DeviceCensus.parsePrimary(subject) ?: return@guarded CensusMessages.NOT_IN_SCAN
            for (w in walk()) {
                val tag = LanKeys.value(DeviceCensus.summaryOf(w.observations), LanKeys.SCAN_NETWORK) ?: continue
                val ids = hostsWithIds(w.observations).map { it.second }.firstOrNull { primary in it } ?: continue
                // A full list refuses the acknowledgement: nothing is written and the finding stays.
                if (!DeviceCensus.fits(listNow(tag).known, ids)) return@guarded CensusMessages.FULL
                store.recordEvent(DeviceCensus.STREAM, DeviceCensus.KIND_ACK, tag, DeviceCensus.eventSummary(ids))
                // The acknowledgement is what counts; findings that cannot be dismissed just stay until they are. Every
                // finding of this device goes, not only the one tapped: a device that announces several names, or whose
                // SSDP reply was lost once, may have been raised under another primary token.
                try {
                    val own = ids.toSet()
                    store.dao.dismissFinding(Finding.findingId(LanKeys.TUNNEL_ID, subject, LanRules.UNKNOWN_DEVICE))
                    store.dao.findingsFor(LanKeys.TUNNEL_ID)
                        .filter { it.kind == LanRules.UNKNOWN_DEVICE && DeviceCensus.parsePrimary(it.subject) in own }
                        .forEach { store.dao.dismissFinding(it.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                return@guarded CensusMessages.ADDED
            }
            CensusMessages.NOT_IN_SCAN
        }
    }

    /** "These are all mine": one acknowledgement per non-gateway host (with an identity) of the newest snapshot of the network [tag]. */
    fun setup(tag: String): String = runBlocking {
        guarded {
            val newest = walk().firstOrNull { LanKeys.value(DeviceCensus.summaryOf(it.observations), LanKeys.SCAN_NETWORK) == tag }
                ?: return@guarded CensusMessages.NO_SCAN_FOR_SETUP
            val withIds = hostsWithIds(newest.observations)
            val known = listNow(tag).known.toMutableSet()
            var added = 0
            var notFitting = 0
            for ((_, ids) in withIds) {
                if (ids.isEmpty()) continue
                // A device whose tokens would pass the cap is refused, like a single Mine.
                if (!DeviceCensus.fits(known, ids)) {
                    notFitting++
                    continue
                }
                store.recordEvent(DeviceCensus.STREAM, DeviceCensus.KIND_ACK, tag, DeviceCensus.eventSummary(ids))
                known += ids
                added++
            }
            when {
                added == 0 && notFitting > 0 -> CensusMessages.FULL
                added == 0 -> CensusMessages.NOTHING_TO_ADD
                else -> CensusMessages.setupDone(added, withIds.count { it.second.isEmpty() }, notFitting)
            }
        }
    }

    /**
     * "Start the list again" (and Forget, once the network is forgotten): records a reset, then moves the pin. The reset
     * stands even if the pin move fails. Findings already raised are not touched: sticky ones expire at 30 days or on Dismiss.
     */
    fun reset(tag: String): String = runBlocking {
        guarded {
            store.recordEvent(DeviceCensus.STREAM, DeviceCensus.KIND_RESET, tag, DeviceCensus.RESET_SUMMARY)
            try {
                settleNow(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            CensusMessages.RESET
        }
    }

    /** The non-gateway hosts of a snapshot and their tokens (empty for a host with no identity), in address order. */
    private fun hostsWithIds(observations: List<Observation>): List<Pair<String, List<String>>> {
        val homenet = observations.filter { it.tunnelId == LanKeys.TUNNEL_ID }
        val gateway = LanKeys.value(homenet.filter { it.subject == LanKeys.SUBJECT_ROUTER }, LanKeys.ROUTER_IP)
        return homenet.filter { LanKeys.isHostSubject(it.subject) && it.subject != gateway }
            .groupBy { it.subject }
            .toSortedMap(compareBy(LanSummary::ipSortKey))
            .map { (ip, rows) -> ip to LanKeys.items(LanKeys.value(rows, LanKeys.HOST_IDS)).filter(DeviceIdentity::isToken) }
    }

    /** Runs a write; a store that cannot be read or changed (even a missing native library) answers with a message and writes nothing more. */
    private suspend fun guarded(block: suspend () -> String): String =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            CensusMessages.NOT_SAVED
        }

    companion object {
        /** How many Home network snapshots the census looks back over: retention keeps twelve plus the pins. */
        const val MAX_WALK = 64
        const val MAX_EVENTS = 2000
    }
}
