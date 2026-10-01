package io.github.stronghorse44.tunnels

import android.app.Application
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TunnelsApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            Staging.clearStale(this@TunnelsApp)
            runCatching { TunnelsStore.get(this@TunnelsApp).maintain() }
        }
    }
}
