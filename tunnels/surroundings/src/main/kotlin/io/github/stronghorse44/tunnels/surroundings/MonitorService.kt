package io.github.stronghorse44.tunnels.surroundings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.stronghorse44.tunnels.ble.CellSummary
import io.github.stronghorse44.tunnels.ble.FollowingHeuristic
import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.SightingAggregator
import io.github.stronghorse44.tunnels.ble.SightingRecord
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.WifiSummary
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** What the panel and the notification show about the background monitor. */
data class MonitorState(
    val running: Boolean = false,
    val startedAt: Long = 0L,
    /** BLE windows completed so far. */
    val windows: Int = 0,
    /** Distinct pseudonymous tracker keys seen since start. */
    val trackerKeys: Int = 0,
    val trackerTypes: Set<String> = emptySet(),
    /** The same keys by their latest state, so the notification can use the tracker section's words. */
    val keysByState: Map<TrackerState, Int> = emptyMap(),
    /** Unmuted identities close to following ([FollowingHeuristic.isClose]) but not over the threshold; judged per identity. */
    val keysClose: Int = 0,
    /** Unmuted identities over the following threshold since start (mutes as they stood when the monitor started). */
    val keysFollowing: Int = 0,
    val bleAvailable: String? = null,
    val lastCell: String? = null,
    /** Why the monitor stopped or could not start. */
    val message: String? = null,
    /** When the last run ended (0 while never run): the panel compares it with the last scan. */
    val stoppedAt: Long = 0L,
)

