package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.stronghorse44.tunnels.lan.DnsProbe
import io.github.stronghorse44.tunnels.lan.DnsVerdict
import io.github.stronghorse44.tunnels.lan.HttpLite
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.MdnsTypes
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.Ssdp
import io.github.stronghorse44.tunnels.lan.SsdpResponse
import io.github.stronghorse44.tunnels.lan.Upnp
import io.github.stronghorse44.tunnels.lan.UpnpDescription
import io.github.stronghorse44.tunnels.model.ScanProgress
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
    val dnsVerdict: DnsVerdict?,
    val dnsIsGateway: Boolean?,
    val privateDns: Boolean?,
)

data class LanScanOutput(
    val hosts: Map<String, LanHostRecord>,
    val router: RouterFacts,
    /** Stages that hit their time budget and returned partial results. */
    val partial: List<String>,
)

/**
 * The LAN scan proper: mDNS browse and resolve, SSDP M-SEARCH, TCP connect scan, router checks. Runs
 * only after the own-network gate allowed it. Every stage has a time budget and returns what it has.
 */
class LanScanner(private val context: Context) {
    private val executor = Dispatchers.IO.asExecutor()

    suspend fun scan(state: WifiState, progress: ScanProgress): LanScanOutput = withContext(Dispatchers.IO) {
        val hosts = ConcurrentHashMap<String, LanHostRecord>()
        val partial = ArrayList<String>()
        val own = state.ownAddresses
        val gateway = state.gateway?.hostAddress
        fun record(ip: String): LanHostRecord? = if (ip in own || hosts.size >= MAX_HOSTS && !hosts.containsKey(ip)) null else hosts.getOrPut(ip) { LanHostRecord(ip) }

        progress.report(0, STAGES, STAGE_DISCOVERY)
        val mdnsDone = withTimeoutOrNull(DISCOVERY_BUDGET_MS) { discoverMdns(state.network, ::record); true }
        if (mdnsDone == null) partial += "mdns"
        val ssdp = withTimeoutOrNull(SSDP_BUDGET_MS) { ssdpSearch(state.network) } ?: run { partial += "ssdp"; emptyList() }
        for ((ip, response) in ssdp) {
            val rec = record(ip) ?: continue
            response.st?.let { rec.ssdpTypes += it }
            response.location?.let { if (rec.ssdpLocations.size < 4) rec.ssdpLocations += it }
            response.server?.let { rec.ssdpServer = it }
        }
        gateway?.let { record(it) }

        progress.report(1, STAGES, STAGE_PORTS)
        val targets = hosts.keys.sortedWith(compareBy({ it != gateway }, { it })).take(MAX_PORT_SCAN_HOSTS)
        val portsDone = withTimeoutOrNull(PORTS_BUDGET_MS) { portScan(state.network, targets, hosts); true }
        if (portsDone == null) partial += "ports"

        progress.report(2, STAGES, STAGE_ROUTER)
        val router = withTimeoutOrNull(ROUTER_BUDGET_MS) { routerChecks(state, gateway, gateway?.let { hosts[it] }) }
            ?: run { partial += "router"; RouterFacts(gateway, LanKeys.UNKNOWN, null, null, null, state.linkProperties?.isPrivateDnsActive) }
        progress.report(STAGES, STAGES, "done")
        LanScanOutput(hosts, router, partial)
    }

    // ---- mDNS -------------------------------------------------------------------------------------

