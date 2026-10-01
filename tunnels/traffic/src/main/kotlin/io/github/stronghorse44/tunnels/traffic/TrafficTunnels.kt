package io.github.stronghorse44.tunnels.traffic

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (traffic). Discovered through META-INF/services. */
class TrafficTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
