package io.github.stronghorse44.tunnels.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which upstream replies a Traffic session passes back to an app (audit M4). A reply is accepted only when it comes from
 * the resolver the query was sent to, from port 53, and answers that query (same id and question name, as before). The
 * forwarder also sends each query from its own connected socket on a port the system picks, so a device on the same
 * Wi-Fi can neither aim at one fixed port nor have a reply from another address taken.
 */
object ReplyGate {
    fun accepts(resolver: InetAddress, sender: InetAddress?, senderPort: Int, query: DnsMessage, reply: DnsMessage): Boolean {
        if (sender == null || senderPort != DnsMessage.PORT) return false
        if (!sameAddress(resolver, sender)) return false
        return reply.isResponse && reply.id == query.id && reply.queryName == query.queryName
    }

    /** Same address, an IPv4 resolver matching its IPv4-mapped form; two scoped IPv6 addresses must share the scope. */
    fun sameAddress(a: InetAddress, b: InetAddress): Boolean {
        if (!canonical(a.address).contentEquals(canonical(b.address))) return false
        if (a is Inet6Address && b is Inet6Address && a.scopeId != 0 && b.scopeId != 0 && a.scopeId != b.scopeId) return false
        return true
    }

    private fun canonical(bytes: ByteArray): ByteArray {
        if (bytes.size != 16) return bytes
        val mapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return if (mapped) bytes.copyOfRange(12, 16) else bytes
    }
}

/**
 * The upstream resolvers a session uses, from the network's DNS servers (audit L3). There is no public fallback: with
 * none usable, the session does not start (or ends) with [NONE], and nothing goes to a resolver the network never named.
 */
object Resolvers {
    /** The status reason when [select] finds nothing; the caller adds whether the session did not start or ended. */
    const val NONE = "No DNS resolver on this network."

    /**
     * IPv4 first, then global IPv6, then link-local IPv6 with their scope: kept when they already carry one, else given
     * one by [scope] (the network's interface), else dropped, since a link-local address without a scope cannot be sent to.
     */
    fun select(servers: List<InetAddress>, scope: (Inet6Address) -> Inet6Address? = { null }): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        servers.filterIsInstance<Inet4Address>().filterNot { it.isAnyLocalAddress }.forEach(out::add)
        servers.filterIsInstance<Inet6Address>().filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }.forEach(out::add)
        for (v6 in servers.filterIsInstance<Inet6Address>().filter { it.isLinkLocalAddress }) {
            val scoped = if (v6.scopeId != 0 || v6.scopedInterface != null) v6 else runCatching { scope(v6) }.getOrNull()
            if (scoped != null && (scoped.scopeId != 0 || scoped.scopedInterface != null)) out += scoped
        }
        return out.distinct()
    }
}

/**
 * Whether another VPN may be up (audit L4). It fails closed: no connectivity service, or any error reading the networks,
 * counts as "a VPN is up", so Traffic refuses to start instead of replacing a VPN it could not see.
 */
object VpnPresence {
    /** [vpnTransports] gives, per network, whether it has the VPN transport; null when there is no connectivity service. */
    fun anyUp(vpnTransports: () -> List<Boolean>?): Boolean = try {
        vpnTransports()?.any { it } ?: true
    } catch (_: Exception) {
        true
    }
}
