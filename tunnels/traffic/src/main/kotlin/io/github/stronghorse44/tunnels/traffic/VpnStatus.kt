package io.github.stronghorse44.tunnels.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Read-only questions about the device's VPN and DNS state. Every call swallows system errors. */
object VpnStatus {
    const val OTHER_VPN_MESSAGE = "Another VPN (e.g. Surfshark) is connected. Pause it first; Tunnels never disconnects it for you."

    /** Documented last resort when the underlying network reports no usable resolver. */
    val FALLBACK_RESOLVER: InetAddress = InetAddress.getByAddress("one.one.one.one", byteArrayOf(1, 1, 1, 1))

    /** True when the system still has to show the VPN consent dialog for this app. */
    fun consentNeeded(context: Context): Boolean = runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    /** Any connected network with the VPN transport. While our own session runs, that includes ours. */
    fun anyVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            networks(cm).any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
    }

    /** A VPN that is not our session. */
    fun otherVpnActive(context: Context): Boolean = !DnsVpnService.isRunning && anyVpnActive(context)

    /** The best non-VPN network with internet, validated ones first. */
    fun underlyingNetwork(cm: ConnectivityManager): Network? = runCatching {
        networks(cm)
            .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .sortedByDescending { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .firstOrNull()?.first
    }.getOrNull()

    /**
     * Resolvers of [network] (IPv4 first, link-local IPv6 dropped since it needs a scope), or
     * [FALLBACK_RESOLVER] alone when there are none.
     */
    fun resolversOf(cm: ConnectivityManager, network: Network?): List<InetAddress> {
        val servers = network?.let { runCatching { cm.getLinkProperties(it)?.dnsServers }.getOrNull() }.orEmpty()
            .filter { it is Inet4Address || (it is Inet6Address && !it.isLinkLocalAddress) }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
        return servers.ifEmpty { listOf(FALLBACK_RESOLVER) }
    }

    @Suppress("DEPRECATION")
    private fun networks(cm: ConnectivityManager): List<Network> = cm.allNetworks.toList()
}
