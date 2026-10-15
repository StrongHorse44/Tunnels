package io.github.stronghorse44.tunnels.traffic

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.stronghorse44.tunnels.dns.DnsMessage
import io.github.stronghorse44.tunnels.dns.Doh
import io.github.stronghorse44.tunnels.dns.IpPacket
import io.github.stronghorse44.tunnels.dns.IpPackets
import io.github.stronghorse44.tunnels.dns.ReplyGate
import io.github.stronghorse44.tunnels.dns.SessionCounter
import io.github.stronghorse44.tunnels.dns.Upstream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * The packet loop of a session. Reads IP packets from the TUN, keeps the UDP port-53 ones, counts each
 * query against the app that owns the socket, answers it here when the session blocks it, otherwise
 * forwards it and writes the answer back with fresh IP/UDP headers. Everything else is dropped (TCP 853 is counted as
 * encrypted DNS first). Plain threads; nothing here touches the UI or the store.
 *
 * Plain lookups (audit M4): each goes out on its own protected socket, connected to the resolver it is sent to, from a
 * port the system picks at random, and the answer is taken only from that resolver's port 53 with the query's id and
 * name ([ReplyGate]) within [PENDING_MS]. A device on the same Wi-Fi can no longer flood one fixed port with forged
 * answers, nor answer from its own address.
 *
 * While the TUN is established the phone's DNS depends on these threads, so neither may die quietly: a
 * loop that exits while the session is still meant to run reports through [onFailure] exactly once and
 * the service ends the session, which hands DNS back to the system.
 */
