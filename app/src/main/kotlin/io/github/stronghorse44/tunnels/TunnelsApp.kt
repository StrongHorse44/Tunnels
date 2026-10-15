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
        // The sealed decoder's process (tunnels:decoder, isolatedProcess) runs this class too. It has no access to Tunnels'
        // data, and must never open the store (whose failed-start count it would feed), touch staging or install the lock.
        if (android.os.Process.isIsolated()) return
        AppLock.install(this)
        // Every start empties the staging area (audit L9): renamed away now, before any screen stages a file, deleted below.
        runCatching { Staging.clearOnStart(this) }
        scope.launch {
            runCatching { Staging.clearTrash(this@TunnelsApp) }
            runCatching { TunnelsRuntime.get(this@TunnelsApp).store.maintain() }
            // Background checks the user switched on: re-arm the job if the system lost it.
            runCatching { WatchScheduler.ensure(this@TunnelsApp) }
        }
    }
}
