package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.stronghorse44.tunnels.lan.BindGuard
import io.github.stronghorse44.tunnels.lan.DnsProbe
import io.github.stronghorse44.tunnels.lan.DnsVerdict
import io.github.stronghorse44.tunnels.lan.DropCounter
import io.github.stronghorse44.tunnels.lan.HttpLite
import io.github.stronghorse44.tunnels.lan.LanAddresses
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanScope
import io.github.stronghorse44.tunnels.lan.MdnsTypes
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.ProbeBinder
import io.github.stronghorse44.tunnels.lan.ResolverScope
import io.github.stronghorse44.tunnels.lan.Ssdp
import io.github.stronghorse44.tunnels.lan.SsdpResponse
import io.github.stronghorse44.tunnels.lan.Upnp
import io.github.stronghorse44.tunnels.lan.UpnpDescription
import io.github.stronghorse44.tunnels.model.ScanProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.random.Random

/** Everything the scan learned about one LAN host, by IP. Mutable while the scan runs. */
class LanHostRecord(val ip: String) {
    val names: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val mdnsTypes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val models: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val ssdpTypes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val ssdpLocations: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    @Volatile var ssdpServer: String? = null
    val openPorts: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    val upnp: Boolean get() = ssdpTypes.isNotEmpty() || ssdpLocations.isNotEmpty() || ssdpServer != null
}

/** The router checks' raw results. */
data class RouterFacts(
    val gateway: String?,
    val upnpIgd: String,
    val description: UpnpDescription?,
    /** Null when no resolver was probed (none configured, or the configured one is outside the LAN). */
    val dnsVerdict: DnsVerdict?,
    val dnsIsGateway: Boolean?,
    /** Where the first configured resolver lives; null when none is configured. */
    val dnsScope: ResolverScope?,
    val privateDns: Boolean?,
)

data class LanScanOutput(
    val hosts: Map<String, LanHostRecord>,
    val router: RouterFacts,
    /** Stages that hit their time budget, or lost requests to a system limit, and returned partial results. */
    val partial: List<String>,
    /** Distinct discovered addresses dropped before any probe because they lay outside the confirmed network. Count only. */
    val droppedOutOfScope: Int = 0,
    /** Probes not sent because binding to the confirmed network failed (it was lost mid-scan). */
    val probesSkipped: Int = 0,
    /** False when nothing was sent because there was no confirmed prefix or network to stay inside. */
    val scanned: Boolean = true,
)

/** Pins sockets to the confirmed Wi-Fi [network]; a failure throws and the [BindGuard] treats the network as gone. */
private class NetworkBinder(private val network: Network) : ProbeBinder {
    override fun bind(socket: Socket) = network.bindSocket(socket)
    override fun bind(socket: DatagramSocket) = network.bindSocket(socket)
}

/**
 * The LAN scan proper: mDNS browse and resolve, SSDP M-SEARCH, TCP connect scan, router checks. Runs
 * only after the own-network gate allowed it. Every stage has a time budget and returns what it has.
 */
class LanScanner(private val context: Context) {
    private val executor = Dispatchers.IO.asExecutor()

