package io.github.stronghorse44.tunnels.emissions

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnel (emissions). Discovered through META-INF/services. */
class EmissionsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(EmissionsTunnel(context.applicationContext))
}
