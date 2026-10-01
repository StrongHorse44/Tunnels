package io.github.stronghorse44.tunnels.traffic

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.stronghorse44.tunnels.dns.DnsMessage
import io.github.stronghorse44.tunnels.dns.IpPacket
import io.github.stronghorse44.tunnels.dns.IpPackets
import io.github.stronghorse44.tunnels.dns.SessionCounter
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The packet loop of a session. Reads IP packets from the TUN, keeps the UDP port-53 ones, counts each
 * query against the app that owns the socket, forwards it through one protected upstream socket and
 * writes the answer back with fresh IP/UDP headers. Everything else is dropped (TCP 853 is counted as
 * encrypted DNS first). Two plain threads; nothing here touches the UI or the store.
 */
class DnsForwarder(
    private val tun: ParcelFileDescriptor,
    private val counter: SessionCounter,
    private val resolvers: List<InetAddress>,
    /** `VpnService.protect` and `Network.bindSocket`, so our own packets never re-enter the tunnel. */
    private val prepareSocket: (DatagramSocket) -> Unit,
    /** `ConnectivityManager.getConnectionOwnerUid`, or -1. */
    private val ownerUid: (protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int) -> Int,
    private val subjectOf: (uid: Int) -> String,
) {
    private class Pending(val src: InetAddress, val dst: InetAddress, val srcPort: Int, val question: String?, val at: Long)

    private val output = FileOutputStream(tun.fileDescriptor)
    private val upstream = DatagramSocket()
    private val pending = LinkedHashMap<Int, Pending>()
    private val resolverIndex = AtomicInteger(0)
    @Volatile private var running = false

    val dropped = AtomicInteger(0)
    val answered = AtomicInteger(0)

    private val reader = Thread(::readLoop, "tunnels-dns-tun")
    private val responder = Thread(::responseLoop, "tunnels-dns-upstream")

    fun start() {
        running = true
        prepareSocket(upstream)
        upstream.soTimeout = 0
        reader.start()
        responder.start()
    }

    /** Stops both threads; returns once they have exited (bounded wait). */
    fun stop() {
        running = false
        runCatching { upstream.close() }
        runCatching { reader.join(1500) }
        runCatching { responder.join(1500) }
    }

    private fun readLoop() {
        val buffer = ByteArray(32767)
        val fds = arrayOf(StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() })
        while (running) {
            fds[0].revents = 0
            val ready = try {
                Os.poll(fds, POLL_MS)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else break
            }
            if (ready <= 0) continue
            if (fds[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
            val length = try {
                Os.read(tun.fileDescriptor, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue else break
            } catch (_: InterruptedIOException) {
                continue
            } catch (_: IOException) {
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
    }

    private fun handle(buffer: ByteArray, length: Int) {
        when (val packet = IpPackets.parse(buffer, length)) {
            is IpPacket.Udp -> if (packet.dstPort == DnsMessage.PORT) query(packet) else dropped.incrementAndGet()
            is IpPacket.Other -> {
                if (packet.protocol == IpPackets.PROTO_TCP && packet.dstPort == DnsMessage.PORT_TLS) {
                    // Encrypted DNS attempt: count it against the app, nothing else can be read.
                    counter.encrypted(subjectOf(-1))
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
        message.queryName?.let { counter.query(subject, it) }

        synchronized(pending) {
            val now = System.currentTimeMillis()
            val stale = pending.entries.filter { now - it.value.at > PENDING_MS }.map { it.key }
            stale.forEach(pending::remove)
            while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[message.id] = Pending(p.src, p.dst, p.srcPort, message.queryName, now)
        }
        val resolver = resolvers[resolverIndex.get() % resolvers.size]
        try {
            upstream.send(DatagramPacket(p.payload, p.payload.size, resolver, DnsMessage.PORT))
        } catch (e: IOException) {
            // Try the next resolver for the following query; this one is lost (the app retries).
            resolverIndex.incrementAndGet()
            synchronized(pending) { pending.remove(message.id) }
            dropped.incrementAndGet()
            Log.w(TAG, "upstream send failed: ${e.javaClass.simpleName}")
        }
    }

    private fun responseLoop() {
        val buffer = ByteArray(RESPONSE_MAX)
        while (running) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                upstream.receive(datagram)
            } catch (_: SocketException) {
                break // closed by stop()
            } catch (_: IOException) {
                continue
            }
            val message = DnsMessage.parseOrNull(buffer, 0, datagram.length) ?: continue
            if (!message.isResponse) continue
            val match = synchronized(pending) { pending.remove(message.id) } ?: continue
            // A reply to a different question under a reused id is not ours to deliver.
            if (match.question != null && message.queryName != null && match.question != message.queryName) continue
            val payload = buffer.copyOf(datagram.length)
            try {
                val packet = IpPackets.buildUdp(match.dst, match.src, DnsMessage.PORT, match.srcPort, payload)
                synchronized(output) { output.write(packet) }
                answered.incrementAndGet()
            } catch (e: Exception) {
                dropped.incrementAndGet()
                Log.w(TAG, "reply dropped: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "TunnelsDns"
        private const val POLL_MS = 500
        /** Unanswered queries are forgotten after this; the app's resolver retries on its own. */
        private const val PENDING_MS = 5_000L
        private const val MAX_PENDING = 512
        /** Largest UDP answer we accept: EDNS answers rarely exceed 1232 bytes, none exceed this. */
        private const val RESPONSE_MAX = 4096
    }
}
