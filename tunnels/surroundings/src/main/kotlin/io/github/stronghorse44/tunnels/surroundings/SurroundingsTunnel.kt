package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.ble.CellHeuristics
import io.github.stronghorse44.tunnels.ble.CellJudgement
import io.github.stronghorse44.tunnels.ble.CellSummary
import io.github.stronghorse44.tunnels.ble.SightingAggregator
import io.github.stronghorse44.tunnels.ble.SightingRecord
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Surroundings: nearby Bluetooth trackers, the Wi-Fi networks around the phone and the cell it is
 * on. One scan is a 15-second BLE window, the cached Wi-Fi scan results and the current cell. Each
 * tracker sighting goes to the events table (30 days) under a pseudonymous key, so a tag that keeps
 * turning up across scans, and across the background monitor's windows, can be told from a passer-by.
 */
class SurroundingsTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = SurroundingsKeys.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = listOf(
        PermissionSpec(Manifest.permission.BLUETOOTH_SCAN, "To spot trackers like AirTags and SmartTags near you"),
        PermissionSpec(Manifest.permission.NEARBY_WIFI_DEVICES, "To check the Wi-Fi networks around you for open or impostor networks"),
        PermissionSpec(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "To tell a tracker that travels with you from one that stays put, and to learn which cell towers you use at places you return to. Only keyed hashes are kept, never a position",
        ),
        PermissionSpec(Manifest.permission.ACCESS_COARSE_LOCATION, "Android requires this next to precise location; no position is stored"),
    )

    override val rules: List<FindingRule> = SurroundingsRules.all

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val now = System.currentTimeMillis()
        val session = "scan-$now"
        progress.report(0, STEPS, "reading history")
        val history = readEvents()

        progress.report(1, STEPS, "asking for a Wi-Fi scan")
        WifiProbe.requestScan(context)

        progress.report(2, STEPS, "listening for Bluetooth trackers")
        // The place fix runs during the Bluetooth window, so knowing whether the phone moved costs no extra time.
        val (window, place) = coroutineScope {
            val fix = async { placeOf(context) }
            val ble = withTimeoutOrNull(BLE_WINDOW_MS + GRACE_MS) {
                BleWindow.scan(context, session, BLE_WINDOW_MS) { s, total -> progress.report(2, STEPS, "listening for Bluetooth trackers ${s}s/${total}s") }
            } ?: BleWindowResult(SurroundingsKeys.AVAILABLE_FAILED, 0, emptyList())
            ble to fix.await()
        }
        val ble = window.copy(sightings = window.sightings.map { it.copy(place = place.place) })

        progress.report(3, STEPS, "reading Wi-Fi networks")
        val wifi = try {
            WifiProbe.read(context)
        } catch (e: Exception) {
            WifiProbeResult(SurroundingsKeys.AVAILABLE_FAILED, emptyList())
        }

        progress.report(4, STEPS, "reading the cell")
        val cell = try {
            withTimeoutOrNull(CELL_TIMEOUT_MS) { CellProbe.read(context) } ?: CellProbeResult(SurroundingsKeys.AVAILABLE_FAILED, null)
        } catch (e: Exception) {
            CellProbeResult(SurroundingsKeys.AVAILABLE_FAILED, null)
        }

        progress.report(5, STEPS, "saving summaries")
        record(ble.sightings, cell.cell)

        val cutoff = now - RETENTION_MS
        val recent = history.filter { it.at >= cutoff }
        val sightings = recent.filter { it.kind == SurroundingsKeys.EVENT_SIGHTING }.mapNotNull { SightingRecord.parse(it.subject, it.summary) } + ble.sightings
        val muted = activeMutes(context, recent, now)
        val aggregate = SightingAggregator.aggregate(sightings)
        val sessions = sightings.map { it.session }.toSet().size

        val cellHistory = recent.filter { it.kind == SurroundingsKeys.EVENT_CELL }.sortedBy { it.at }.mapNotNull { CellSummary.parse(it.summary) }
        val previousCell = cellHistory.lastOrNull()
        val changed = if (cell.cell != null && previousCell != null) previousCell.registered != cell.cell.registered else null
        val downgrades = (cellHistory + listOfNotNull(cell.cell)).zipWithNext().count { (a, b) -> CellHeuristics.isDowngrade(a.registered, b.registered) }
        val twinsRecorded = recent.count { it.kind == SurroundingsKeys.EVENT_WIFI }

        progress.report(6, STEPS, "checking the cell logbook")
        val log = judgeLogbook(place, cell, previousCell?.registered?.rank ?: 0)

        progress.report(STEPS, STEPS, "done")
        return SurroundingsKeys.bleObservations(aggregate, ble.devicesTotal, ble.available, now, muted, sessions, currentSession = session, placeAvailable = place.available) +
            SurroundingsKeys.wifiObservations(wifi.summaries, wifi.available, twinsRecorded) +
            SurroundingsKeys.cellObservations(cell.cell, cell.available, changed, downgrades) +
            SurroundingsKeys.logObservations(log.first, log.second)
    }

    /**
     * The cell logbook's step of a scan: the `log:state` and, when the scan was judged, the judgement. The order of
     * states is off (no row: nothing is read, learned or written), then no place, then no cell id. Anything that goes
     * wrong is `failed` with no verdict and the rest of the scan is unchanged. The serving cells and the place's
     * hashes exist in memory for this call only.
     */
    private suspend fun judgeLogbook(place: PlaceResult, cell: CellProbeResult, previousRank: Int): Pair<String, CellJudgement?> = try {
        val logbook = CellLogStore(context)
        val block = place.block
        when {
            !logbook.isOn() -> SurroundingsKeys.LOG_OFF to null
            block == null -> SurroundingsKeys.LOG_NO_PLACE to null
            cell.serving.none { it.cellId != null } -> SurroundingsKeys.LOG_NO_CELL_ID to null
            else -> logbook.judge(block, cell.serving, previousRank)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cell logbook failed: ${e.javaClass.simpleName}")
        SurroundingsKeys.LOG_FAILED to null
    }

    /** The tunnel's rows from the events table, newest first; empty when the store cannot be opened. */
    private suspend fun readEvents(): List<EventEntity> = try {
        val store = TunnelsStore.get(context)
        withTimeoutOrNull(STORE_TIMEOUT_MS) { store.dao.events(id, MAX_EVENTS).first() }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "events unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    private suspend fun record(sightings: List<SightingRecord>, cell: CellSummary?) {
        if (sightings.isEmpty() && cell == null) return
        try {
            val store = TunnelsStore.get(context)
            for (s in sightings) store.recordEvent(id, SurroundingsKeys.EVENT_SIGHTING, s.subject, s.encode(), Instant.ofEpochMilli(s.at))
            cell?.let { store.recordEvent(id, SurroundingsKeys.EVENT_CELL, SurroundingsKeys.CELL_SUMMARY, it.encode()) }
        } catch (e: Exception) {
            Log.w(TAG, "record failed: ${e.javaClass.simpleName}")
        }
    }

    /** Routed by the finding's kind, never its subject: a Wi-Fi SSID can be any string, including one that looks like a tracker or cell subject. */
    override fun actionsFor(draft: FindingDraft): List<FindingAction> = when (draft.kind) {
        in SurroundingsRules.trackerKinds -> trackerActions(draft.subject)
        SurroundingsRules.CELL_DOWNGRADE, SurroundingsRules.CELL_DOWNGRADED -> listOf(
            FindingAction.OpenSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Mobile network settings"),
        )
        SurroundingsRules.UNFAMILIAR_TOWER -> listOfNotNull(
            FindingAction.OpenSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Mobile network settings"),
            SurroundingsKeys.parseTowerSubject(draft.subject)?.let { id -> FindingAction.Perform(LABEL_NORMAL_HERE) { CellLogStore(context).accept(id) } },
        )
        else -> listOf(FindingAction.OpenSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings"))
    }

    /**
     * A tracker finding offers find it, Android's own alerts and the mute; the brand guides (identify,
     * disable, report) are sheets in the identity detail, too long for a finding's one-line result.
     * Following findings are on identity subjects (`tracker:<type>:<key>`): find-it locks to that key and the
     * mute covers that identity only. A family subject (the new-family notice) gets find it and the alerts but
     * no mute: a family-wide mute would also silence a planted tag of the same kind. A subject that does not
     * parse gets neither find it nor mute rather than a guess.
     */
    fun trackerActions(subject: String): List<FindingAction> {
        val parsed = SurroundingsKeys.parseTrackerSubject(subject)
        return listOfNotNull(
            parsed?.let { (type, key) -> TrackerActions.findIt(context, type, key) },
            TrackerActions.unknownTrackerAlerts(context),
            parsed?.second?.let { muteAction(subject) },
        )
    }

    /** Mutes one identity for [SurroundingsKeys.MUTE_DAYS] days. Only ever offered for identity subjects. */
    fun muteAction(subject: String): FindingAction = FindingAction.Perform(LABEL_MUTE) { mute(subject) }

    /**
     * Cancels the mute on each of [subjects] (an identity and, if the whole family was muted, the family).
     * Destructive only in the sense that it rescans, so the warning can come back at once.
     */
    fun unmuteAction(vararg subjects: String): FindingAction = FindingAction.Perform("Unmute", destructive = true) {
        try {
            val store = TunnelsStore.get(context)
            subjects.forEach { store.recordEvent(SurroundingsKeys.MUTE_LEDGER, SurroundingsKeys.EVENT_UNMUTE, it, "unmuted") }
            SurroundingsFormat.unmuteMessage(subjects.toList())
        } catch (e: Exception) {
            "Could not save the unmute: ${e.javaClass.simpleName}"
        }
    }

    /** Records a mute row for the identity subject; the next scan marks it muted and the finding clears. */
    private suspend fun mute(subject: String): String = if (SurroundingsKeys.parseTrackerSubject(subject)?.second == null) {
        "Only a single identity can be muted."
    } else try {
        TunnelsStore.get(context).recordEvent(SurroundingsKeys.MUTE_LEDGER, SurroundingsKeys.EVENT_MUTE, subject, "muted")
        "Muted ${SurroundingsFormat.trackerTitle(subject)} for ${SurroundingsKeys.MUTE_DAYS} days. It stays in the list without a warning."
    } catch (e: Exception) {
        "Could not save the mute: ${e.javaClass.simpleName}"
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        SurroundingsPanel(state, actions)
    }

    companion object {
        private const val TAG = "Surroundings"
        const val LABEL_MUTE = "Known tracker: mute 30 days"
        const val LABEL_NORMAL_HERE = "Normal here: remember this tower"

        /**
         * Subjects muted right now. Mute rows since v3 come from their own small stream
         * ([SurroundingsKeys.MUTE_LEDGER]), so thousands of sighting rows never crowd them out; rows written before
         * v3 are picked from [legacy] (the tunnel's own events, already read by the caller) when given, else from a
         * bounded read of that stream. Workaround until the store offers a kind-filtered events query.
         */
        suspend fun activeMutes(context: Context, legacy: List<EventEntity>?, now: Long): Set<String> {
            val rows = try {
                val store = TunnelsStore.get(context)
                val ledger = withTimeoutOrNull(STORE_TIMEOUT_MS) { store.dao.events(SurroundingsKeys.MUTE_LEDGER, SurroundingsKeys.MAX_MUTE_ROWS).first() }.orEmpty()
                val old = legacy ?: withTimeoutOrNull(STORE_TIMEOUT_MS) { store.dao.events(SurroundingsKeys.TUNNEL_ID, MAX_EVENTS).first() }.orEmpty()
                ledger + old
            } catch (e: Exception) {
                Log.w(TAG, "mutes unavailable: ${e.javaClass.simpleName}")
                legacy.orEmpty()
            }
            return SurroundingsKeys.activeMutes(
                rows.filter { it.kind == SurroundingsKeys.EVENT_MUTE || it.kind == SurroundingsKeys.EVENT_UNMUTE }
                    .map { SurroundingsKeys.MuteRow(it.kind, it.subject, it.at) },
                now,
            )
        }
        /** The phone's place for one scan; a failure only means the scan does not know it. */
        suspend fun placeOf(context: Context, timeoutMs: Long = BLE_WINDOW_MS): PlaceResult = try {
            PlaceProbe.locate(context, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "place failed: ${e.javaClass.simpleName}")
            PlaceResult(SurroundingsKeys.AVAILABLE_FAILED, null)
        }

        private const val STEPS = 7
        const val BLE_WINDOW_MS = 15_000L
        private const val GRACE_MS = 5_000L
        private const val CELL_TIMEOUT_MS = 10_000L
        private const val STORE_TIMEOUT_MS = 20_000L
        private const val RETENTION_MS = 30L * SurroundingsKeys.DAY_MS
        /** One row per tracker per scan window; a day of background monitoring near a few tags is a few thousand. */
        private const val MAX_EVENTS = 20_000
    }
}
