package io.github.stronghorse44.tunnels.deepmode

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (deep_mode). Discovered through META-INF/services. */
class DeepModeTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(DeepModeTunnel(context.applicationContext))
}
