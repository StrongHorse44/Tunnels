package io.github.stronghorse44.tunnels.traffic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.stronghorse44.tunnels.dns.SessionCounter
import io.github.stronghorse44.tunnels.dns.SessionTotals
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.store.TunnelsStore
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** What the panel and the notification show about the session. */
data class SessionState(
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val totals: SessionTotals = SessionTotals(),
    /** Resolver the queries are forwarded to, for display. */
    val resolver: String? = null,
    /** Why the last session ended or could not start. */
    val message: String? = null,
)

/**
 * A DNS-only VPN session. The tunnel gets one address and a route for a single fake resolver address,
 * so only DNS queries enter it and every other packet flows as usual. Each query is counted against
 * the owning app and forwarded to the real resolver. Foreground service (specialUse, subtype
 * dns-logging), started and stopped by the user, auto-stops after [MAX_DURATION_MS]. Never restarted
 * by the system: an unexpected start without [ACTION_START] just stops.
 */
class DnsVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tun: ParcelFileDescriptor? = null
    private var forwarder: DnsForwarder? = null
    private var counter: SessionCounter? = null
    private var timers: Job? = null
    private var stopping = false
    private var startedAt = 0L
    private val subjects = ConcurrentHashMap<Int, String>()
    private var lastShown: SessionTotals? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession("Stopped.")
            else -> if (!isRunning) stopSelf() // system restart or stray start: sessions are user-started only
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopSession("The VPN permission was revoked: another VPN took over or consent was withdrawn.")
    }

    override fun onDestroy() {
        if (isRunning) tearDown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession() {
        if (isRunning) return
        // The foreground notification must be up quickly after startForegroundService().
        startForeground(NOTIFICATION_ID, notification(SessionTotals()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val failure = openTunnel()
        if (failure != null) {
            _state.update { it.copy(running = false, message = failure) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startedAt = System.currentTimeMillis()
        _state.value = SessionState(running = true, startedAt = startedAt, resolver = state.value.resolver, message = null)
        timers = scope.launch {
            launch {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    flush(force = false)
                }
            }
            launch {
                while (isActive) {
                    delay(REFRESH_MS)
                    publishTotals()
                }
            }
            launch {
                delay(MAX_DURATION_MS)
                stopSession("Stopped automatically after ${MAX_DURATION_MS / 60_000} minutes.")
            }
        }
    }

    /** Null on success, otherwise a plain-language reason the session could not start. */
    private fun openTunnel(): String? {
        if (prepare(this) != null) return "Android has not given Tunnels VPN consent yet. Open the tunnel and allow it."
        if (VpnStatus.anyVpnActive(this)) return VpnStatus.OTHER_VPN_MESSAGE
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "No connectivity service."
        val underlying = VpnStatus.underlyingNetwork(cm)
        val resolvers = VpnStatus.resolversOf(cm, underlying)

        val builder = Builder()
            .setSession("Tunnels DNS log")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS_V4, 32)
            .addDnsServer(DNS_V4)
            .addRoute(DNS_V4, 32)
            .setBlocking(true)
            .setMetered(false)
        try {
            builder.addAddress(TUN_ADDRESS_V6, 128).addDnsServer(DNS_V6).addRoute(DNS_V6, 128)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IPv6 not added: ${e.message}")
        }
        try {
            // Our own sockets must bypass the tunnel (the forwarder also protect()s them).
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            return "Could not open the tunnel: ${e.javaClass.simpleName}."
        } ?: return "Could not open the tunnel: another VPN is set as always-on, or consent was withdrawn. Check VPN settings."

        val sessionCounter = SessionCounter(SessionCounter.newToken())
        val fwd = DnsForwarder(
            tun = pfd,
            counter = sessionCounter,
            resolvers = resolvers,
            prepareSocket = { socket ->
                protect(socket)
                runCatching { underlying?.bindSocket(socket) }
            },
            ownerUid = { protocol, src, srcPort, dst, dstPort -> ownerUid(cm, protocol, src, srcPort, dst, dstPort) },
            subjectOf = ::subjectOf,
        )
        try {
            fwd.start()
        } catch (e: Exception) {
            runCatching { pfd.close() }
            return "Could not start the forwarder: ${e.javaClass.simpleName}."
        }
        tun = pfd
        forwarder = fwd
        counter = sessionCounter
        subjects.clear()
        _state.update { it.copy(resolver = resolvers.first().hostAddress) }
        return null
    }

    private fun ownerUid(cm: ConnectivityManager, protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int): Int =
        try {
            cm.getConnectionOwnerUid(protocol, InetSocketAddress(src, srcPort), InetSocketAddress(dst, dstPort))
        } catch (_: Exception) {
            -1 // only the active VPN app may ask; anything else means unknown
        }

    /** Package name for [uid], `uid:<n>` for a uid without one, `unknown` for -1. Cached per session. */
    private fun subjectOf(uid: Int): String {
        if (uid < 0) return TrafficKeys.UNKNOWN_SUBJECT
        return subjects.getOrPut(uid) {
            runCatching { packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull() }.getOrNull() ?: "${TrafficKeys.UID_PREFIX}$uid"
        }
    }

    private fun publishTotals() {
        val totals = counter?.totals ?: return
        if (totals == lastShown) return
        lastShown = totals
        _state.update { it.copy(totals = totals) }
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(totals)) }
    }

    /** Writes the interval's rows to the events table (summaries only, 30-day retention). */
    private suspend fun flush(force: Boolean) {
        val c = counter ?: return
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000).toInt()
        val rows = c.flush(minutes, force)
        if (rows.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val store = TunnelsStore.get(this@DnsVpnService)
                for ((subject, summary) in rows) store.recordEvent(TrafficKeys.TUNNEL_ID, TrafficKeys.EVENT_KIND, subject, summary)
            } catch (e: Exception) {
                Log.w(TAG, "flush failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        if (!isRunning) {
            _state.update { it.copy(message = reason) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        timers?.cancel()
        tearDown()
        val totals = counter?.totals ?: SessionTotals()
        _state.update { it.copy(running = false, totals = totals, message = reason) }
        scope.launch {
            flush(force = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun tearDown() {
        runCatching { forwarder?.stop() }
        runCatching { tun?.close() }
        forwarder = null
        tun = null
    }

    private fun notification(totals: SessionTotals): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Traffic sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a DNS logging session is running."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, TunnelActivity.intent(this, TrafficKeys.TUNNEL_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Traffic session running")
            .setContentText(SessionFormat.counters(totals))
            .setSubText("DNS only · stops after ${MAX_DURATION_MS / 60_000} min")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "TunnelsDns"
        const val ACTION_START = "io.github.stronghorse44.tunnels.traffic.action.START_SESSION"
        const val ACTION_STOP = "io.github.stronghorse44.tunnels.traffic.action.STOP_SESSION"
        const val CHANNEL_ID = "traffic_session"
        private const val NOTIFICATION_ID = 3101

        /** Sessions end on their own after this long. */
        const val MAX_DURATION_MS = 60 * 60_000L
        /** Summaries are written to the events table this often while running, and at the end. */
        const val FLUSH_INTERVAL_MS = 5 * 60_000L
        private const val REFRESH_MS = 2_000L
        private const val MTU = 1500

        const val TUN_ADDRESS_V4 = "10.111.0.2"
        const val DNS_V4 = "10.111.0.1"
        const val TUN_ADDRESS_V6 = "fd00:7a7a::2"
        const val DNS_V6 = "fd00:7a7a::1"

        private val _state = MutableStateFlow(SessionState())
        val state: StateFlow<SessionState> = _state.asStateFlow()
        val isRunning: Boolean get() = _state.value.running

        /** Starts a session. The caller has already handled consent and the other-VPN check for its UI. */
        fun start(context: Context) {
            _state.update { it.copy(message = null) }
            context.startForegroundService(Intent(context, DnsVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
