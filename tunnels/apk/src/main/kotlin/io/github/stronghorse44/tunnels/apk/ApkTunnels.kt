package io.github.stronghorse44.tunnels.apk

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (apk_excavation). Discovered through META-INF/services. */
class ApkTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(ApkExcavationTunnel(context.applicationContext))
}
