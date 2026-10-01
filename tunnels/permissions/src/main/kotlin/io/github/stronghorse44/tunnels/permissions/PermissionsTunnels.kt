package io.github.stronghorse44.tunnels.permissions

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (permissions). Discovered through META-INF/services. */
class PermissionsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
