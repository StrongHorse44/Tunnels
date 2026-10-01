package io.github.stronghorse44.tunnels.homenet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import io.github.stronghorse44.tunnels.lan.LanKeys
import java.net.Inet4Address
import java.net.InetAddress

/** The Wi-Fi the phone is on right now, as far as the gate and the scanner need to know. */
data class WifiState(
    /** The non-VPN Wi-Fi network, or null when the phone is not on Wi-Fi. */
    val network: Network?,
    val ssid: String?,
    val linkProperties: LinkProperties?,
) {
    val onWifi: Boolean get() = network != null
    val ssidHash: String? get() = ssid?.let(GateHashing::ssidHash)

    /** The IPv4 default gateway from the routes, if any. */
    val gateway: Inet4Address?
        get() = linkProperties?.routes?.firstOrNull { it.hasGateway() && it.destination.prefixLength == 0 && it.gateway is Inet4Address }
            ?.gateway as? Inet4Address

    val dnsServers: List<InetAddress> get() = linkProperties?.dnsServers.orEmpty()

    val ownAddresses: Set<String> get() = linkProperties?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty().toSet()

    companion object {
        val OFFLINE = WifiState(null, null, null)
    }
}

/** The gate's verdict before a scan. */
sealed interface GateDecision {
    data class Allowed(val state: WifiState, val ssid: String, val hash: String) : GateDecision

    /** [reason] is one of the LanKeys.REASON_* values. */
    data class Refused(val reason: String, val state: WifiState) : GateDecision
}

/**
 * Own-network gate: Tunnels scans only a Wi-Fi the user confirmed as theirs, once per SSID. Confirmed
 * SSIDs are kept as SHA-256 hashes in this module's own preferences (not sensitive, not observations).
 */
class NetworkGate(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    /** Reads the current Wi-Fi network; cheap binder calls, safe from the main thread. */
    fun current(): WifiState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return WifiState.OFFLINE
        val network = wifiNetwork(cm) ?: return WifiState.OFFLINE
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val info = caps?.transportInfo as? WifiInfo
        val ssid = GateHashing.normalizeSsid(info?.ssid) ?: legacySsid()
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        return WifiState(network, ssid, lp)
    }

    /** WifiManager's own connection info, in case the transport info was redacted but this is not. */
    @Suppress("DEPRECATION")
    private fun legacySsid(): String? =
        runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()?.let(GateHashing::normalizeSsid)

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

    fun isConfirmed(ssid: String): Boolean = GateHashing.ssidHash(ssid) in confirmedHashes()

    fun confirm(ssid: String) {
        prefs.edit().putStringSet(KEY_CONFIRMED, confirmedHashes() + GateHashing.ssidHash(ssid)).apply()
    }

    fun forget(ssid: String) {
        prefs.edit().putStringSet(KEY_CONFIRMED, confirmedHashes() - GateHashing.ssidHash(ssid)).apply()
    }

    fun forgetAll() = prefs.edit().remove(KEY_CONFIRMED).apply()

    fun confirmedCount(): Int = confirmedHashes().size

    private fun confirmedHashes(): Set<String> = prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty().toSet()

    /** Decides whether a scan may run right now. Never throws. */
    fun check(): GateDecision {
        if (!hasPermission()) return GateDecision.Refused(LanKeys.REASON_NO_PERMISSION, WifiState.OFFLINE)
        val state = runCatching { current() }.getOrDefault(WifiState.OFFLINE)
        if (!state.onWifi) return GateDecision.Refused(LanKeys.REASON_NO_WIFI, state)
        val ssid = state.ssid ?: return GateDecision.Refused(LanKeys.REASON_SSID_UNKNOWN, state)
        if (!isConfirmed(ssid)) return GateDecision.Refused(LanKeys.REASON_NOT_CONFIRMED, state)
        return GateDecision.Allowed(state, ssid, GateHashing.ssidHash(ssid))
    }

    companion object {
        const val PREFS = "homenet_gate"
        const val KEY_CONFIRMED = "confirmed_ssid_hashes"
    }
}
