package io.github.stronghorse44.tunnels.lan

/**
 * Android refuses `Network.bindSocket` to the Wi-Fi with EPERM while a VPN that Tunnels may not bypass covers it (netd
 * lets an app covered by a secure VPN use only that VPN; Tailscale, for one, never allows bypass). Tunnels then cannot
 * reach the Wi-Fi with a bound socket and sends nothing (RJ, OQ197: the bind guarantee stays). This tells that refusal
 * apart from a network that went away, so Home network can say what to do: exclude Tunnels in the VPN app, or
 * disconnect it. Plain Kotlin: [errno] reads an exception's errno (Android's `ErrnoException`), [eperm] is EPERM.
 */
object BindRefusal {
    fun isVpnRefusal(t: Throwable, errno: (Throwable) -> Int?, eperm: Int): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < MAX_DEPTH) {
            if (errno(e) == eperm) return true
            if (e.message?.contains("EPERM") == true) return true
            e = e.cause
            depth++
        }
        return false
    }

    private const val MAX_DEPTH = 8
}
