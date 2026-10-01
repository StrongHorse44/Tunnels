package io.github.stronghorse44.tunnels.hardening

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (hardening). Discovered through META-INF/services. */
class HardeningTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
