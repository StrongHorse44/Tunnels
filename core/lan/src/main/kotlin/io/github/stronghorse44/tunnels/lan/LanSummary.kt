package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation

/** One host as the UI shows it, rebuilt from its observations. */
data class LanHost(
    val ip: String,
    val name: String?,
    val kind: HostKind,
    val services: List<String>,
    val openPorts: List<Int>,
    val riskyPorts: List<Int>,
    val upnp: Boolean,
    val vendor: String?,
) {
    val title: String get() = name ?: vendor ?: ip
}

/** The router card's facts. Values are "true"/"false"/"unknown" strings as observed. */
data class RouterInfo(
    val ip: String?,
    val name: String?,
    val upnpIgd: String,
    val dnsHijack: String,
    val dnsIsGateway: String,
    /** "true" when the resolver is on the LAN, "false" when it is outside (hijack probe skipped), else "unknown". The probe
     * itself runs only for a resolver inside the confirmed network's prefix (see LanScope). */
    val dnsLocal: String,
    val privateDns: String,
    /** Null when the router was never port-scanned (it lay outside the confirmed network); not the same as none open. */
    val openPorts: List<Int>?,
)

/** Everything the home_network panel renders, derived from the latest scan's observations. */
data class LanSummary(
    val hosts: List<LanHost>,
    val router: RouterInfo?,
    val totalHosts: Int,
    val riskyHosts: Int,
    /** First hex chars of the network fingerprint hash (see [NetworkFingerprint]), never an SSID. */
    val networkTag: String?,
    val durationSec: Int?,
    val gate: String?,
    val gateReason: String?,
    val partialStages: List<String>,
    /** Distinct discovered addresses ignored because they lay outside the confirmed network. */
    val droppedOutOfScope: Int = 0,
) {
    val scanned: Boolean get() = gate == LanKeys.GATE_CONFIRMED

    /** Hosts grouped by kind, in [HostKind] display order, empty kinds left out. */
    fun hostsByKind(): List<Pair<HostKind, List<LanHost>>> =
        HostKind.entries.mapNotNull { kind -> hosts.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it } }

    companion object {
        val EMPTY = LanSummary(emptyList(), null, 0, 0, null, null, null, null, emptyList())

        fun from(observations: List<Observation>): LanSummary {
            val obs = observations.filter { it.tunnelId == LanKeys.TUNNEL_ID }
            if (obs.isEmpty()) return EMPTY
            val bySubject = obs.groupBy { it.subject }
            val hosts = bySubject.filterKeys(LanKeys::isHostSubject).map { (ip, list) ->
                LanHost(
                    ip = ip,
                    name = LanKeys.value(list, LanKeys.HOST_NAME),
                    kind = HostKind.byLabel(LanKeys.value(list, LanKeys.HOST_KIND)),
                    services = LanKeys.items(LanKeys.value(list, LanKeys.HOST_SERVICES)),
                    openPorts = LanKeys.ports(LanKeys.value(list, LanKeys.HOST_OPEN_PORTS)),
                    riskyPorts = LanKeys.ports(LanKeys.value(list, LanKeys.HOST_RISKY)),
                    upnp = LanKeys.value(list, LanKeys.HOST_UPNP) == LanKeys.TRUE,
                    vendor = LanKeys.value(list, LanKeys.HOST_VENDOR),
                )
            }.sortedWith(compareBy<LanHost> { it.kind.ordinal }.thenBy { ipSortKey(it.ip) })
            val router = bySubject[LanKeys.SUBJECT_ROUTER]?.let { r ->
                RouterInfo(
                    ip = LanKeys.value(r, LanKeys.ROUTER_IP),
                    name = LanKeys.value(r, LanKeys.ROUTER_NAME),
                    upnpIgd = LanKeys.value(r, LanKeys.ROUTER_UPNP_IGD) ?: LanKeys.UNKNOWN,
                    dnsHijack = LanKeys.value(r, LanKeys.ROUTER_DNS_HIJACK) ?: LanKeys.UNKNOWN,
                    dnsIsGateway = LanKeys.value(r, LanKeys.ROUTER_DNS_IS_GATEWAY) ?: LanKeys.UNKNOWN,
                    dnsLocal = LanKeys.value(r, LanKeys.ROUTER_DNS_LOCAL) ?: LanKeys.UNKNOWN,
                    privateDns = LanKeys.value(r, LanKeys.ROUTER_PRIVATE_DNS) ?: LanKeys.UNKNOWN,
                    openPorts = LanKeys.value(r, LanKeys.ROUTER_OPEN_PORTS)?.let(LanKeys::ports),
                )
            }
            val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
            return LanSummary(
                hosts = hosts,
                router = router,
                totalHosts = LanKeys.value(summary, LanKeys.HOSTS_TOTAL)?.toIntOrNull() ?: hosts.size,
                riskyHosts = LanKeys.value(summary, LanKeys.HOSTS_RISKY)?.toIntOrNull() ?: hosts.count { it.riskyPorts.isNotEmpty() },
                networkTag = LanKeys.value(summary, LanKeys.SCAN_NETWORK),
                durationSec = LanKeys.value(summary, LanKeys.SCAN_DURATION)?.toIntOrNull(),
                gate = LanKeys.value(summary, LanKeys.SCAN_GATE),
                gateReason = LanKeys.value(summary, LanKeys.SCAN_GATE_REASON),
                partialStages = LanKeys.items(LanKeys.value(summary, LanKeys.SCAN_PARTIAL)),
                droppedOutOfScope = LanKeys.value(summary, LanKeys.SCAN_DROPPED_OUT_OF_SCOPE)?.toIntOrNull() ?: 0,
            )
        }

        /** Numeric order for dotted IPv4, text order otherwise. */
        fun ipSortKey(ip: String): String {
            val parts = ip.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return "~$ip"
            return parts.joinToString(".") { it.padStart(3, '0') }
        }
    }
}
