package io.github.stronghorse44.tunnels.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

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

    private val uuids = TreeSet<String>()

    /**
     * The device UUIDs from SSDP USN headers ([Ssdp.uuidOf]), sorted, at most [MAX_UUIDS]. A UPnP device answers
     * for its root and for every embedded device, each with its own UUID, so when more arrive the two smallest are
     * kept: the same pair on every scan whatever order the replies came in, which keeps the primary token stable.
     */
    val ssdpUuids: List<String> get() = synchronized(uuids) { uuids.toList() }

    fun addUuid(uuid: String) {
        synchronized(uuids) {
            uuids.add(uuid)
            while (uuids.size > MAX_UUIDS) uuids.pollLast()
        }
    }

    companion object {
        const val MAX_UUIDS = 2
    }
}

/**
 * The scanner's host table. Every address passes [LanScope] before it gets a record; at most [maxHosts] hosts are
 * kept besides the gateway, which [reserve] puts in first (before any discovery) so a crowded network can never
 * crowd it out. What the cap or the scope turned away is only counted ([overCap], [dropped]), never kept.
 * Thread-safe.
 */
class ScanHosts(
    private val prefixes: List<Pair<InetAddress, Int>>,
    private val own: List<InetAddress>,
    private val maxHosts: Int = DEFAULT_MAX_HOSTS,
) {
    private val table = LinkedHashMap<String, LanHostRecord>()
    private var reserved: String? = null

    /** Distinct discovered addresses outside the confirmed network (or not addresses at all). */
    val dropped = DropCounter()
    private val capped = DropCounter()

    /** The records so far, gateway included. */
    val hosts: Map<String, LanHostRecord> get() = synchronized(table) { LinkedHashMap(table) }

    /** Distinct in-scope addresses refused because the cap was full. */
    val overCap: Int get() = capped.count

    /** Records the gateway outside the cap. Call it before discovery. Null for no gateway or one outside the network. */
    fun reserve(gateway: String?): LanHostRecord? {
        if (gateway == null) return null
        admit(gateway) ?: return null
        return synchronized(table) {
            reserved = gateway
            table.getOrPut(gateway) { LanHostRecord(gateway) }
        }
    }

    /** The record for [ip], created if the address is inside the network and the cap has room; else null. */
    fun record(ip: String): LanHostRecord? {
        val address = admit(ip) ?: return null
        synchronized(table) {
            table[ip]?.let { return it }
            val counted = table.size - if (reserved != null && table.containsKey(reserved)) 1 else 0
            if (counted >= maxHosts) {
                capped.add(address)
                return null
            }
            return LanHostRecord(ip).also { table[ip] = it }
        }
    }

    /** The parsed address when it may be recorded; otherwise counts a drop (never the phone's own address) and returns null. */
    private fun admit(ip: String): InetAddress? {
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
        return address
    }

    companion object {
        const val DEFAULT_MAX_HOSTS = 50
    }
}

/** What to do with the addresses one resolved mDNS service offered. */
sealed interface MdnsPick {
    /** Record and probe this address. */
    data class Use(val address: String) : MdnsPick

    /** Every offered address was link-local and none was usable: nothing to record, and not "outside this network". */
    data object LinkLocalOnly : MdnsPick

    /** The phone's own service (its own addresses, with or without its link-local ones): not a host, counted nowhere. */
    data object Own : MdnsPick

    /** Nothing offered lies inside the network; [first] is the address to count as dropped (null when none was offered). */
    data class OutOfScope(val first: InetAddress?) : MdnsPick
}

object MdnsAddress {
    /**
     * Prefers an address inside the confirmed network ([inScope]): IPv4 first, then a non-link-local IPv6 one; never
     * loopback or wildcard. With none usable the answer says why: [MdnsPick.LinkLocalOnly] when every offered address
     * is link-local (IPv6 fe80::/10, IPv4 169.254/16), else [MdnsPick.OutOfScope]. A service whose addresses are the
     * phone's own ([isOwn], link-local ones aside) is [MdnsPick.Own].
     */
    fun pick(all: List<InetAddress>, inScope: (InetAddress) -> Boolean, isOwn: (InetAddress) -> Boolean = { false }): MdnsPick {
        val inside = all.filter(inScope)
        val v4 = inside.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (v4 != null) return MdnsPick.Use(v4.hostAddress.orEmpty())
        val v6 = inside.filterIsInstance<Inet6Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        if (v6 != null) return MdnsPick.Use(v6.hostAddress.orEmpty().substringBefore('%'))
        if (all.any(isOwn) && all.all { isOwn(it) || it.isLinkLocalAddress }) return MdnsPick.Own
        if (all.isNotEmpty() && all.all { it.isLinkLocalAddress }) return MdnsPick.LinkLocalOnly
        return MdnsPick.OutOfScope(all.firstOrNull { !it.isLinkLocalAddress && !isOwn(it) } ?: all.firstOrNull { !it.isLinkLocalAddress } ?: all.firstOrNull())
    }
}
