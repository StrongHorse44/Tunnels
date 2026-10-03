package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.github.stronghorse44.tunnels.lan.LanAddresses
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The Wi-Fi the phone is on right now, as far as the gate and the scanner need to know. */
data class WifiState(
    /** The non-VPN Wi-Fi network, or null when the phone is not on Wi-Fi. */
    val network: Network?,
    val linkProperties: LinkProperties?,
    /** DHCP server from LinkProperties, else WifiManager's DhcpInfo; may be null. */
    val dhcpServer: InetAddress? = null,
    /** Gateway from DhcpInfo when the routes name none (rare). */
    val dhcpGateway: Inet4Address? = null,
    /**
     * Display only. Android redacts it for apps without location permission (see [NetworkFingerprint]); the
     * gate never depends on it.
     */
    val ssid: String? = null,
) {
    val onWifi: Boolean get() = network != null

    /** The IPv4 default gateway from the routes, else from DHCP, if any. */
    val gateway: Inet4Address?
        get() = linkProperties?.routes?.firstOrNull { it.hasGateway() && it.destination.prefixLength == 0 && it.gateway is Inet4Address }
            ?.gateway as? Inet4Address ?: dhcpGateway

    /** The IPv6 default gateway (the router's link-local address), used only when there is no IPv4 one. */
    val gateway6: Inet6Address?
        get() = linkProperties?.routes?.firstOrNull { it.hasGateway() && it.destination.prefixLength == 0 && it.gateway is Inet6Address }
            ?.gateway as? Inet6Address

    val dnsServers: List<InetAddress> get() = linkProperties?.dnsServers.orEmpty()

    val ownAddresses: Set<String> get() = linkProperties?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty().toSet()

    /**
     * The phone's own link addresses with their prefix lengths. These define the confirmed network: every
     * discovered address must lie inside one of them before the scanner probes it (see LanScope), and the
     * addresses themselves are the phone's own, which are never probed.
     */
    val prefixes: List<Pair<InetAddress, Int>>
        get() = linkProperties?.linkAddresses?.mapNotNull { la -> la.address?.let { it to la.prefixLength } }.orEmpty()

    /** The IPv4 network of the phone's own address, else its first non-link-local IPv6 one. */
    val prefixNetwork: String?
        get() {
            val las = linkProperties?.linkAddresses.orEmpty()
            val v4 = las.firstOrNull { it.address is Inet4Address }
            if (v4 != null) return LanAddresses.network(v4.address, v4.prefixLength)
            val v6 = las.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }
            return v6?.let { LanAddresses.network(it.address, it.prefixLength) }
        }

    /** What the gate confirms and the scan tags; null when the network cannot be told apart from another. */
    val fingerprint: NetworkFingerprint?
        get() = if (!onWifi) null else NetworkFingerprint.of(
            gateway = (gateway ?: gateway6)?.hostAddress?.substringBefore('%'),
            dhcpServer = dhcpServer?.hostAddress?.substringBefore('%'),
            dnsServers = dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
            prefix = prefixNetwork,
            ssid = ssid,
        )

    companion object {
        val OFFLINE = WifiState(null, null)
    }
}

/** The gate's verdict before a scan. */
sealed interface GateDecision {
    data class Allowed(val state: WifiState, val fingerprint: NetworkFingerprint) : GateDecision

    /** [reason] is one of the LanKeys.REASON_* values. */
    data class Refused(val reason: String, val state: WifiState) : GateDecision
}

/**
 * Own-network gate: Tunnels scans only a Wi-Fi the user confirmed as theirs, once per network. A network is
 * identified by its [NetworkFingerprint] (gateway, DHCP server, DNS servers, prefix), not by its SSID, which
 * Android hides from apps without a location permission. Confirmed fingerprints are kept as SHA-256 hashes
 * in this module's own preferences (not sensitive, not observations).
 */
