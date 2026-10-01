package io.github.stronghorse44.tunnels.runtime

import android.content.Context
import io.github.stronghorse44.tunnels.model.TunnelModule
import java.util.ServiceLoader

/**
 * Each tunnel module implements this and lists the class in
 * `META-INF/services/io.github.stronghorse44.tunnels.runtime.TunnelProvider`.
 */
interface TunnelProvider {
    fun create(context: Context): List<TunnelModule>
}

/** All tunnel modules found on the classpath, by id. Tests may pass [override] instead. */
class TunnelRegistry(context: Context, override: Map<String, TunnelModule>? = null) {
    val modules: Map<String, TunnelModule> = override
        ?: ServiceLoader.load(TunnelProvider::class.java, TunnelProvider::class.java.classLoader)
            .flatMap { it.create(context.applicationContext) }
            .associateBy { it.id }

    operator fun get(id: String): TunnelModule? = modules[id]
}
