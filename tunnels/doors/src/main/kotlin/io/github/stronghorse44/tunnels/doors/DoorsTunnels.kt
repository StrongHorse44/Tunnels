package io.github.stronghorse44.tunnels.doors

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (doors). Discovered through META-INF/services. */
class DoorsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(DoorsTunnel(context.applicationContext))
}
