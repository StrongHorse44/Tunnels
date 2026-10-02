package io.github.stronghorse44.tunnels

import android.app.Application
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.runtime.AppLock
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.watch.WatchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TunnelsApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLock.install(this)
        scope.launch {
            Staging.clearStale(this@TunnelsApp)
            runCatching { TunnelsRuntime.get(this@TunnelsApp).store.maintain() }
            // Background checks the user switched on: re-arm the job if the system lost it.
            runCatching { WatchScheduler.ensure(this@TunnelsApp) }
        }
    }
}
