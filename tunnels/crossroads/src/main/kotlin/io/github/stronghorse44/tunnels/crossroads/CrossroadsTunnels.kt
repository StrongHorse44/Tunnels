package io.github.stronghorse44.tunnels.crossroads

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnel (crossroads). Discovered through META-INF/services. */
class CrossroadsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(CrossroadsTunnel(context.applicationContext))
}
