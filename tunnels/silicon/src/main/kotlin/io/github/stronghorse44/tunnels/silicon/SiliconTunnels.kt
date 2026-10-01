package io.github.stronghorse44.tunnels.silicon

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (silicon). Discovered through META-INF/services. */
class SiliconTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(SiliconTunnel(context.applicationContext))
}
