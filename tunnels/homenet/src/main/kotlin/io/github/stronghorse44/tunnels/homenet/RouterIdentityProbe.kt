package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.wifi.WifiManager
import io.github.stronghorse44.tunnels.lan.BindGuard
import io.github.stronghorse44.tunnels.lan.LanScope
import io.github.stronghorse44.tunnels.lan.RouterIdentity
import io.github.stronghorse44.tunnels.lan.Ssdp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the router said about itself, raw, **in memory only**: the gate turns it into keyed tokens at once and drops it.
 * [probed] is false when nothing was sent (no IPv4 gateway inside the network); [lost] is true when a socket could not be
 * bound to the Wi-Fi (it went away during the probe).
 */
data class SeenRouter(val uuids: List<String>, val spkiSha256: String?, val probed: Boolean, val lost: Boolean) {
    companion object {
        val NOT_PROBED = SeenRouter(emptyList(), null, probed = false, lost = false)
    }
}

/** Asks the gateway who it is. The gate takes one so device tests can put a fake router in its place. */
fun interface RouterIdentitySource {
    suspend fun probe(state: WifiState): SeenRouter
}

/**
 * The identity probe (spec 2.2): to the IPv4 gateway literal only, accepted by [LanScope] like Close it's target, never a
 * name or another address; every socket bound to the Wi-Fi ([BindGuard]).
 *
 * - **UPnP id:** one unicast SSDP search for root devices to gateway:1900, 1,200 ms of listening; only when the gateway
 *   gave no root-device id, one standard multicast search (MX 1, ST upnp:rootdevice) under the multicast lock, 1,500 ms
 *   (L8-Q7). Only replies whose source is the gateway count ([RouterIdentity.uuidsFrom]).
 * - **Admin page key**, in parallel: a TLS handshake with gateway:443 broken off at the certificate ([TlsPeek]), and the
 *   SHA-256 of the leaf's public key (its SubjectPublicKeyInfo), so a renewed certificate with the same key still matches.
 * - The whole probe has [BUDGET_MS]; what arrived by then counts, and every socket still open is closed. No HTTP, no
 *   login, nothing sent past the search and the handshake. Nothing is logged.
 */
class RouterIdentityProbe(context: Context) : RouterIdentitySource {
    private val app = context.applicationContext

    override suspend fun probe(state: WifiState): SeenRouter = withContext(Dispatchers.IO) {
        val network = state.network ?: return@withContext SeenRouter.NOT_PROBED
        val prefixes = state.prefixes
        val gateway = state.gateway?.hostAddress?.let(LanScope::parseLiteral)
            ?.takeIf { it is Inet4Address && LanScope.accepts(it, prefixes, prefixes.map { p -> p.first }) }
            ?: return@withContext SeenRouter.NOT_PROBED
        val guard = BindGuard(NetworkBinder(network, VpnFallthroughCheck(app, state)))
        val open = ConcurrentLinkedQueue<Closeable>()
        val found = Found()
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000
        try {
            coroutineScope {
                // At the end of the budget every socket still open is closed, which ends any wait on it at once.
                val watchdog = launch {
                    delay(BUDGET_MS)
                    closeAll(open)
                }
                val upnp = async(Dispatchers.IO) { upnp(guard, gateway, deadline, open, found) }
                val tls = async(Dispatchers.IO) { adminKey(guard, gateway, deadline, open, found) }
                upnp.await()
                tls.await()
                watchdog.cancel()
            }
        } finally {
            closeAll(open)
        }
        SeenRouter(found.uuids, found.spki, probed = true, lost = guard.lost)
    }

    private fun closeAll(open: ConcurrentLinkedQueue<Closeable>) {
        while (true) {
            val next = open.poll() ?: return
            runCatching { next.close() }
        }
    }

    /** What has arrived so far; read when the budget ends. */
    private class Found {
        @Volatile var uuids: List<String> = emptyList()
        @Volatile var spki: String? = null
    }

    private fun upnp(guard: BindGuard, gateway: InetAddress, deadline: Long, open: ConcurrentLinkedQueue<Closeable>, found: Found) {
        val gw = gateway.hostAddress ?: return
        val unicast = Ssdp.unicastSearch(gw).toByteArray(Charsets.US_ASCII)
        val direct = listen(guard, unicast, InetSocketAddress(gateway, Ssdp.PORT), gw, minOf(deadline, now() + UNICAST_MS * 1_000_000), open, found)
        if (direct || guard.lost) return
        // The router ignored the direct question: one standard discovery message to the local network (RJ, L8-Q7).
        val wifi = app.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("tunnels-homenet-id")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            val group = LanScope.parseLiteral(Ssdp.ADDRESS) ?: return
            val search = Ssdp.mSearch(mx = 1, st = RouterIdentity.ROOT_DEVICE).toByteArray(Charsets.US_ASCII)
            listen(guard, search, InetSocketAddress(group, Ssdp.PORT), gw, minOf(deadline, now() + MULTICAST_MS * 1_000_000), open, found)
        } finally {
            lock?.let { runCatching { it.release() } }
        }
    }

    /** Sends [message] once and collects replies until [until]; true when the gateway gave a root-device id. */
    private fun listen(
        guard: BindGuard,
        message: ByteArray,
        target: InetSocketAddress,
        gateway: String,
        until: Long,
        open: ConcurrentLinkedQueue<Closeable>,
        found: Found,
    ): Boolean {
        val replies = ArrayList<Pair<String, String>>()
        try {
            DatagramSocket().use { socket ->
                open += socket
                if (!guard.bindUdp(socket)) return false
                socket.send(DatagramPacket(message, message.size, target))
                val buffer = ByteArray(2048)
                while (replies.size < MAX_REPLIES) {
                    val left = ((until - now()) / 1_000_000).toInt()
                    if (left <= 0) break
                    socket.soTimeout = left
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val source = packet.address?.hostAddress ?: continue
                    // Replies from other devices are dropped at once, unread.
                    if (source != gateway) continue
                    replies += source to String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val ids = RouterIdentity.uuidsFrom(gateway, replies)
                    if (ids.isNotEmpty()) found.uuids = ids
                    if (ids.size >= RouterIdentity.MAX_UUIDS) break
                }
            }
        } catch (_: Exception) {
            // No reply, a refused socket or the budget's close: what arrived counts.
        }
        return RouterIdentity.uuidsFrom(gateway, replies).isNotEmpty()
    }

    private fun adminKey(guard: BindGuard, gateway: InetAddress, deadline: Long, open: ConcurrentLinkedQueue<Closeable>, found: Found) {
        val connectMs = minOf(CONNECT_MS, ((deadline - now()) / 1_000_000).toInt())
        if (connectMs <= 0) return
        val readMs = maxOf(1, ((deadline - now()) / 1_000_000).toInt() - connectMs)
        val leaf = TlsPeek.leaf(guard, gateway, HTTPS_PORT, connectMs, readMs) { open += it } ?: return
        val key = runCatching { leaf.publicKey?.encoded }.getOrNull() ?: return
        found.spki = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }

    private fun now() = System.nanoTime()

    companion object {
        const val BUDGET_MS = 3_000L
        const val UNICAST_MS = 1_200L
        const val MULTICAST_MS = 1_500L
        const val CONNECT_MS = 1_500
        const val HTTPS_PORT = 443
        private const val MAX_REPLIES = 16
    }
}
