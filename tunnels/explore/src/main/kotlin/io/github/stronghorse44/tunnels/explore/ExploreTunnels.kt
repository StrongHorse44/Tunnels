package io.github.stronghorse44.tunnels.explore

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (sensors, cameras, satellites, radio). Discovered through META-INF/services. */
class ExploreTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
