package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.Network
import android.system.Os
import android.system.OsConstants
import android.system.StructMsghdr
import android.system.StructTimeval
import io.github.stronghorse44.tunnels.lan.BindGuard
import io.github.stronghorse44.tunnels.lan.CertFacts
import io.github.stronghorse44.tunnels.lan.Greetings
import io.github.stronghorse44.tunnels.lan.HttpLite
import io.github.stronghorse44.tunnels.lan.HttpTarget
import io.github.stronghorse44.tunnels.lan.IcmpEcho
import io.github.stronghorse44.tunnels.lan.LanScope
import io.github.stronghorse44.tunnels.lan.LanSweep
import io.github.stronghorse44.tunnels.lan.LookCloser
import io.github.stronghorse44.tunnels.lan.NetBios
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.ReverseDns
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.random.Random

/**
 * One UDP question to [target]:[port] on a socket bound to the confirmed network, waiting [timeoutMs] for the reply;
 * [build] gives the packet and its parser for a random id. Null for no reply, an unreadable one or a lost network.
 */
internal fun <T> askUdp(guard: BindGuard, timeoutMs: Int, target: InetAddress, port: Int, build: (Int) -> Pair<ByteArray, (ByteArray, Int) -> T?>): T? = try {
    val id = Random.nextInt(1, 0xFFFF)
    val (packet, parse) = build(id)
    if (packet.isEmpty()) {
        null
    } else {
        DatagramSocket().use { socket ->
            if (!guard.bindUdp(socket)) return null
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(target, port)))
            val buffer = ByteArray(1500)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            parse(reply.data, reply.length)
        }
    }
} catch (_: Exception) {
    null
}

/** The device's own answer to a Windows name query (NetBIOS, port 137): its names, or null. */
internal fun askNetBios(guard: BindGuard, timeoutMs: Int, address: InetAddress): List<NetBios.Name>? =
    askUdp(guard, timeoutMs, address, NetBios.PORT) { id -> NetBios.query(id) to { b: ByteArray, n: Int -> NetBios.parse(id, b, n) } }

/** The device's own answer to an mDNS question for its address's name, asked of it directly (port 5353): the name, or null. */
internal fun askMdnsName(guard: BindGuard, timeoutMs: Int, address: InetAddress, ip: String): String? =
    askUdp(guard, timeoutMs, address, LookCloserProbe.MDNS_PORT) { id ->
        (ReverseDns.query(ip, id) ?: ByteArray(0)) to { b: ByteArray, n: Int -> ReverseDns.parse(id, b, n) }
    }?.let(ReverseDns::deviceName)

/** One look's outcome: the live findings, or why nothing was sent. */
data class CloserOutcome(val findings: LookCloser.Findings?, val error: String? = null)

/**
 * "Look closer" (RJ, 2026-10-11): asks the one device the user tapped what it is. Runs only when the user taps, only on
 * the network the gate confirmed, only to an address inside it (LanScope), and every socket is bound to that network
 * (BindGuard), as the scan's are. It tries the catalog's ports plus the telling extras, reads what open ones say about
 * themselves (a web page's title, a certificate's names, an SSH, camera or login greeting), asks for its names (Windows
 * name query, a direct mDNS question, the router) and pings it once for the system hint. It sends no login, no form and
 * nothing past a greeting: a TLS handshake is broken off once the certificate is read. Only [LookCloser.Summary] is kept.
 */
class LookCloserProbe(private val context: Context, private val gate: NetworkGate) {

