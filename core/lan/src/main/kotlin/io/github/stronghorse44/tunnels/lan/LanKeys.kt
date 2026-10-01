package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation key schema of the home_network tunnel. Subjects are host IPs (local addresses, as
 * summaries), plus [SUBJECT_ROUTER] for the gateway checks and [SUBJECT_SUMMARY] for scan totals.
 */
object LanKeys {
    const val TUNNEL_ID = "home_network"

    const val SUBJECT_ROUTER = "router"
    const val SUBJECT_SUMMARY = "summary"

    /** mDNS instance name or SSDP friendly name, trimmed; absent when nothing named the host. */
    const val HOST_NAME = "host:name"
    /** One of [HostKind]'s labels. Always present for a host. */
    const val HOST_KIND = "host:kind"
    /** Comma list of advertised services (short mDNS type names, SSDP device/service types), or "none". */
    const val HOST_SERVICES = "host:services"
    /** Comma list of open TCP ports from the curated catalog, or "none". */
    const val HOST_OPEN_PORTS = "host:openPorts"
    /** Comma list of the open ports the catalog marks risky, or "none". */
    const val HOST_RISKY = "host:risky"
    /** "true" when the host answered SSDP (UPnP). */
    const val HOST_UPNP = "host:upnp"
    /** Vendor guessed from mDNS/SSDP strings; absent when unknown. */
    const val HOST_VENDOR = "host:vendor"

    const val ROUTER_IP = "router:ip"
    /** "true", "false" or "unknown": the gateway advertises a WAN(IP|PPP)Connection UPnP service. */
    const val ROUTER_UPNP_IGD = "router:upnpIgd"
    /** "true", "false" or "unknown": the gateway resolver answered an A record for a name that cannot exist. */
    const val ROUTER_DNS_HIJACK = "router:dnsHijack"
    const val ROUTER_DNS_IS_GATEWAY = "router:dnsIsGateway"
    /**
     * "true" when the resolver the phone was handed is on the local network (the gateway or another LAN
     * address), "false" when it is a server outside the network (the hijack probe is then skipped so the
     * phone never sends DNS beyond the LAN), "unknown" when no resolver was configured.
     */
    const val ROUTER_DNS_LOCAL = "router:dnsLocal"
    const val ROUTER_PRIVATE_DNS = "router:privateDns"
    const val ROUTER_OPEN_PORTS = "router:openPorts"
    /** Friendly name / manufacturer / model from the UPnP description, when fetched. */
    const val ROUTER_NAME = "router:name"

    const val HOSTS_TOTAL = "hosts:total"
    const val HOSTS_RISKY = "hosts:risky"
    /**
     * First 8 hex chars of the SHA-256 of the network fingerprint (gateway, DHCP server, DNS servers,
     * IPv4 prefix: see [NetworkFingerprint]), never the SSID or any address itself.
     */
    const val SCAN_NETWORK = "scan:network"
    const val SCAN_DURATION = "scan:durationSec"
    /** [GATE_CONFIRMED] when the own-network gate let the scan run, else [GATE_UNCONFIRMED]. */
    const val SCAN_GATE = "scan:gate"
    /** Why the gate refused: "no-wifi", "network-unknown", "not-confirmed", "no-permission". */
    const val SCAN_GATE_REASON = "scan:gateReason"
    /** Comma list of stages that hit their time budget and returned partial results. */
    const val SCAN_PARTIAL = "scan:partial"

    const val GATE_CONFIRMED = "confirmed"
    const val GATE_UNCONFIRMED = "unconfirmed"
    const val REASON_NO_WIFI = "no-wifi"
    /** On Wi-Fi, but neither a gateway nor an address prefix was readable, so the network cannot be told apart. */
    const val REASON_NETWORK_UNKNOWN = "network-unknown"
    const val REASON_NOT_CONFIRMED = "not-confirmed"
    const val REASON_NO_PERMISSION = "no-permission"

    const val TRUE = "true"
    const val FALSE = "false"
    const val UNKNOWN = "unknown"
    const val NONE = "none"

    /** Hosts are every subject that is not the router summary or the scan summary. */
    fun isHostSubject(subject: String) = subject != SUBJECT_ROUTER && subject != SUBJECT_SUMMARY

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    /** "22,80,443" -> [22, 80, 443]; "none" and blanks -> empty. */
    fun ports(value: String?): List<Int> =
        value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct().sorted()

    fun portList(ports: Collection<Int>): String = if (ports.isEmpty()) NONE else ports.distinct().sorted().joinToString(",")

    fun list(items: Collection<String>): String = if (items.isEmpty()) NONE else items.distinct().joinToString(",")

    fun items(value: String?): List<String> =
        value.orEmpty().takeIf { it != NONE }.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