    private suspend fun discoverMdns(network: Network?, record: (String) -> LanHostRecord?) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            val browsers = launch {
                coroutineScope {
                    for (type in MdnsTypes.types) launch { browse(nsd, network, type, found, seen) }
                }
                found.close()
            }
            val resolveLimit = Semaphore(RESOLVE_CONCURRENCY)
            for (info in found) {
                launch {
                    resolveLimit.withPermit {
                        val resolved = runCatching { resolve(nsd, info) }.getOrNull() ?: return@withPermit
                        val address = pickAddress(resolved.hostAddresses) ?: return@withPermit
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
    }

    /** Browses one service type for [MDNS_WINDOW_MS], sending each newly found service to [found]. */
    private suspend fun browse(nsd: NsdManager, network: Network?, type: String, found: Channel<NsdServiceInfo>, seen: MutableSet<String>) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
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
        if (!started) return
        try {
            delay(MDNS_WINDOW_MS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /** Resolves one service to its addresses with the API 34 callback; null on failure or timeout. */
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        var registered: NsdManager.ServiceInfoCallback? = null
        try {
            return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    fun finish(result: NsdServiceInfo?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                    }
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(null)
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.hostAddresses.isNotEmpty()) finish(serviceInfo)
                        }
                        override fun onServiceLost() = finish(null)
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    registered = callback
                    runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure { finish(null) }
                }
            }
        } finally {
            registered?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    /** Prefers a routable IPv4 address; falls back to a non-link-local IPv6 one; never loopback. */
    private fun pickAddress(addresses: List<InetAddress>): String? {
        val v4 = addresses.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (v4 != null) return v4.hostAddress
        val v6 = addresses.filterIsInstance<Inet6Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        return v6?.hostAddress?.substringBefore('%')
    }

    // ---- SSDP -------------------------------------------------------------------------------------

    /** One M-SEARCH (repeated once) and [SSDP_WINDOW_MS] of listening under a MulticastLock. */
    private suspend fun ssdpSearch(network: Network?): List<Pair<String, SsdpResponse>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, SsdpResponse>>()
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("tunnels-homenet")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                network?.let { runCatching { it.bindSocket(socket) } }
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
            // No multicast on this network, or the socket was refused: SSDP simply finds nothing.
        } finally {
            lock?.let { runCatching { it.release() } }
        }
        out
    }

    // ---- TCP connect scan ---------------------------------------------------------------------------

    private suspend fun portScan(network: Network?, targets: List<String>, hosts: Map<String, LanHostRecord>) = coroutineScope {
        val inFlight = Semaphore(PORT_CONCURRENCY)
        for (ip in targets) {
            val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
            for (port in PortCatalog.ports) {
                launch(Dispatchers.IO) {
                    inFlight.withPermit {
                        if (!isActive) return@withPermit
                        val socket = Socket()
                        try {
                            network?.let { runCatching { it.bindSocket(socket) } }
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

    private suspend fun routerChecks(state: WifiState, gateway: String?, gatewayRecord: LanHostRecord?): RouterFacts {
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
                val d = withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchDescription(state.network, location) } ?: continue
                description = description ?: d
                if (d.hasIgd) {
                    description = d
                    break
                }
            }
            val advertisesIgd = gatewayRecord?.ssdpTypes?.any(Ssdp::isIgdType) == true
            upnpIgd = when {
                description != null -> if (description.hasIgd) LanKeys.TRUE else if (advertisesIgd) LanKeys.TRUE else LanKeys.FALSE
                advertisesIgd -> LanKeys.TRUE
                locations.isNotEmpty() -> LanKeys.UNKNOWN
                else -> LanKeys.FALSE
            }
        }

        val resolver = dns.firstOrNull { it is Inet4Address } ?: dns.firstOrNull()
        val verdict = resolver?.let { withTimeoutOrNull(DNS_TIMEOUT_MS * 2 + 500) { dnsProbe(state.network, it) } }
        return RouterFacts(gateway, upnpIgd, description, verdict, dnsIsGateway, privateDns)
    }

    /** Fetches a UPnP description over a raw socket (plain http to the gateway only), capped at 64 KB. */
    private suspend fun fetchDescription(network: Network?, url: String): UpnpDescription? = withContext(Dispatchers.IO) {
        val target = HttpLite.parseUrl(url) ?: return@withContext null
        try {
            Socket().use { socket ->
                network?.let { runCatching { it.bindSocket(socket) } }
                socket.connect(InetSocketAddress(InetAddress.getByName(target.host), target.port), FETCH_TIMEOUT_MS.toInt())
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
    private suspend fun dnsProbe(network: Network?, server: InetAddress): DnsVerdict? = withContext(Dispatchers.IO) {
        val id = Random.nextInt(1, 0xFFFF)
        val query = DnsProbe.buildQuery(DnsProbe.probeName(), id)
        try {
            DatagramSocket().use { socket ->
                network?.let { runCatching { it.bindSocket(socket) } }
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

        const val MAX_HOSTS = 50
        const val MAX_PORT_SCAN_HOSTS = 32
        const val MAX_SERVICES = 120
        const val MAX_SSDP_RESPONSES = 150
        const val MAX_NAME = 40
        const val MDNS_WINDOW_MS = 10_000L
        const val RESOLVE_TIMEOUT_MS = 2_500L
        const val RESOLVE_CONCURRENCY = 8
        const val DISCOVERY_BUDGET_MS = 20_000L
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