    /** [known] are the names and maker the scan already had for the device; [onStep] says what it is doing now. */
    suspend fun look(ip: String, known: List<String>, onStep: (String) -> Unit): CloserOutcome = withContext(Dispatchers.IO) {
        val decision = try {
            gate.check()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (decision !is GateDecision.Allowed) return@withContext CloserOutcome(null, "This Wi-Fi is not confirmed as yours, so Tunnels sends nothing.")
        val state = decision.state
        val network = state.network ?: return@withContext CloserOutcome(null, "The phone is not on Wi-Fi.")
        val prefixes = state.prefixes
        val own = prefixes.map { it.first }
        val address = LanScope.parseLiteral(ip)?.takeIf { LanScope.accepts(it, prefixes, own) }
            ?: return@withContext CloserOutcome(null, "That address is not inside this network, so Tunnels does not ask it.")
        val guard = BindGuard(NetworkBinder(network))

        onStep("trying ${PortCatalog.closer.size} ports")
        val doors = withTimeoutOrNull(PORTS_BUDGET_MS) { doors(guard, address) } ?: Doors(emptyList(), emptyList())
        val open = doors.open

        onStep("reading what it says about itself")
        val http = HTTP_PORTS.filter { it in open }.take(2).firstNotNullOfOrNull { port -> withTimeoutOrNull(READ_BUDGET_MS) { http(guard, address, port) } }
        val cert = TLS_PORTS.firstOrNull { it in open }?.let { port -> withTimeoutOrNull(READ_BUDGET_MS) { certificate(guard, address, port) } }
        val ssh = if (22 in open) withTimeoutOrNull(READ_BUDGET_MS) { firstLine(guard, address, 22, null) }?.let(Greetings::sshSoftware) else null
        val rtsp = RTSP_PORTS.firstOrNull { it in open }?.let { port ->
            withTimeoutOrNull(READ_BUDGET_MS) { firstLine(guard, address, port, Greetings.rtspOptions(), whole = true) }?.let(Greetings::rtspServer)
        }
        val banner = BANNER_PORTS.firstOrNull { it in open }?.let { port ->
            withTimeoutOrNull(READ_BUDGET_MS) { bannerOf(guard, address, port) }
        }

        onStep("asking for its names")
        val netbios = withTimeoutOrNull(NAME_BUDGET_MS) { askNetBios(guard, UDP_TIMEOUT_MS, address) }
        val mdns = withTimeoutOrNull(NAME_BUDGET_MS) { askMdnsName(guard, UDP_TIMEOUT_MS, address, ip) }
        val gateway = state.gateway?.takeIf { LanScope.accepts(it, prefixes, own) }
        val router = gateway?.let { gw ->
            withTimeoutOrNull(NAME_BUDGET_MS) {
                askUdp(guard, UDP_TIMEOUT_MS, gw, DNS_PORT) { id -> (ReverseDns.query(ip, id) ?: ByteArray(0)) to { b: ByteArray, n: Int -> ReverseDns.parse(id, b, n) } }
            }?.let(ReverseDns::deviceName)
        }

        onStep("pinging it once")
        val ttl = withTimeoutOrNull(PING_BUDGET_MS) { pingTtl(network, address) }

        val findings = LookCloser.Findings(
            open = open, refused = doors.refused,
            httpTitle = http?.title, httpServer = http?.server, authRealm = http?.realm, redirect = http?.redirect,
            cert = cert, ssh = ssh, rtspServer = rtsp, banner = banner,
            netbiosName = netbios?.let(NetBios::computerName), workgroup = netbios?.let(NetBios::workgroup),
            mdnsName = mdns, routerName = router, ttl = ttl, known = known,
        )
        if (guard.lost) return@withContext CloserOutcome(findings, "The Wi-Fi changed during the look, so it stopped early and nothing was kept.")
        keep(decision.fingerprint.prefixTag, ip, findings)
        CloserOutcome(findings)
    }

    /** Writes the summary only (never the greetings) to its own 30-day events stream. A failure loses the summary, nothing else. */
    private suspend fun keep(tag: String, ip: String, findings: LookCloser.Findings) {
        try {
            val summary = findings.summary(System.currentTimeMillis())
            TunnelsStore.get(context).recordEvent(LookCloser.LEDGER, LookCloser.EVENT, LookCloser.subject(tag, ip), summary.encode())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private data class Doors(val open: List<Int>, val refused: List<Int>)

    /** Every port of [PortCatalog.closer], a few at a time: open (accepted), refused (something is there) or silent. */
    private suspend fun doors(guard: BindGuard, address: InetAddress): Doors {
        val open = ConcurrentHashMap.newKeySet<Int>()
        val refused = ConcurrentHashMap.newKeySet<Int>()
        val limit = Semaphore(PORT_CONCURRENCY)
        // The scope returns only when every probe has finished, so the sets are complete when read below.
        coroutineScope {
            for (info in PortCatalog.closer) {
                launch(Dispatchers.IO) {
                    limit.withPermit {
                        val socket = Socket()
                        try {
                            if (!guard.bindTcp(socket)) return@withPermit
                            socket.connect(InetSocketAddress(address, info.port), CONNECT_TIMEOUT_MS)
                            open += info.port
                        } catch (e: Exception) {
                            if (LanSweep.classify(e) == LanSweep.Answer.PRESENT) refused += info.port
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }
        }
        return Doors(open.sorted(), refused.sorted())
    }

    private data class HttpFacts(val title: String?, val server: String?, val realm: String?, val redirect: String?)

    /** One plain GET of `/`, read up to [Greetings.MAX_READ]: the page's title, server, login realm and redirect. */
    private fun http(guard: BindGuard, address: InetAddress, port: Int): HttpFacts? = try {
        Socket().use { socket ->
            if (!guard.bindTcp(socket)) return null
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS * 3)
            socket.soTimeout = READ_TIMEOUT_MS
            val request = HttpLite.getRequest(HttpTarget(address.hostAddress ?: return null, port, "/"))
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val raw = readUpTo(socket, Greetings.MAX_READ)
            val response = HttpLite.parseResponse(raw.toString(Charsets.UTF_8)) ?: return null
            HttpFacts(
                title = Greetings.httpTitle(response.body),
                server = Greetings.clean(response.headers["server"]),
                realm = Greetings.authRealm(response.headers),
                redirect = Greetings.redirect(response.status, response.headers),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The device's certificate: a TLS handshake whose trust check records the certificate and then refuses it, so the
     * handshake fails and nothing is ever sent over the connection.
     */
    private fun certificate(guard: BindGuard, address: InetAddress, port: Int): CertFacts? {
        val seen = AtomicReference<X509Certificate?>()
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                seen.set(chain?.firstOrNull())
                throw CertificateException("Tunnels only reads the certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val plain = Socket()
        try {
            if (!guard.bindTcp(plain)) return null
            plain.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS * 3)
            plain.soTimeout = READ_TIMEOUT_MS
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
            (tls.socketFactory.createSocket(plain, address.hostAddress, port, true) as SSLSocket).use { socket ->
                runCatching { socket.startHandshake() }
            }
        } catch (_: Exception) {
            // Refused, timed out or not TLS: no certificate.
        } finally {
            runCatching { plain.close() }
        }
        val cert = seen.get() ?: return null
        val alt = runCatching { cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1) as? String } }.getOrNull().orEmpty()
        return CertFacts.of(cert.subjectX500Principal.getName(X500Principal.RFC2253), cert.issuerX500Principal.getName(X500Principal.RFC2253), alt)
    }

    /** What the service on [port] sends first (after [request], when given): one line, or the whole reply when [whole]. */
    private fun firstLine(guard: BindGuard, address: InetAddress, port: Int, request: String?, whole: Boolean = false): String? = try {
        Socket().use { socket ->
            if (!guard.bindTcp(socket)) return null
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS * 3)
            socket.soTimeout = READ_TIMEOUT_MS
            request?.let { socket.getOutputStream().apply { write(it.toByteArray(Charsets.US_ASCII)); flush() } }
            val text = readUpTo(socket, 2048).toString(Charsets.ISO_8859_1)
            if (whole) text else text.lineSequence().firstOrNull()
        }
    } catch (_: Exception) {
        null
    }

    /** A login service's banner (FTP, telnet), read without sending anything. */
    private fun bannerOf(guard: BindGuard, address: InetAddress, port: Int): String? = try {
        Socket().use { socket ->
            if (!guard.bindTcp(socket)) return null
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS * 3)
            socket.soTimeout = BANNER_TIMEOUT_MS
            val bytes = readUpTo(socket, 512)
            Greetings.bannerLine(bytes)
        }
    } catch (_: Exception) {
        null
    }

    /** Reads until the peer closes, [max] bytes, or the read timeout; whatever came is returned. */
    private fun readUpTo(socket: Socket, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (out.size() < max) {
                val n = input.read(chunk, 0, minOf(chunk.size, max - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
        } catch (_: Exception) {
            // A timeout ends the read with what arrived.
        }
        return out.toByteArray()
    }

    /**
     * One ping over an ICMP datagram socket bound to [network], reading the reply's time-to-live from its control data.
     * Null when ping sockets are refused, nothing answers, or the TTL is not reported.
     */
    private fun pingTtl(network: Network, address: InetAddress): Int? {
        if (address !is Inet4Address) return null
        var fd: FileDescriptor? = null
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
            network.bindSocket(fd)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, IP_RECVTTL, 1)
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(UDP_TIMEOUT_MS.toLong()))
            val seq = Random.nextInt(1, 0xFFFF)
            val request = IcmpEcho.request(0, seq)
            Os.sendto(fd, request, 0, request.size, 0, address, 0)
            repeat(3) {
                val buffer = ByteBuffer.allocate(1500)
                val msg = StructMsghdr(null, arrayOf(buffer), null, 0)
                val n = Os.recvmsg(fd, msg, 0)
                if (n <= 0) return null
                val data = buffer.array().copyOf(minOf(n, buffer.capacity()))
                if (IcmpEcho.isReply(data, data.size, seq)) {
                    val ttl = msg.msg_control?.firstOrNull { it.cmsg_level == OsConstants.IPPROTO_IP && it.cmsg_type == OsConstants.IP_TTL }?.cmsg_data
                    return ttl?.let(::nativeInt)
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /** The TTL control message: an int in the phone's byte order (little-endian on Android's processors). */
    private fun nativeInt(bytes: ByteArray): Int? = when {
        bytes.size >= 4 -> ByteBuffer.wrap(bytes, 0, 4).order(java.nio.ByteOrder.nativeOrder()).int
        bytes.size == 1 -> bytes[0].toInt() and 0xFF
        else -> null
    }?.takeIf { it in 1..255 }

    companion object {
        const val PORT_CONCURRENCY = 16
        const val CONNECT_TIMEOUT_MS = 500
        const val READ_TIMEOUT_MS = 2_000
        const val BANNER_TIMEOUT_MS = 1_500
        const val UDP_TIMEOUT_MS = 1_500
        const val PORTS_BUDGET_MS = 15_000L
        const val READ_BUDGET_MS = 4_000L
        const val NAME_BUDGET_MS = 2_500L
        const val PING_BUDGET_MS = 3_000L
        const val MDNS_PORT = 5353
        const val DNS_PORT = 53

        /** Linux's IP_RECVTTL: asks for each reply's TTL as control data (not in OsConstants). */
        private const val IP_RECVTTL = 12

        val HTTP_PORTS = listOf(80, 8080, 8008, 5000, 1400, 8000)
        val TLS_PORTS = listOf(443, 8443, 5001)
        val RTSP_PORTS = listOf(554, 8554)
        val BANNER_PORTS = listOf(21, 23, 2323)
    }
}
