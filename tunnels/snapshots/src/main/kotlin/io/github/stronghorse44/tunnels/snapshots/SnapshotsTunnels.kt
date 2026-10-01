package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/**
 * Discovered through META-INF/services. Snapshots is a screen (history, diff, export/import, app lock), not a
 * tunnel: it observes nothing of its own, so it registers no modules. See [SnapshotsActivity].
 */
class SnapshotsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
