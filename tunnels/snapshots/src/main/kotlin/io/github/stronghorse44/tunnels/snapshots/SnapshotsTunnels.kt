package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels ((snapshot UI, export/import, app lock)). Discovered through META-INF/services. */
class SnapshotsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
