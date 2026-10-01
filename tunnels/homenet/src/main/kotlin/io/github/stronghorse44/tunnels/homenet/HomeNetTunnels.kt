package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (home_network). Discovered through META-INF/services. */
class HomeNetTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
