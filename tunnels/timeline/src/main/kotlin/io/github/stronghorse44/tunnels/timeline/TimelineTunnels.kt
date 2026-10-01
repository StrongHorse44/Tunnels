package io.github.stronghorse44.tunnels.timeline

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (timeline). Discovered through META-INF/services. */
class TimelineTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
