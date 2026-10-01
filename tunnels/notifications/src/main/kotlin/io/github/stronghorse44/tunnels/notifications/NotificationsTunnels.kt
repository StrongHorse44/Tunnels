package io.github.stronghorse44.tunnels.notifications

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (notifications). Discovered through META-INF/services. */
class NotificationsTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = emptyList()
}
