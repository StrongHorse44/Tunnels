package io.github.stronghorse44.tunnels.traffic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Read-only questions about the device's VPN and DNS state. Every call swallows system errors.
 *
 * `VpnService.prepare()` is not read-only: once the user has consented to Tunnels, the system treats a
 * later `prepare()` as "make Tunnels the current VPN" and tears down whichever other VPN is connected
 * (its service gets `onRevoke`). Always-on VPNs are protected from that, ordinary ones are not. So this
 * object is the only place that calls `prepare()`, and it never does so while any VPN is connected:
 * the caller's own [anyVpnActive] check then decides what to say ([OTHER_VPN_MESSAGE]).
 */
object VpnStatus {
    const val OTHER_VPN_MESSAGE = "Another VPN is connected. Disconnect it in VPN settings first; an Always-on VPN restarts " +
        "the moment it drops, so turn Always-on off (gear icon) before disconnecting. Tunnels never disconnects it for you."

    /**
     * [OTHER_VPN_MESSAGE] naming the installed VPN apps (other than Tunnels), since Android does not tell
     * an ordinary app which of them owns the connected VPN network.
     */
    fun otherVpnMessage(context: Context): String {
        val names = vpnAppLabels(context)
        return if (names.isEmpty()) OTHER_VPN_MESSAGE
        else "Another VPN is connected (installed VPN apps: ${names.joinToString()}). Disconnect it in VPN settings " +
            "first; an Always-on VPN restarts the moment it drops, so turn Always-on off (gear icon) before " +
            "disconnecting. Tunnels never disconnects it for you."
    }

    /** Labels of apps exposing a VpnService, Tunnels excluded (manifest <queries> grants the visibility). */
    fun vpnAppLabels(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        pm.queryIntentServices(Intent("android.net.VpnService"), PackageManager.MATCH_ALL)
            .map { it.serviceInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { it.loadLabel(pm).toString() }
            .sorted()
    }.getOrDefault(emptyList())

    /** Documented last resort when the underlying network reports no usable resolver. */
    val FALLBACK_RESOLVER: InetAddress = InetAddress.getByAddress("one.one.one.one", byteArrayOf(1, 1, 1, 1))

    /**
     * True when the system still has to show the VPN consent dialog for this app. False, without asking
     * the system, while any VPN is connected: asking would disconnect it (see the class comment), and the
     * START flow refuses to run beside another VPN anyway.
     */
    fun consentNeeded(context: Context): Boolean {
        if (anyVpnActive(context)) return false
        return runCatching { VpnService.prepare(context) != null }.getOrDefault(true)
    }

    /**
     * The consent dialog intent when consent is still needed, else null. Never asks the system while a
     * VPN is connected (see the class comment); callers check [anyVpnActive] first and explain instead.
     */
    fun consentIntent(context: Context): Intent? {
        if (anyVpnActive(context)) return null
        return runCatching { VpnService.prepare(context) }.getOrNull()
    }

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

    /** True when [network] is a VPN (ours included), so its resolvers must not be used upstream. */
    fun isVpn(cm: ConnectivityManager, network: Network): Boolean =
        runCatching { cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }.getOrDefault(false)

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