/**
 * The background monitor: a foreground service (connectedDevice|location) the user switches on inside
 * the tunnel. While on, it runs a 10-second BLE window every 2 minutes and a Wi-Fi/cell check every
 * 10 minutes, writing only summaries to the events table. It stops from the switch, the notification,
 * or by itself after 24 hours, and the system never restarts it.
 *
 * Its BLE windows carry the tracker catalog's hardware filters: an unfiltered scan is suspended by the
 * Bluetooth stack while the screen is off, which is exactly when a monitor in a pocket has to listen.
 * The monitor only needs trackers, so the filters cost nothing it records.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    /** Key → that identity's sightings since start, folded (one record per window) by the same aggregator the scan uses. */
    private val keyRecords = HashMap<String, MutableList<SightingRecord>>()
    /** Key → latest state; separated sticks once seen. */
    private val keys = HashMap<String, TrackerState>()
    private val types = HashSet<String>()
    private var lastWifiCellAt = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop("Stopped.")
            else -> if (!isRunning) stopSelf() // a system restart or a stray start: the monitor is user-started only
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        if (isRunning) _state.update { it.copy(running = false, message = it.message ?: "Stopped.", stoppedAt = System.currentTimeMillis()) }
        scope.cancel()
        super.onDestroy()
    }

    private fun start() {
        if (isRunning) return
        stopping = false
        val initial = MonitorState(running = true, startedAt = System.currentTimeMillis())
        if (!foreground(initial)) return
        keys.clear()
        keyRecords.clear()
        types.clear()
        lastWifiCellAt = 0L
        _state.value = initial
        loop = scope.launch {
            try {
                // Mutes are read once at start: identities the user muted (or whose family carries a legacy mute) never count.
                val muted = withContext(Dispatchers.IO) { SurroundingsTunnel.activeMutes(this@MonitorService, null, initial.startedAt) }
                while (isActive && !stopping) {
                    val now = System.currentTimeMillis()
                    if (now - initial.startedAt >= MAX_DURATION_MS) {
                        stop("Stopped automatically after ${MAX_DURATION_MS / 3_600_000} hours.")
                        return@launch
                    }
                    val session = "monitor-$now"
                    // Probes are binder calls and waits; keep them off the main thread the service lives on.
                    val ble = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(BLE_WINDOW_MS + GRACE_MS) {
                            BleWindow.scan(this@MonitorService, session, BLE_WINDOW_MS, filters = TrackerSignatures.scanFilters)
                        }
                    }
                    var cell: CellSummary? = null
                    var twins: List<WifiSummary> = emptyList()
                    if (now - lastWifiCellAt >= WIFI_CELL_INTERVAL_MS) {
                        lastWifiCellAt = now
                        withContext(Dispatchers.IO) {
                            WifiProbe.requestScan(this@MonitorService)
                            twins = runCatching { WifiProbe.read(this@MonitorService).summaries.filter { it.twinSuspect != null } }.getOrDefault(emptyList())
                            cell = runCatching { withTimeoutOrNull(CELL_TIMEOUT_MS) { CellProbe.read(this@MonitorService) }?.cell }.getOrNull()
                        }
                    }
                    if (ble != null) {
                        ble.sightings.forEach { s ->
                            val previous = keys[s.key]
                            keys[s.key] = if (previous == TrackerState.SEPARATED) previous else s.state
                            types += s.type.slug
                            // Only what the aggregate needs (session, time, state); the full row went to the store.
                            keyRecords.getOrPut(s.key) { ArrayList() } += s.copy(battery = null, kind = null)
                        }
                    }
                    record(ble, cell, twins)
                    val judged = SightingAggregator.aggregate(keyRecords.values.flatten()).devices.values
                        .filterNot { d -> SurroundingsKeys.isMuted(d.type, d.key, muted) }
                    _state.update {
                        it.copy(
                            windows = it.windows + 1,
                            trackerKeys = keys.size,
                            trackerTypes = types.toSet(),
                            keysByState = keys.values.groupingBy { s -> s }.eachCount(),
                            keysClose = judged.count { d -> d.level == FollowingLevel.NONE && FollowingHeuristic.isClose(d.sessions, d.spanMinutes) },
                            keysFollowing = judged.count { d -> d.level != FollowingLevel.NONE },
                            bleAvailable = ble?.available ?: SurroundingsKeys.AVAILABLE_FAILED,
                            lastCell = cell?.registered?.slug ?: it.lastCell,
                        )
                    }
                    notify(_state.value)
                    delay((BLE_INTERVAL_MS - BLE_WINDOW_MS).coerceAtLeast(10_000L))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "monitor loop failed: ${e.javaClass.simpleName}")
                stop("Stopped after an error: ${e.javaClass.simpleName}.")
            }
        }
    }

    /** Promotes the service; the location type needs its permissions, so it falls back to connected-device only. */
    private fun foreground(state: MonitorState): Boolean {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        for (type in listOf(both, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)) {
            try {
                startForeground(NOTIFICATION_ID, notification(state), type)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground($type) failed: ${e.javaClass.simpleName}")
            }
        }
        _state.update { it.copy(running = false, message = "Could not start the monitor: Android refused the foreground service. Check the location and notification permissions.") }
        stopSelf()
        return false
    }

    /** Writes the window's summaries to the events table (30-day retention). */
    private suspend fun record(ble: BleWindowResult?, cell: CellSummary?, twins: List<WifiSummary>) {
        withContext(Dispatchers.IO) {
            try {
                val store = TunnelsStore.get(this@MonitorService)
                ble?.sightings?.forEach { s ->
                    store.recordEvent(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.EVENT_SIGHTING, s.subject, s.encode(), Instant.ofEpochMilli(s.at))
                }
                cell?.let { store.recordEvent(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.EVENT_CELL, SurroundingsKeys.CELL_SUMMARY, it.encode()) }
                for (t in twins) {
                    val reason = t.twinSuspect ?: continue
                    store.recordEvent(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.EVENT_WIFI, t.subject, reason)
                }
            } catch (e: Exception) {
                Log.w(TAG, "record failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun stop(reason: String) {
        if (stopping) return
        stopping = true
        loop?.cancel()
        loop = null
        _state.update { it.copy(running = false, message = reason, stoppedAt = System.currentTimeMillis()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(state: MonitorState) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state)) }
    }

    private fun notification(state: MonitorState): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Surroundings monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the background monitor is scanning for trackers."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, TunnelActivity.intent(this, SurroundingsKeys.TUNNEL_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Surroundings monitor on")
            .setContentText(SurroundingsFormat.monitorLine(state.windows, state.keysByState, state.lastCell, state.keysClose, state.keysFollowing))
            .setSubText("BLE every ${BLE_INTERVAL_MS / 60_000} min · stops after ${MAX_DURATION_MS / 3_600_000} h")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "SurroundingsMonitor"
        const val ACTION_START = "io.github.stronghorse44.tunnels.surroundings.action.START_MONITOR"
        const val ACTION_STOP = "io.github.stronghorse44.tunnels.surroundings.action.STOP_MONITOR"
        const val CHANNEL_ID = "surroundings_monitor"
        private const val NOTIFICATION_ID = 4101

        const val BLE_INTERVAL_MS = 2 * 60_000L
        const val BLE_WINDOW_MS = 10_000L
        const val WIFI_CELL_INTERVAL_MS = 10 * 60_000L
        /** The monitor ends on its own after this long; the user switches it on again if needed. */
        const val MAX_DURATION_MS = 24 * 3_600_000L
        private const val GRACE_MS = 5_000L
        private const val CELL_TIMEOUT_MS = 10_000L

        private val _state = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = _state.asStateFlow()
        val isRunning: Boolean get() = _state.value.running

        fun start(context: Context) {
            _state.update { it.copy(message = null) }
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                _state.update { it.copy(message = "Could not start the monitor: ${e.javaClass.simpleName}.") }
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
