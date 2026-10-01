package io.github.stronghorse44.tunnels.syspackages

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (system_packages). Discovered through META-INF/services. */
class SysPackagesTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
