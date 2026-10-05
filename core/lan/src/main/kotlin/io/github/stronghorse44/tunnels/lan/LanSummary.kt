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
    /** The census tokens, primary first; empty for the gateway and for a host with no identity. */
    val ids: List<String> = emptyList(),
    /**
     * Whether the host is on the network's list: null unless the census state is `set` (and always null for the
     * gateway, which is never judged), true when any of its tokens is listed, false otherwise, so a host with no
     * identity reads false.
     */
    val listed: Boolean? = null,
) {
    val title: String get() = name ?: vendor ?: ip

    /** The primary token, or null when the host has no identity (it cannot be added to the list). */
    val primaryId: String? get() = DeviceIdentity.primaryOf(ids)

    /** The name the census shows for the device in findings (see [DeviceCensus.title]). */
    val censusTitle: String get() = DeviceCensus.title(name, vendor)
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
    /** [DeviceCensus.STATE_SET], [DeviceCensus.STATE_UNSET] or [DeviceCensus.STATE_UNAVAILABLE]; null for a scan that predates the census. */
    val censusState: String? = null,
    /** Tokens on the list (0 unless the state is `set`); a device can hold up to six, see [listedCount]. */
    val knownCount: Int = 0,
    /** Non-gateway hosts not on the list; 0 unless the state is `set`. */
    val unknownCount: Int = 0,
    /** The list was full, so some acknowledgements were left out. */
    val full: Boolean = false,
    /** mDNS services that offered only link-local addresses (not probed). */
    val linkLocalOnly: Int = 0,
    /** In-scope hosts not recorded because the host cap was full. */
    val overCap: Int = 0,
) {
    val scanned: Boolean get() = gate == LanKeys.GATE_CONFIRMED

    /** Hosts other than the gateway: the devices the census can list. */
    val devicesSeen: Int get() = hosts.count { it.ip != router?.ip }

    /**
     * Devices seen in this scan that are on the list. [knownCount] counts tokens, and a device has up to six, so the
     * panel's "N on your list" is this number: the devices on the network now that the list accepts.
     */
    val listedCount: Int get() = hosts.count { it.listed == true }

    /** Hosts grouped by kind, in [HostKind] display order, empty kinds left out. */
    fun hostsByKind(): List<Pair<HostKind, List<LanHost>>> =
        HostKind.entries.mapNotNull { kind -> hosts.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it } }

    companion object {
        val EMPTY = LanSummary(emptyList(), null, 0, 0, null, null, null, null, emptyList())

        fun from(observations: List<Observation>): LanSummary {
            val obs = observations.filter { it.tunnelId == LanKeys.TUNNEL_ID }
            if (obs.isEmpty()) return EMPTY
            val bySubject = obs.groupBy { it.subject }
            val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
            val censusState = LanKeys.value(summary, LanKeys.CENSUS_STATE)?.takeIf {
                it == DeviceCensus.STATE_SET || it == DeviceCensus.STATE_UNSET || it == DeviceCensus.STATE_UNAVAILABLE
            }
            val known = if (censusState == DeviceCensus.STATE_SET) DeviceCensus.parseKnown(LanKeys.value(summary, LanKeys.CENSUS_KNOWN)) else null
            val gateway = LanKeys.value(bySubject[LanKeys.SUBJECT_ROUTER].orEmpty(), LanKeys.ROUTER_IP)
            val hosts = bySubject.filterKeys(LanKeys::isHostSubject).map { (ip, list) ->
                val ids = LanKeys.items(LanKeys.value(list, LanKeys.HOST_IDS)).filter(DeviceIdentity::isToken)
                LanHost(
                    ip = ip,
                    name = LanKeys.value(list, LanKeys.HOST_NAME),
                    kind = HostKind.byLabel(LanKeys.value(list, LanKeys.HOST_KIND)),
                    services = LanKeys.items(LanKeys.value(list, LanKeys.HOST_SERVICES)),
                    openPorts = LanKeys.ports(LanKeys.value(list, LanKeys.HOST_OPEN_PORTS)),
                    riskyPorts = LanKeys.ports(LanKeys.value(list, LanKeys.HOST_RISKY)),
                    upnp = LanKeys.value(list, LanKeys.HOST_UPNP) == LanKeys.TRUE,
                    vendor = LanKeys.value(list, LanKeys.HOST_VENDOR),
                    ids = ids,
                    listed = if (known == null || ip == gateway) null else DeviceCensus.isListed(ids, known),
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
                censusState = censusState,
                knownCount = known?.size ?: 0,
                unknownCount = if (known == null) 0 else LanKeys.value(summary, LanKeys.CENSUS_UNKNOWN)?.toIntOrNull()
                    ?: hosts.count { it.listed == false },
                full = LanKeys.value(summary, LanKeys.CENSUS_FULL) == LanKeys.TRUE,
                linkLocalOnly = LanKeys.value(summary, LanKeys.SCAN_LINK_LOCAL_ONLY)?.toIntOrNull() ?: 0,
                overCap = LanKeys.value(summary, LanKeys.SCAN_OVER_CAP)?.toIntOrNull() ?: 0,
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