class DnsForwarder(
    private val tun: ParcelFileDescriptor,
    private val counter: SessionCounter,
    resolvers: List<InetAddress>,
    /** `VpnService.protect`, so our own packets never re-enter the tunnel. */
    private val prepareSocket: (DatagramSocket) -> Unit,
    /** `ConnectivityManager.getConnectionOwnerUid`, or -1. */
    private val ownerUid: (protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int) -> Int,
    private val subjectOf: (uid: Int) -> String,
    /** Called once, from a forwarder thread, when a loop ends while the session should still be running. */
    private val onFailure: (String) -> Unit = {},
    /** Whether a lookup of host by subject is blocked ([io.github.stronghorse44.tunnels.dns.BlockPolicy]): it then gets NXDOMAIN from here. */
    private val blocks: (subject: String, host: String) -> Boolean = { _, _ -> false },
    /** Where forwarded lookups go, read per lookup ([Upstream]): the network's resolver, or a DNS-over-HTTPS endpoint. */
    private val upstreamOf: () -> Upstream = { Upstream() },
) {
    /**
     * Upstream resolvers, tried in order of [resolverIndex]. The service replaces the list when the
     * underlying network changes (Wi-Fi to cellular, say), so the session keeps resolving. Never a public
     * fallback (audit L3): while it is empty, plain lookups are dropped, and the service ends the session.
     */
    @Volatile var resolvers: List<InetAddress> = resolvers
        set(value) {
            field = value
            resolverIndex.set(0)
        }

    private val output = FileOutputStream(tun.fileDescriptor)
    private val resolverIndex = AtomicInteger(0)
    private val failed = AtomicBoolean(false)
    @Volatile private var running = false
    /** Upstream sockets that could not be opened in a row; past [MAX_REOPENS] the forwarder reports failure. */
    private val openFailuresInRow = AtomicInteger(0)
    /** Sockets of lookups in flight, closed by [stop] so their threads end at once. */
    private val inFlight = ConcurrentHashMap.newKeySet<DatagramSocket>()

    init {
        // EPERM here means the GrapheneOS Network toggle is off; the service says so instead of starting.
        DatagramSocket().close()
    }

    val dropped = AtomicInteger(0)
    val answered = AtomicInteger(0)
    /** Lookups answered here with NXDOMAIN instead of being forwarded. */
    val blockedCount = AtomicInteger(0)

    /** Encrypted lookups in flight, each one HTTPS POST; bounded so a dead provider cannot pile up threads or memory. */
    private val doh = ThreadPoolExecutor(
        DOH_THREADS, DOH_THREADS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(DOH_QUEUE),
        { r -> Thread(r, "tunnels-dns-doh").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() },
    ).apply { allowCoreThreadTimeOut(true) }
    private val dohFailuresInRow = AtomicInteger(0)
    @Volatile private var dohLastAnswer = 0L

    /** Lookups answered by the encrypted resolver. */
    val encryptedAnswered = AtomicInteger(0)

    /** Plain lookups in flight, one connected socket each; bounded like [doh], so a dead resolver cannot pile up threads. */
    private val plain = ThreadPoolExecutor(
        PLAIN_THREADS, PLAIN_THREADS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(PLAIN_QUEUE),
        { r -> Thread(r, "tunnels-dns-upstream").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() },
    ).apply { allowCoreThreadTimeOut(true) }

    private val reader = Thread(::readLoop, "tunnels-dns-tun")

    /** The reader still runs. False once it has exited, whether or not [onFailure] fired yet. */
    val alive: Boolean get() = running && reader.isAlive

    fun start() {
        running = true
        reader.start()
    }

    /** Stops the threads; returns once the reader has exited (bounded wait). */
    fun stop() {
        running = false
        doh.shutdownNow()
        plain.shutdownNow()
        inFlight.forEach { runCatching { it.close() } }
        runCatching { reader.join(1500) }
    }

    private fun fail(what: String) {
        if (!running || !failed.compareAndSet(false, true)) return
        Log.w(TAG, "forwarder failed: $what")
        runCatching { onFailure(what) }
    }

    private fun readLoop() {
        var why = "TUN read loop ended"
        try {
            val buffer = ByteArray(32767)
            val fds = arrayOf(StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() })
            while (running) {
                fds[0].revents = 0
                val ready = try {
                    Os.poll(fds, POLL_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    why = "poll failed (errno ${e.errno})"
                    break
                }
                if (ready <= 0) continue
                if (fds[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                    why = "the tunnel interface closed"
                    break
                }
                val length = try {
                    Os.read(tun.fileDescriptor, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                    why = "tunnel read failed (errno ${e.errno})"
                    break
                } catch (_: InterruptedIOException) {
                    continue
                } catch (e: IOException) {
                    why = "tunnel read failed (${e.javaClass.simpleName})"
                    break
                }
                if (length <= 0) continue
                try {
                    handle(buffer, length)
                } catch (e: Exception) {
                    // One bad packet never ends the session.
                    dropped.incrementAndGet()
                    Log.w(TAG, "packet dropped: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Throwable) {
            why = "tunnel reader crashed (${e.javaClass.simpleName})"
        } finally {
            fail(why)
        }
    }

    private fun handle(buffer: ByteArray, length: Int) {
        when (val packet = IpPackets.parse(buffer, length)) {
            is IpPacket.Udp -> if (packet.dstPort == DnsMessage.PORT) query(packet) else dropped.incrementAndGet()
            is IpPacket.Other -> {
                val dstPort = packet.dstPort
                if (packet.protocol == IpPackets.PROTO_TCP && dstPort == DnsMessage.PORT_TLS) {
                    // Encrypted DNS attempt: count it against the app, nothing else can be read.
                    val uid = try {
                        ownerUid(OsConstants.IPPROTO_TCP, packet.src, packet.srcPort ?: 0, packet.dst, dstPort)
                    } catch (_: Exception) {
                        -1
                    }
                    counter.encrypted(subjectOf(uid))
                }
                dropped.incrementAndGet()
            }
            is IpPacket.Malformed -> dropped.incrementAndGet()
        }
    }

    private fun query(p: IpPacket.Udp) {
        val message = DnsMessage.parseOrNull(p.payload)
        if (message == null || message.isResponse) {
            dropped.incrementAndGet()
            return
        }
        val uid = try {
            ownerUid(OsConstants.IPPROTO_UDP, p.src, p.srcPort, p.dst, p.dstPort)
        } catch (_: Exception) {
            -1
        }
        val subject = subjectOf(uid)
        val name = message.queryName
        name?.let { counter.query(subject, it) }
        if (name != null && blocks(subject, name) && answerBlocked(p, subject)) return

        val upstreamChoice = upstreamOf()
        if (upstreamChoice.encrypted) {
            forwardEncrypted(p, upstreamChoice.url!!)
            return
        }

        val list = resolvers
        if (list.isEmpty()) {
            // No resolver on this network: nothing is sent anywhere (audit L3); the service ends the session.
            dropped.incrementAndGet()
            return
        }
        val resolver = list[Math.floorMod(resolverIndex.get(), list.size)]
        val queuedAt = System.currentTimeMillis()
        plain.execute { forwardPlain(p, message, resolver, queuedAt) }
    }

    /**
     * Sends [p]'s lookup to [resolver] from a fresh socket connected to it (a random source port the system picks) and
     * writes back the first reply [ReplyGate] accepts within [PENDING_MS] of [queuedAt]. An unanswered lookup is
     * forgotten; the app's resolver retries on its own.
     */
    private fun forwardPlain(p: IpPacket.Udp, query: DnsMessage, resolver: InetAddress, queuedAt: Long) {
        if (!running) return
        // Waited past its window in the queue: the app has given up on it, so nothing is opened or sent.
        if (System.currentTimeMillis() - queuedAt >= PENDING_MS) return
        var opened: DatagramSocket? = null
        val socket = try {
            DatagramSocket().also { s ->
                opened = s
                inFlight += s
                prepareSocket(s)
                s.connect(resolver, DnsMessage.PORT)
            }
        } catch (e: Exception) {
            opened?.let { inFlight -= it; runCatching { it.close() } }
            dropped.incrementAndGet()
            Log.w(TAG, "upstream socket failed: ${e.javaClass.simpleName}")
            if (openFailuresInRow.incrementAndGet() > MAX_REOPENS) fail("upstream socket failed (${e.javaClass.simpleName})")
            return
        }
        openFailuresInRow.set(0)
        try {
            socket.send(DatagramPacket(p.payload, p.payload.size))
            val buffer = ByteArray(RESPONSE_MAX)
            while (running) {
                val left = PENDING_MS - (System.currentTimeMillis() - queuedAt)
                if (left <= 0) return
                socket.soTimeout = left.toInt()
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    return
                }
                val reply = DnsMessage.parseOrNull(buffer, 0, datagram.length) ?: continue
                if (!ReplyGate.accepts(resolver, datagram.address, datagram.port, query, reply)) continue
                val packet = IpPackets.buildUdp(p.dst, p.src, DnsMessage.PORT, p.srcPort, buffer.copyOf(datagram.length))
                synchronized(output) { output.write(packet) }
                answered.incrementAndGet()
                return
            }
        } catch (e: IOException) {
            if (!running) return // closed by stop()
            // Try the next resolver for the following query; this one is lost (the app retries).
            resolverIndex.incrementAndGet()
            dropped.incrementAndGet()
            Log.w(TAG, "upstream lookup failed: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            dropped.incrementAndGet()
            Log.w(TAG, "reply dropped: ${e.javaClass.simpleName}")
        } finally {
            inFlight -= socket
            socket.close()
        }
    }

    /**
     * Sends [p]'s lookup to the DNS-over-HTTPS endpoint [url] from the pool and writes the answer back. A lookup the
     * provider does not answer is dropped, never retried in plain text: the app's resolver retries on its own. If the
     * provider answers nothing for [DOH_DEAD_MS] while lookups keep failing, the session is ended through [fail] so
     * the phone's DNS is handed back instead of staying broken.
     */
    private fun forwardEncrypted(p: IpPacket.Udp, url: String) {
        val query = p.payload
        val body = Doh.request(query)
        if (body == null) {
            dropped.incrementAndGet()
            return
        }
        if (dohLastAnswer == 0L) dohLastAnswer = System.currentTimeMillis()
        doh.execute {
            val answer = try {
                post(url, body)?.let { Doh.response(it, query) }
            } catch (e: IOException) {
                Log.w(TAG, "encrypted lookup failed: ${e.javaClass.simpleName}")
                null
            }
            if (!running) return@execute
            if (answer == null) {
                dropped.incrementAndGet()
                val failures = dohFailuresInRow.incrementAndGet()
                if (failures >= DOH_DEAD_FAILURES && System.currentTimeMillis() - dohLastAnswer > DOH_DEAD_MS) {
                    fail("$DOH_FAILED ${Upstream.hostOf(url)}")
                }
                return@execute
            }
            dohFailuresInRow.set(0)
            dohLastAnswer = System.currentTimeMillis()
            try {
                val packet = IpPackets.buildUdp(p.dst, p.src, DnsMessage.PORT, p.srcPort, answer)
                synchronized(output) { output.write(packet) }
                answered.incrementAndGet()
                encryptedAnswered.incrementAndGet()
            } catch (e: Exception) {
                dropped.incrementAndGet()
                Log.w(TAG, "encrypted reply dropped: ${e.javaClass.simpleName}")
            }
        }
    }

    /** One RFC 8484 POST. Tunnels is excluded from its own VPN, so this goes out over the physical network. */
    private fun post(url: String, body: ByteArray): ByteArray? {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = DOH_TIMEOUT_MS
            conn.readTimeout = DOH_TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", Doh.CONTENT_TYPE)
            conn.setRequestProperty("Accept", Doh.CONTENT_TYPE)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != HttpsURLConnection.HTTP_OK || conn.contentType?.startsWith(Doh.CONTENT_TYPE) != true) {
                runCatching { conn.errorStream?.close() }
                return null
            }
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > Doh.MAX_RESPONSE) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            // Closing the stream returns the connection to the keep-alive pool; on errors drop it.
            conn.disconnect()
            throw e
        }
    }

    /**
     * Writes a "no such domain" answer to [p] straight back into the tunnel. Nothing leaves the phone for a blocked
     * lookup. False when no answer could be built or written; the lookup is then forwarded as usual.
     */
    private fun answerBlocked(p: IpPacket.Udp, subject: String): Boolean {
        val reply = DnsMessage.nxdomain(p.payload) ?: return false
        return try {
            val packet = IpPackets.buildUdp(p.dst, p.src, DnsMessage.PORT, p.srcPort, reply)
            synchronized(output) { output.write(packet) }
            counter.blocked(subject)
            blockedCount.incrementAndGet()
            true
        } catch (e: Exception) {
            Log.w(TAG, "blocked answer not written: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val TAG = "TunnelsDns"
        private const val POLL_MS = 500
        /** Unanswered queries are forgotten after this; the app's resolver retries on its own. */
        private const val PENDING_MS = 5_000L
        /** Plain lookups waiting for their answer at once, each on its own socket and thread, and queued behind them. */
        private const val PLAIN_THREADS = 32
        private const val PLAIN_QUEUE = 512
        /** Largest UDP answer we accept: EDNS answers rarely exceed 1232 bytes, none exceed this. */
        private const val RESPONSE_MAX = 4096
        /** Upstream sockets that may fail to open in a row before the forwarder reports failure. */
        private const val MAX_REOPENS = 8

        /** Prefix of the failure reason when the encrypted resolver stopped answering; the host follows. */
        const val DOH_FAILED = "encrypted resolver not answering:"
        private const val DOH_THREADS = 4
        private const val DOH_QUEUE = 128
        private const val DOH_TIMEOUT_MS = 5_000
        /** The session ends after this long without an encrypted answer while at least [DOH_DEAD_FAILURES] lookups failed. */
        private const val DOH_DEAD_MS = 30_000L
        private const val DOH_DEAD_FAILURES = 12
    }
}