    suspend fun scan(state: WifiState, progress: ScanProgress): LanScanOutput = withContext(Dispatchers.IO) {
        val hosts = ConcurrentHashMap<String, LanHostRecord>()
        val partial = ArrayList<String>()
        val gateway = state.gateway?.hostAddress

        // Every connect and probe below stays inside the prefixes the gate confirmed (see LanScope). With no
        // prefixes there is nothing to stay inside, so nothing is sent at all, not even the multicast queries.
        // Likewise every socket must be bound to the confirmed Wi-Fi before it sends (see BindGuard): an unbound
        // one would go out over the default network. A failed bind means the network is gone and ends the scan.
        val prefixes = state.prefixes
        val network = state.network
        if (prefixes.isEmpty() || network == null) {
            return@withContext LanScanOutput(
                hosts, RouterFacts(null, LanKeys.UNKNOWN, null, null, null, null, null), listOf(PARTIAL_SCOPE), scanned = false,
            )
        }
        val guard = BindGuard(NetworkBinder(network))
        val own = prefixes.map { it.first }
        val dropped = DropCounter()
        fun record(ip: String): LanHostRecord? {
            val address = LanScope.parseLiteral(ip)
            if (address == null) {
                dropped.add(ip)
                return null
            }
            if (LanScope.isOwn(address, own)) return null
            if (!LanScope.accepts(address, prefixes, own)) {
                dropped.add(address)
                return null
            }
            if (hosts.size >= MAX_HOSTS && !hosts.containsKey(ip)) return null
            return hosts.getOrPut(ip) { LanHostRecord(ip) }
        }

        progress.report(0, STAGES, STAGE_DISCOVERY)
        val mdnsFailures = withTimeoutOrNull(DISCOVERY_BUDGET_MS) { discoverMdns(network, prefixes, own, dropped, ::record) }
        if (mdnsFailures == null || mdnsFailures > 0) partial += "mdns"
        val ssdp = withTimeoutOrNull(SSDP_BUDGET_MS) { ssdpSearch(guard) } ?: run { partial += "ssdp"; emptyList() }
        // Only replies from addresses that passed the scope count as "the network answered SSDP".
        var ssdpInScope = false
        for ((ip, response) in ssdp) {
            val rec = record(ip) ?: continue
            ssdpInScope = true
            response.st?.let { rec.ssdpTypes += it }
            response.location?.let { if (rec.ssdpLocations.size < 4) rec.ssdpLocations += it }
            response.server?.let { rec.ssdpServer = it }
        }
        val ssdpResponded = ssdpInScope && "ssdp" !in partial
        gateway?.let { record(it) }

        progress.report(1, STAGES, STAGE_PORTS)
        val targets = hosts.keys.sortedWith(compareBy({ it != gateway }, { it })).take(MAX_PORT_SCAN_HOSTS)
        val portsDone = withTimeoutOrNull(PORTS_BUDGET_MS) { portScan(guard, targets, hosts, prefixes, own, dropped); true }
        if (portsDone == null) partial += "ports"

        progress.report(2, STAGES, STAGE_ROUTER)
        val router = withTimeoutOrNull(ROUTER_BUDGET_MS) { routerChecks(state, gateway, gateway?.let { hosts[it] }, ssdpResponded, guard) }
            ?: run {
                partial += "router"
                RouterFacts(gateway, LanKeys.UNKNOWN, null, null, null, resolverScope(state), state.linkProperties?.isPrivateDnsActive)
            }
        progress.report(STAGES, STAGES, "done")
        if (guard.lost) partial += PARTIAL_NETWORK
        LanScanOutput(hosts, router, partial, dropped.count, guard.skipped)
    }

    // ---- mDNS -------------------------------------------------------------------------------------

