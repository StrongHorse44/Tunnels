package io.github.stronghorse44.tunnels.explore

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/**
 * Registers this module's tunnels. Discovered through META-INF/services. Phase 1 ships sensors and
 * cameras; phase 4 adds satellites and radio.
 */
class ExploreTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(
        SensorsTunnel(context),
        CamerasTunnel(context),
        SatellitesTunnel(context),
        RadioTunnel(context),
    )
}
