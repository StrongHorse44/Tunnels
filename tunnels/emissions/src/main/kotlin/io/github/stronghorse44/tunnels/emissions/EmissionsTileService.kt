package io.github.stronghorse44.tunnels.emissions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Silence" Quick Settings tile. Lit when nothing Android's app APIs can see is on (the tile never binds the shell
 * just to draw itself). A tap with Shizuku ready silences, or restores what the last Silence turned off; Restore turns
 * radios back on, so on a locked phone it waits for the unlock. Without Shizuku a tap opens the Emissions tunnel.
 */
class EmissionsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val silencer by lazy { Silencer(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        scope.launch {
            val access = withContext(Dispatchers.IO) { EmissionsProbe.shellAccess(applicationContext) }
            if (access != ShellAccess.READY) {
                openTunnel()
                return@launch
            }
            val quiet = withContext(Dispatchers.IO) { TileText.of(EmissionsProbe.readApp(applicationContext)).active }
            when {
                !quiet -> act("Silencing…") { silencer.silence() }
                silencer.remembered().isEmpty() -> openTunnel()
                isLocked -> unlockAndRun { act("Restoring…") { silencer.restore() } }
                else -> act("Restoring…") { silencer.restore() }
            }
        }
    }

    /** Runs [block] in [work], not in this service's scope: closing the shade unbinds the tile, and must not stop it. */
    private fun act(working: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        qsTile?.apply {
            subtitle = working
            updateTile()
        }
        val app = applicationContext
        work.launch {
            val result = try {
                block()
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, result, Toast.LENGTH_LONG).show() }
            requestListeningState(app, ComponentName(app, EmissionsTileService::class.java))
        }
    }

    private fun refresh() {
        scope.launch {
            val look = withContext(Dispatchers.IO) { TileText.of(EmissionsProbe.readApp(applicationContext)) }
            val tile = qsTile ?: return@launch
            tile.label = "Silence"
            tile.subtitle = look.subtitle
            tile.state = if (look.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    private fun openTunnel() {
        val intent = TunnelActivity.intent(this, EmissionsKeys.TUNNEL_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pending)
    }

    companion object {
        /** Silence and Restore take a few seconds; they finish even after the system unbinds the tile. */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile private var busy = false
    }
}
