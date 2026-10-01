package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (surroundings). Discovered through META-INF/services. */
class SurroundingsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(SurroundingsTunnel(context.applicationContext))
}