class NetworkGate(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Reads the current Wi-Fi network; cheap binder calls, safe from the main thread. */
    fun current(): WifiState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return WifiState.OFFLINE
        val network = wifiNetwork(cm) ?: return WifiState.OFFLINE
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val dhcp = runCatching { dhcpInfo() }.getOrNull()
        val dhcpServer = lp?.dhcpServerAddress ?: dhcp?.serverAddress?.takeIf { it != 0 }?.let(::ipv4)
        val dhcpGateway = dhcp?.gateway?.takeIf { it != 0 }?.let(::ipv4)
        val ssid = displaySsid(cm, network)
        return WifiState(network, lp, dhcpServer, dhcpGateway, ssid)
    }

    /** WifiManager's DHCP lease (ACCESS_WIFI_STATE only; no location fields). */
    @Suppress("DEPRECATION")
    private fun dhcpInfo() = context.getSystemService(WifiManager::class.java)?.dhcpInfo

    /** DhcpInfo packs IPv4 addresses little-endian. */
    private fun ipv4(packed: Int): Inet4Address? = runCatching {
        InetAddress.getByAddress(byteArrayOf(packed.toByte(), (packed shr 8).toByte(), (packed shr 16).toByte(), (packed shr 24).toByte())) as? Inet4Address
    }.getOrNull()

    /**
     * Best-effort SSID for the card only. Expected to be redacted ("<unknown ssid>") on API 31+ without
     * ACCESS_FINE_LOCATION (NEARBY_WIFI_DEVICES with neverForLocation would not change that, so the tunnel asks
     * for neither); if a device does share it, the card shows it next to the fingerprint.
     */
    @Suppress("DEPRECATION")
    private fun displaySsid(cm: ConnectivityManager, network: Network): String? {
        val fromCaps = runCatching { (cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo)?.ssid }.getOrNull()
        val fromManager = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        return GateHashing.normalizeSsid(fromCaps) ?: GateHashing.normalizeSsid(fromManager)
    }

    /** The active network when it is plain Wi-Fi, else the first Wi-Fi network that is not a VPN. */
    private fun wifiNetwork(cm: ConnectivityManager): Network? {
        fun isPlainWifi(n: Network): Boolean {
            val c = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
            return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.takeIf(::isPlainWifi)?.let { return it }
        return allNetworks(cm).firstOrNull(::isPlainWifi)
    }

    /** Needed when a VPN is the active network: the Wi-Fi underneath is still listed here. */
    @Suppress("DEPRECATION")
    private fun allNetworks(cm: ConnectivityManager): List<Network> = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())

    fun isConfirmed(fingerprint: NetworkFingerprint): Boolean = fingerprint.hash in confirmedHashes()

    fun confirm(fingerprint: NetworkFingerprint) {
        prefs.edit().putStringSet(KEY_CONFIRMED, confirmedHashes() + fingerprint.hash).apply()
    }

    fun forget(fingerprint: NetworkFingerprint) {
        prefs.edit().putStringSet(KEY_CONFIRMED, confirmedHashes() - fingerprint.hash).apply()
    }

    fun forgetAll() = prefs.edit().remove(KEY_CONFIRMED).apply()

    fun confirmedCount(): Int = confirmedHashes().size

    private fun confirmedHashes(): Set<String> = prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty().toSet()

    /** Decides whether a scan may run right now. Never throws. */
    fun check(): GateDecision {
        val state = runCatching { current() }.getOrDefault(WifiState.OFFLINE)
        if (!state.onWifi) return GateDecision.Refused(LanKeys.REASON_NO_WIFI, state)
        val fingerprint = state.fingerprint ?: return GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, state)
        // Scanning stays inside the link's prefixes; without any, there is nothing to stay inside.
        if (state.prefixes.isEmpty()) return GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, state)
        if (!isConfirmed(fingerprint)) return GateDecision.Refused(LanKeys.REASON_NOT_CONFIRMED, state)
        return GateDecision.Allowed(state, fingerprint)
    }

    companion object {
        const val PREFS = "homenet_gate"
        /** Fingerprint hashes. The first draft stored SSID hashes under another key; those are simply ignored. */
        const val KEY_CONFIRMED = "confirmed_network_hashes"
    }
}
