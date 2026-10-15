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
     * IPv4 first, then global IPv6, then link-local IPv6 that carry their scope (LinkProperties hands them out with the
     * interface's scope id). A link-local address without a scope cannot be sent to and is dropped; no interface lookup
     * is made to give it one (that would add a JDK network-interface type the release gate does not list).
     */
    fun select(servers: List<InetAddress>): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        servers.filterIsInstance<Inet4Address>().filterNot { it.isAnyLocalAddress }.forEach(out::add)
        servers.filterIsInstance<Inet6Address>().filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }.forEach(out::add)
        servers.filterIsInstance<Inet6Address>().filter { it.isLinkLocalAddress && it.scopeId != 0 }.forEach(out::add)
        return out.distinct()
    }

    /**
     * What a running session does after the resolver list it follows came back empty (review Q2): it keeps going and
     * drops plain lookups (nothing is sent anywhere) for [GRACE_MS], and ends only if no resolver appears by then or by
     * the next link-properties change that is still empty. A non-empty list ends the episode. A session never starts
     * without a resolver: that check is separate and has no grace.
     */
    object Grace {
        const val GRACE_MS = 15_000L

        /**
         * One run of updates with an empty list on one network ([network], its handle): since when, and how many DNS
         * changes on that same network came after it began and still named no resolver.
         */
        data class Episode(val since: Long, val network: Long, val laterDnsChanges: Int = 0)

        /**
         * The episode after an update on [network] whose list is [empty]; null once a resolver is back. Only a change of
         * the same network's DNS settings ([dnsChange]: servers, private DNS, search domains or interface) that still names
         * none can end the grace early; an unrelated change (an address added, a route) cannot. Another network starts a
         * new episode.
         */
        fun update(episode: Episode?, empty: Boolean, network: Long, dnsChange: Boolean, now: Long): Episode? = when {
            !empty -> null
            episode == null || episode.network != network -> Episode(now, network)
            dnsChange -> episode.copy(laterDnsChanges = episode.laterDnsChanges + 1)
            else -> episode
        }

        fun shouldEnd(episode: Episode?, now: Long): Boolean =
            episode != null && (episode.laterDnsChanges > 0 || now - episode.since >= GRACE_MS)
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
