package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider
import kotlin.concurrent.thread

/** Registers this module's tunnels (home_network). Discovered through META-INF/services. */
class HomeNetTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> {
        val module = HomeNetworkTunnel(context.applicationContext)
        // First run after an update from a build that kept the confirmed networks in plaintext preferences: move them
        // into the encrypted store now, not whenever Home network is next opened. Off the main thread (it opens the
        // store); idempotent, and a failure leaves the old file for the next start while the gate fails closed.
        thread(name = "homenet-gate-migrate", isDaemon = true) { runCatching { module.gate.migrate() } }
        return listOf(module)
    }
}
