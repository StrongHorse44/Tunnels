package io.github.stronghorse44.tunnels.backups

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnel (backups). Discovered through META-INF/services. */
class BackupsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(BackupsTunnel(context.applicationContext))
}
