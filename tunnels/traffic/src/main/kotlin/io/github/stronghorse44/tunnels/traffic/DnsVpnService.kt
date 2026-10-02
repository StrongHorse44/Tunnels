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
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.stronghorse44.tunnels.dns.BlockPolicy
import io.github.stronghorse44.tunnels.dns.Blocklists
import io.github.stronghorse44.tunnels.dns.SessionCounter
import io.github.stronghorse44.tunnels.dns.SessionTotals
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.dns.Upstream
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
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/** What the panel and the notification show about the session. */
data class SessionState(
    val running: Boolean = false,
    /** True from the moment STOP is taken until the final rows are in the store; [running] stays true meanwhile. */
    val ending: Boolean = false,
    val startedAt: Long = 0L,
    val totals: SessionTotals = SessionTotals(),
    /** Resolver the queries are forwarded to, for display; null while no network is available. */
    val resolver: String? = null,
    /** Why the last session ended or could not start. */
    val message: String? = null,
)

/**
 * A DNS-only VPN session. The tunnel gets one address and a route for a single fake resolver address,
 * so only DNS queries enter it and every other packet flows as usual. Each query is counted against
 * the owning app and forwarded to the real resolver, unless [policy] blocks it: then it is answered
 * "no such domain" from here and nothing leaves the phone. Foreground service (specialUse, subtype
 * dns-logging), started and stopped by the user, auto-stops after [MAX_DURATION_MS]. Never restarted
 * by the system: an unexpected start without [ACTION_START] just stops.
 *
 * While the tunnel is up the phone's DNS depends on this service, so it ends the session (handing DNS
 * back to the system) as soon as the forwarder reports trouble, and follows the default network so a
 * Wi-Fi to cellular hand-over mid-session keeps lookups working.
 */
class DnsVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var forwarder: DnsForwarder? = null
    private var counter: SessionCounter? = null
    private var timers: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** The service's own truth: tunnel established and loops running. [state] lags it at session end. */
    private var active = false
    private var starting = false
    private var stopping = false
    private var startedAt = 0L
    /** Counts sessions, so a stop's deferred clean-up never touches a session started after it. */
    private var serial = 0
    private val subjects = ConcurrentHashMap<Int, String>()
    private var lastShown: SessionTotals? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession("Stopped.")
            else -> if (!active && !starting) stopSelf() // system restart or stray start: sessions are user-started only
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopSession("The VPN permission was revoked: another VPN took over or consent was withdrawn.")
    }

    override fun onDestroy() {
        if (tun != null) tearDown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession() {
        if (active || starting) return
        starting = true
        stopping = false
        serial++
        // The foreground notification must be up quickly after startForegroundService().
        startForeground(NOTIFICATION_ID, notification(SessionTotals()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            // The user's blocking choices, read before the tunnel opens so the first lookup already follows them.
            withContext(Dispatchers.IO) { runCatching { TunnelsStore.get(this@DnsVpnService).setting(BlockPolicy.KEY) }.getOrNull() }
                ?.let { _policy.value = BlockPolicy.decode(it) }
            withContext(Dispatchers.IO) { runCatching { TunnelsStore.get(this@DnsVpnService).setting(Upstream.KEY) }.getOrNull() }
                ?.let { _upstream.value = Upstream.decode(it) }
            // Bundled lists are read before the first lookup, not on the forwarder thread.
            if (_policy.value.needsLists) withContext(Dispatchers.IO) { Blocklists.ALL.forEach { BundledLists.get(this@DnsVpnService, it.id) } }
            // Sockets and binder calls stay off the main thread.
            val failure = withContext(Dispatchers.IO) { openTunnel() }
            starting = false
            if (failure != null) {
                _state.update { it.copy(running = false, ending = false, message = failure) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            if (stopping) {
                // STOP arrived while the tunnel was opening: stopSession left the clean-up to us.
                tearDown()
                _state.update { it.copy(running = false, ending = false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            active = true
            startedAt = System.currentTimeMillis()
            _state.value = SessionState(running = true, startedAt = startedAt, resolver = state.value.resolver, message = null)
            getSystemService(ConnectivityManager::class.java)?.let(::followDefaultNetwork)
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
                        // Belt and braces beside the forwarder's own failure callback.
                        val fwd = forwarder
                        if (fwd != null && !fwd.alive) stopSession(FORWARDER_FAILED_MESSAGE)
                    }
                }
                launch {
                    delay(MAX_DURATION_MS)
                    stopSession("Stopped automatically after ${MAX_DURATION_MS / 60_000} minutes.")
                }
            }
        }
    }

    /** Null on success, otherwise a plain-language reason the session could not start. */
    private fun openTunnel(): String? {
        // Order matters: prepare() while another VPN is connected would disconnect that VPN (see
        // VpnStatus), so the other-VPN refusal comes first and prepare() runs only on a VPN-free phone.
        if (VpnStatus.anyVpnActive(this)) return VpnStatus.otherVpnMessage(this)
        if (prepare(this) != null) return "Android has not given Tunnels VPN consent yet. Open the tunnel and allow it."
        // Checked before establish(): a tunnel with no forwarder behind it would swallow every lookup.
        if (!VpnStatus.networkAllowed(this)) return VpnStatus.NETWORK_OFF_MESSAGE
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "No connectivity service."
        val resolvers = VpnStatus.resolversOf(cm, VpnStatus.underlyingNetwork(cm))

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
        val fwd = try {
            DnsForwarder(
                tun = pfd,
                counter = sessionCounter,
                resolvers = resolvers,
                // protect() alone: a socket bound to the start-time Network would die with it on a Wi-Fi to
                // cellular hand-over, while a protected unbound one follows the current default network.
                prepareSocket = { socket -> protect(socket) },
                ownerUid = { protocol, src, srcPort, dst, dstPort -> ownerUid(cm, protocol, src, srcPort, dst, dstPort) },
                subjectOf = ::subjectOf,
                onFailure = { why ->
                    Log.w(TAG, "forwarder failure: $why")
                    val message = if (why.startsWith(DnsForwarder.DOH_FAILED)) {
                        "The encrypted resolver (${why.removePrefix(DnsForwarder.DOH_FAILED).trim()}) stopped answering, so the session " +
                            "ended and lookups work again. Pick another resolver, or your network's, before the next session."
                    } else {
                        FORWARDER_FAILED_MESSAGE
                    }
                    scope.launch { stopSession(message) }
                },
                // Read per lookup, so a change in the panel applies to the running session at once.
                blocks = { subject, host ->
                _policy.value.blocks(subject, host, listed = { h, ids -> BundledLists.listed(this, h, ids) }) != null
            },
                upstreamOf = { _upstream.value },
            )
        } catch (e: Exception) {
            // The forwarder opens its upstream socket as it is built; EPERM there means the Network toggle went off.
            runCatching { pfd.close() }
            return if (e is SocketException) VpnStatus.NETWORK_OFF_MESSAGE else "Could not start the forwarder: ${e.javaClass.simpleName}."
        }
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
        _state.update { it.copy(resolver = resolverLabel(resolvers)) }
        return null
    }

    /**
     * Keeps the upstream resolvers in step with the physical network for the whole session. Tunnels is
     * excluded from its own VPN, so its default network is the underlying one; a VPN network is still
     * never adopted, since its resolvers would point back into a tunnel.
     */
    private fun followDefaultNetwork(cm: ConnectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = adopt(cm, network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = adopt(cm, network)
            override fun onLost(network: Network) {
                // Nothing to forward to until a network comes back; apps cannot resolve without one either.
                _state.update { it.copy(resolver = null) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "default network callback not registered: ${e.javaClass.simpleName}")
        }
    }

    /** Runs on the connectivity thread: only volatile and atomic state is touched. */
    private fun adopt(cm: ConnectivityManager, network: Network) {
        val fwd = forwarder ?: return
        val physical = if (VpnStatus.isVpn(cm, network)) VpnStatus.underlyingNetwork(cm) else network
        val resolvers = VpnStatus.resolversOf(cm, physical)
        if (resolvers != fwd.resolvers) fwd.resolvers = resolvers
        _state.update { it.copy(resolver = resolverLabel(resolvers)) }
    }

    /** The encrypted provider when one is chosen, otherwise the network resolver's address. */
    private fun resolverLabel(resolvers: List<InetAddress>): String =
        _upstream.value.takeIf { it.encrypted }?.label ?: resolvers.first().hostAddress.orEmpty()

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
        if (!active) {
            if (starting) {
                // Still opening: the start coroutine sees [stopping] and tears down once the tunnel is up.
                _state.update { it.copy(ending = true, message = reason) }
                return
            }
            _state.update { it.copy(message = reason) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        active = false
        timers?.cancel()
        tearDown()
        val totals = counter?.totals ?: SessionTotals()
        _state.update { it.copy(ending = true, totals = totals) }
        val mySerial = serial
        scope.launch {
            // The last rows reach the store BEFORE the panel learns the session ended: its automatic
            // rescan on running=false must find them.
            flush(force = true)
            _state.update { it.copy(running = false, ending = false, totals = totals, message = reason) }
            if (serial == mySerial) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun tearDown() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
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
        const val FORWARDER_FAILED_MESSAGE = "The DNS forwarder stopped unexpectedly; the session was ended so lookups work again."

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

        private val _policy = MutableStateFlow(BlockPolicy())
        /** What sessions block. The panel loads it from the encrypted store and saves changes there too. */
        val policy: StateFlow<BlockPolicy> = _policy.asStateFlow()

        /** Applies [policy] to the running session (if any) and to the next ones. The caller stores it. */
        fun setPolicy(policy: BlockPolicy) {
            _policy.value = policy
        }

        private val _upstream = MutableStateFlow(Upstream())
        /** Where forwarded lookups go. Loaded and saved like [policy]. */
        val upstream: StateFlow<Upstream> = _upstream.asStateFlow()

        /** Applies [upstream] to the running session (from its next lookup) and to the next ones. The caller stores it. */
        fun setUpstream(upstream: Upstream) {
            _upstream.value = upstream
            _state.update { s -> if (s.running && s.resolver != null) s.copy(resolver = if (upstream.encrypted) upstream.label else "your network's resolver") else s }
        }
        /** True until the session's last rows are stored, so a scan started on the flip sees them all. */
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
