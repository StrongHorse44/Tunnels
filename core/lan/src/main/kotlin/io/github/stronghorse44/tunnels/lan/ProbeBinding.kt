package io.github.stronghorse44.tunnels.lan

import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Pins a socket to the confirmed network (on Android `Network.bindSocket`); throws when it cannot. */
interface ProbeBinder {
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)
}

/**
 * Fail-closed socket binding for a scan. An unbound socket would send over the default network (cellular, a VPN,
 * another Wi-Fi that happens to share the prefix), so a probe may go out only after its socket was bound. The first
 * failed bind means the confirmed network is gone (Wi-Fi dropped or roamed mid-scan): the guard is then [lost] for
 * good and refuses every later bind, which aborts the rest of the scan. Refusals are counted in [skipped].
 * No binder at all (no known network) is the same as lost. Thread-safe. Plain JVM, unit-tested.
 */
class BindGuard(private val binder: ProbeBinder?) {
    private val lostFlag = AtomicBoolean(binder == null)
    private val skippedCount = AtomicInteger()

    /** True once a bind failed (or there was no network to bind to). */
    val lost: Boolean get() = lostFlag.get()

    /** Probes that were not sent because the network was lost. */
    val skipped: Int get() = skippedCount.get()

    /** True only when [socket] is now bound to the confirmed network and may connect. */
    fun bindTcp(socket: Socket): Boolean = attempt { it.bind(socket) }

    /** True only when [socket] is now bound to the confirmed network and may send. */
    fun bindUdp(socket: DatagramSocket): Boolean = attempt { it.bind(socket) }

    private fun attempt(block: (ProbeBinder) -> Unit): Boolean {
        val b = binder
        if (b == null || lostFlag.get()) {
            skippedCount.incrementAndGet()
            return false
        }
        return try {
            block(b)
            true
        } catch (_: Exception) {
            lostFlag.set(true)
            skippedCount.incrementAndGet()
            false
        }
    }
}

/**
 * Counts the distinct addresses a scan dropped for lying outside the confirmed network, without keeping them:
 * only a truncated SHA-256 digest of each is held in memory for the length of the scan, and never written
 * anywhere. Capped, so a hostile network cannot grow it without bound. Thread-safe.
 */
class DropCounter {
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val count: Int get() = seen.size

    fun add(address: java.net.InetAddress) = addKey(address.address)

    /** Anything that is not an IP literal still counts, once per distinct text. */
    fun add(text: String) {
        val address = LanScope.parseLiteral(text)
        addKey(address?.address ?: text.trim().toByteArray(Charsets.UTF_8))
    }

    private fun addKey(bytes: ByteArray) {
        if (seen.size >= MAX_TRACKED) return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        seen.add(digest.take(8).joinToString("") { "%02x".format(it) })
    }

    companion object {
        const val MAX_TRACKED = 512
    }
}
