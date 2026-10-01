package io.github.stronghorse44.tunnels.explore

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/**
 * Registers this module's tunnels. Discovered through META-INF/services. Phase 1 ships sensors and
 * cameras; satellites and radio (phase 4) are one more line each here.
 */
class ExploreTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(
        SensorsTunnel(context),
        CamerasTunnel(context),
    )
}