    /**
     * Browses the curated types in batches of [BROWSE_BATCH] and resolves what they find with at most
     * [RESOLVE_CONCURRENCY] callbacks registered at once. NsdService caps one client at 10 outstanding requests
     * (FAILURE_MAX_LIMIT beyond that), so browses plus resolves stay at 8. Returns how many browse starts or
     * resolve registrations the system refused even after a retry; the caller marks the stage partial then.
     */
    private suspend fun discoverMdns(
        network: Network?,
        prefixes: List<Pair<InetAddress, Int>>,
        own: List<InetAddress>,
        dropped: DropCounter,
        record: (String) -> LanHostRecord?,
    ): Int {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return 0
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val failures = AtomicInteger()
        coroutineScope {
            val browsers = launch {
                for (batch in MdnsTypes.types.chunked(BROWSE_BATCH)) {
                    coroutineScope {
                        for (type in batch) launch { browse(nsd, network, type, found, seen, failures) }
                    }
                }
                found.close()
            }
            val resolveLimit = Semaphore(RESOLVE_CONCURRENCY)
            for (info in found) {
                launch {
                    resolveLimit.withPermit {
                        var outcome = runCatching { resolve(nsd, info) }.getOrDefault(ResolveOutcome.FAILED)
                        if (outcome.registrationRefused) {
                            delay(RETRY_DELAY_MS)
                            outcome = runCatching { resolve(nsd, info) }.getOrDefault(ResolveOutcome.FAILED)
                            if (outcome.registrationRefused) failures.incrementAndGet()
                        }
                        val resolved = outcome.info ?: return@withPermit
                        val address = pickAddress(resolved.hostAddresses) { LanScope.accepts(it, prefixes, own) }
                        if (address == null) {
                            // Nothing usable (for example only link-local addresses): counted, not probed.
                            resolved.hostAddresses.firstOrNull()?.let { dropped.add(it) }
                            return@withPermit
                        }
                        val rec = record(address) ?: return@withPermit
                        resolved.serviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (rec.names.size < 4) rec.names += it.take(MAX_NAME) }
                        resolved.serviceType?.let { rec.mdnsTypes += MdnsTypes.normalize(it) }
                        for (key in MODEL_TXT_KEYS) {
                            val bytes = runCatching { resolved.attributes[key] }.getOrNull() ?: continue
                            val value = String(bytes, Charsets.UTF_8).trim()
                            if (value.isNotEmpty() && rec.models.size < 4) rec.models += value.take(MAX_NAME)
                        }
                    }
                }
            }
            browsers.join()
        }
        return failures.get()
    }

    /**
     * Browses one service type for [MDNS_WINDOW_MS], sending each newly found service to [found]. A refused
     * start (typically FAILURE_MAX_LIMIT) is retried once after a short pause; a second refusal counts as a failure.
     */
    private suspend fun browse(
        nsd: NsdManager,
        network: Network?,
        type: String,
        found: Channel<NsdServiceInfo>,
        seen: MutableSet<String>,
        failures: AtomicInteger,
    ) {
        repeat(2) { attempt ->
            val startFailed = CompletableDeferred<Int>()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    startFailed.complete(errorCode)
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    val info = serviceInfo ?: return
                    val key = "${info.serviceName}|${MdnsTypes.normalize(info.serviceType.orEmpty())}"
                    if (seen.size < MAX_SERVICES && seen.add(key)) found.trySend(info)
                }
            }
            val started = runCatching {
                if (network != null) nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
                else nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.isSuccess
            if (!started) {
                if (attempt == 1) failures.incrementAndGet() else delay(RETRY_DELAY_MS)
                return@repeat
            }
            val refused = try {
                withTimeoutOrNull(MDNS_WINDOW_MS) { startFailed.await() } != null
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
            if (!refused) return
            if (attempt == 1) failures.incrementAndGet() else delay(RETRY_DELAY_MS)
        }
    }

    /** What one resolve attempt produced. */
    private class ResolveOutcome(val info: NsdServiceInfo?, val registrationRefused: Boolean) {
        companion object {
            val FAILED = ResolveOutcome(null, false)
            val REFUSED = ResolveOutcome(null, true)
        }
    }

    /** Resolves one service to its addresses with the API 34 callback; times out at [RESOLVE_TIMEOUT_MS]. */
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): ResolveOutcome {
        var registered: NsdManager.ServiceInfoCallback? = null
        try {
            return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<ResolveOutcome> { cont ->
                    val done = AtomicBoolean(false)
                    fun finish(result: ResolveOutcome) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                    }
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(ResolveOutcome.REFUSED)
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.hostAddresses.isNotEmpty()) finish(ResolveOutcome(serviceInfo, false))
                        }
                        override fun onServiceLost() = finish(ResolveOutcome.FAILED)
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    registered = callback
                    runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure { finish(ResolveOutcome.FAILED) }
                }
            } ?: ResolveOutcome.FAILED
        } finally {
            registered?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    /**
     * Prefers an address inside the confirmed network ([inScope]); only when the service offers none is an
     * outside one returned, so [record] counts the drop. IPv4 before IPv6, a non-link-local IPv6 one; never loopback.
     */
    private fun pickAddress(all: List<InetAddress>, inScope: (InetAddress) -> Boolean): String? {
        val addresses = all.filter(inScope).ifEmpty { all }
        val v4 = addresses.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (v4 != null) return v4.hostAddress
        val v6 = addresses.filterIsInstance<Inet6Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        return v6?.hostAddress?.substringBefore('%')
    }

    // ---- SSDP -------------------------------------------------------------------------------------

    /** One M-SEARCH (repeated once) and [SSDP_WINDOW_MS] of listening under a MulticastLock. */
    private suspend fun ssdpSearch(guard: BindGuard): List<Pair<String, SsdpResponse>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, SsdpResponse>>()
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("tunnels-homenet")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                if (!guard.bindUdp(socket)) return@withContext out
                socket.soTimeout = 300
                val message = Ssdp.mSearch(mx = 2).toByteArray(Charsets.US_ASCII)
                val target = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
                socket.send(DatagramPacket(message, message.size, target))
                val buffer = ByteArray(4096)
                val end = System.nanoTime() + SSDP_WINDOW_MS * 1_000_000
                var resent = false
                while (System.nanoTime() < end && out.size < MAX_SSDP_RESPONSES) {
                    currentCoroutineContext().ensureActive()
                    if (!resent && System.nanoTime() > end - (SSDP_WINDOW_MS / 2) * 1_000_000) {
                        resent = true
                        runCatching { socket.send(DatagramPacket(message, message.size, target)) }
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address?.hostAddress ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    Ssdp.parseResponse(text)?.let { out += ip to it }
                }
            }
        } catch (_: Exception) {
            // No multicast on this network, or the socket was refused: SSDP simply finds nothing, and the
            // router verdict stays "unknown" because nothing answered.
        } finally {
            lock?.let { runCatching { it.release() } }
        }
        out
    }

    // ---- TCP connect scan ---------------------------------------------------------------------------

    private suspend fun portScan(
        guard: BindGuard,
        targets: List<String>,
        hosts: Map<String, LanHostRecord>,
        prefixes: List<Pair<InetAddress, Int>>,
        own: List<InetAddress>,
        dropped: DropCounter,
    ) = coroutineScope {
        val inFlight = Semaphore(PORT_CONCURRENCY)
        for (ip in targets) {
            // Second guard: the targets were already scoped when recorded, and are checked again right before connecting.
            val address = LanScope.parseLiteral(ip)?.takeIf { LanScope.accepts(it, prefixes, own) }
            if (address == null) {
                dropped.add(ip)
                continue
            }
            if (guard.lost) break
            for (port in PortCatalog.ports) {
                launch(Dispatchers.IO) {
                    inFlight.withPermit {
                        if (!isActive) return@withPermit
                        val socket = Socket()
                        try {
                            // A failed bind skips this probe; it must never connect over the default network.
                            if (!guard.bindTcp(socket)) return@withPermit
                            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                            hosts[ip]?.openPorts?.add(port)
                        } catch (_: Exception) {
                            // Closed, filtered or unreachable: not open.
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }
            yield()
        }
    }

    // ---- Router -------------------------------------------------------------------------------------

    /** The first configured resolver (IPv4 preferred) and where it lives; null when none is configured. */
    private fun resolverScope(state: WifiState): ResolverScope? {
        val resolver = pickResolver(state) ?: return null
        return LanAddresses.resolverScope(resolver, state.gateway ?: state.gateway6, state.prefixes)
    }

    private fun pickResolver(state: WifiState): InetAddress? {
        val dns = state.dnsServers
        return dns.firstOrNull { it is Inet4Address } ?: dns.firstOrNull()
    }

    private suspend fun routerChecks(
        state: WifiState,
        gateway: String?,
        gatewayRecord: LanHostRecord?,
        ssdpResponded: Boolean,
        guard: BindGuard,
    ): RouterFacts {
        val lp = state.linkProperties
        val privateDns = lp?.isPrivateDnsActive
        val dns = state.dnsServers
        val dnsIsGateway = if (gateway == null || dns.isEmpty()) null else dns.any { it.hostAddress == gateway }

        var description: UpnpDescription? = null
        var upnpIgd = LanKeys.UNKNOWN
        if (gateway != null) {
            val locations = gatewayRecord?.ssdpLocations?.toList().orEmpty()
                .filter { HttpLite.parseUrl(it)?.host == gateway }
                .take(3)
            for (location in locations) {
                val d = withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchDescription(guard, location) } ?: continue
                description = description ?: d
                if (d.hasIgd) {
                    description = d
                    break
                }
            }
            val advertisesIgd = gatewayRecord?.ssdpTypes?.any(Ssdp::isIgdType) == true
            upnpIgd = Upnp.igdVerdict(description, advertisesIgd, locations.size, ssdpResponded, gatewayProbed = gatewayRecord != null)
        }

        // The hijack probe obeys the same scope as every other probe: it goes only to a resolver inside the
        // confirmed network (LanScope), never to a public one handed out by DHCP and never to a private address
        // outside the prefix. [scope] below only classifies what the phone was configured with, for the card.
        val resolver = pickResolver(state)
        val scope = resolverScope(state)
        val verdict = if (resolver != null && LanScope.accepts(resolver, state.prefixes, state.prefixes.map { it.first })) {
            withTimeoutOrNull(DNS_TIMEOUT_MS * 2 + 500) { dnsProbe(guard, resolver) }
        } else {
            null
        }
        return RouterFacts(gateway, upnpIgd, description, verdict, dnsIsGateway, scope, privateDns)
    }

    /** Fetches a UPnP description over a raw socket (plain http to the gateway only), capped at 64 KB. */
    private suspend fun fetchDescription(guard: BindGuard, url: String): UpnpDescription? = withContext(Dispatchers.IO) {
        val target = HttpLite.parseUrl(url) ?: return@withContext null
        // The host was matched to the gateway's address literal already; parse it strictly, never resolve a name.
        val address = LanScope.parseLiteral(target.host) ?: return@withContext null
        try {
            Socket().use { socket ->
                if (!guard.bindTcp(socket)) return@withContext null
                socket.connect(InetSocketAddress(address, target.port), FETCH_TIMEOUT_MS.toInt())
                socket.soTimeout = FETCH_TIMEOUT_MS.toInt()
                socket.getOutputStream().apply { write(HttpLite.getRequest(target).toByteArray(Charsets.US_ASCII)); flush() }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                val input = socket.getInputStream()
                while (buffer.size() < HttpLite.MAX_BODY + 8192) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
                val response = HttpLite.parseResponse(buffer.toString("UTF-8")) ?: return@withContext null
                if (response.status != 200) return@withContext null
                Upnp.scan(response.body)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Asks [server] for a name that cannot exist; one retry. Null when it never answered. */
    private suspend fun dnsProbe(guard: BindGuard, server: InetAddress): DnsVerdict? = withContext(Dispatchers.IO) {
        val id = Random.nextInt(1, 0xFFFF)
        val query = DnsProbe.buildQuery(DnsProbe.probeName(), id)
        try {
            DatagramSocket().use { socket ->
                if (!guard.bindUdp(socket)) return@withContext null
                socket.soTimeout = DNS_TIMEOUT_MS.toInt()
                val buffer = ByteArray(1500)
                repeat(2) {
                    socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val verdict = DnsProbe.classify(id, packet.data, packet.length)
                        if (verdict != DnsVerdict.MISMATCH) return@withContext verdict
                    } catch (_: SocketTimeoutException) {
                        // retry once
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val STAGES = 3
        const val STAGE_DISCOVERY = "discovery"
        const val STAGE_PORTS = "ports"
        const val STAGE_ROUTER = "router"
        /** Reported in [LanScanOutput.partial] when the link has no address prefix, so nothing was scanned. */
        const val PARTIAL_SCOPE = "scope"
        /** Reported in [LanScanOutput.partial] when a socket could not be bound to the confirmed network mid-scan. */
        const val PARTIAL_NETWORK = "network"

        const val MAX_HOSTS = 50
        const val MAX_PORT_SCAN_HOSTS = 32
        const val MAX_SERVICES = 120
        const val MAX_SSDP_RESPONSES = 150
        const val MAX_NAME = 40
        /** Service types browsed at once; with [RESOLVE_CONCURRENCY] this stays under NsdService's 10-request cap. */
        const val BROWSE_BATCH = 4
        /** Per batch; 16 types in 4 batches make a 20 s browse. mDNS responders answer within the first seconds. */
        const val MDNS_WINDOW_MS = 5_000L
        const val RESOLVE_TIMEOUT_MS = 2_500L
        const val RESOLVE_CONCURRENCY = 4
        const val RETRY_DELAY_MS = 500L
        const val DISCOVERY_BUDGET_MS = 28_000L
        const val SSDP_WINDOW_MS = 4_000L
        const val SSDP_BUDGET_MS = 6_000L
        const val PORT_CONCURRENCY = 16
        const val CONNECT_TIMEOUT_MS = 400
        const val PORTS_BUDGET_MS = 25_000L
        const val FETCH_TIMEOUT_MS = 3_000L
        const val DNS_TIMEOUT_MS = 2_000L
        const val ROUTER_BUDGET_MS = 12_000L

        private val MODEL_TXT_KEYS = listOf("model", "md", "ty", "product", "manufacturer", "am")
    }
}
