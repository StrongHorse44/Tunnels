package io.github.stronghorse44.tunnels.lan

import java.security.MessageDigest

/**
 * What the own-network gate uses to tell one Wi-Fi from another. Android redacts the SSID and BSSID from apps
 * that hold no location permission (on API 31+ `WifiInfo` arrives with `<unknown ssid>` unless a
 * `NetworkCallback` is registered with `FLAG_INCLUDE_LOCATION_INFO` *and* the app holds
 * `ACCESS_FINE_LOCATION`; `NEARBY_WIFI_DEVICES` with `neverForLocation` does not unlock it). Tunnels asks
 * for no location permission, so the gate keys on what every app may read from `LinkProperties` and
 * `DhcpInfo`: the default gateway, the DHCP server, the DNS servers and the address prefix. Only the SHA-256
 * of the canonical form is stored; the observation keeps its first 8 hex chars.
 *
 * Limit, stated to the user in the UI: two networks with the same router address and DNS setup look the same.
 */
data class NetworkFingerprint(
    /** IPv4 default gateway, else the IPv6 one. */
    val gateway: String?,
    val dhcpServer: String?,
    /** As configured by DHCP/RA, sorted. */
    val dnsServers: List<String>,
    /** "192.168.1.0/24" style network of the phone's own address. */
    val prefix: String?,
    /** Shown only; never part of the hash, because it is usually redacted. */
    val ssid: String? = null,
) {
    /** The string that is hashed: stable key order, no spaces. */
    val canonical: String
        get() = "gw=${gateway.orEmpty()};dhcp=${dhcpServer.orEmpty()};dns=${dnsServers.sorted().joinToString(",")};net=${prefix.orEmpty()}"

    val hash: String get() = sha256Hex("net:$canonical")

    /** The short, non-reversible tag kept in observations. */
    val prefixTag: String get() = hash.take(PREFIX_LENGTH)

    /** One line for the network card: "router 192.168.1.1 · 192.168.1.0/24 · DNS 192.168.1.1". */
    val label: String
        get() = buildList {
            gateway?.let { add("router $it") }
            prefix?.let { add(it) }
            if (dnsServers.isNotEmpty()) add("DNS " + dnsServers.sorted().take(3).joinToString(", "))
            dhcpServer?.takeIf { it != gateway }?.let { add("DHCP $it") }
        }.joinToString(" · ")

    companion object {
        const val PREFIX_LENGTH = 8

        /** Null when neither a gateway nor a prefix is known: nothing tells this network apart from another. */
        fun of(gateway: String?, dhcpServer: String?, dnsServers: List<String>, prefix: String?, ssid: String? = null): NetworkFingerprint? {
            if (gateway.isNullOrBlank() && prefix.isNullOrBlank()) return null
            return NetworkFingerprint(
                gateway = gateway?.takeIf { it.isNotBlank() },
                dhcpServer = dhcpServer?.takeIf { it.isNotBlank() },
                dnsServers = dnsServers.filter { it.isNotBlank() }.distinct().sorted(),
                prefix = prefix?.takeIf { it.isNotBlank() },
                ssid = ssid?.takeIf { it.isNotBlank() },
            )
        }

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
