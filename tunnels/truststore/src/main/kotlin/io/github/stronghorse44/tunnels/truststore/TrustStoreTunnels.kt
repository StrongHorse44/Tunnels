package io.github.stronghorse44.tunnels.truststore

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelProvider

/** Registers this module's tunnels (trust_store). Discovered through META-INF/services. */
class TrustStoreTunnels : TunnelProvider {
    override fun create(context: Context): List<TunnelModule> = listOf(TrustStoreTunnel(context.applicationContext))
}
