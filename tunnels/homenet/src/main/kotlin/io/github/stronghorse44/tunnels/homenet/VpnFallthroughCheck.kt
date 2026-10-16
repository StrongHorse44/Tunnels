package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.system.ErrnoException
import android.system.OsConstants
import io.github.stronghorse44.tunnels.lan.LanScope
import io.github.stronghorse44.tunnels.lan.Ssdp
import io.github.stronghorse44.tunnels.lan.VpnFallthrough
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * May a probe socket go out unbound because a VPN Tunnels cannot bypass refused to let it bind to the Wi-Fi
 * ([VpnFallthrough])? Asked again for every socket, so a VPN that changes its routes mid-scan (an exit node switched on)
 * stops the next probe. It reads the VPN networks' routes and lets the kernel choose a source address for the gateway
 * and the SSDP group on unbound UDP sockets that are connected, never written to: nothing is sent.
 */
internal class VpnFallthroughCheck(context: Context, private val state: WifiState) {
    private val app = context.applicationContext

    fun allowed(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val gateway = state.gateway
        if (cm == null || gateway == null) {
            false
        } else {
            val group = LanScope.parseLiteral(Ssdp.ADDRESS)
            VpnFallthrough.allows(
                wifiOwn = state.prefixes.map { it.first },
                wifiPrefixes = state.prefixes,
                vpnRoutes = vpnUnicastRoutes(cm),
                sources = listOf(sourceFor(gateway), group?.let(::sourceFor)),
            )
        }
    } catch (_: Exception) {
        false
    }

    /** Every unicast route of every VPN network; null when one VPN's link could not be read. */
    @Suppress("DEPRECATION")
    private fun vpnUnicastRoutes(cm: ConnectivityManager): List<Pair<InetAddress, Int>>? {
        val out = ArrayList<Pair<InetAddress, Int>>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = cm.getLinkProperties(n) ?: return null
            for (r in lp.routes) {
                if (r.type != RouteInfo.RTN_UNICAST) continue
                out += r.destination.address to r.destination.prefixLength
            }
        }
        return out
    }

    /** The source address the kernel picks for [destination], on a UDP socket that is connected but never sent on. */
    private fun sourceFor(destination: InetAddress): InetAddress? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(destination, DISCARD_PORT))
            socket.localAddress?.takeIf { !it.isAnyLocalAddress }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val DISCARD_PORT = 9

        /** True when [e] is Android refusing the bind because a VPN Tunnels cannot bypass covers it (EPERM). */
        fun isVpnRefusal(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is ErrnoException && t.errno == OsConstants.EPERM) return true
                t = t.cause
            }
            return e is SocketException && e.message?.contains("EPERM") == true
        }
    }
}
