package io.github.stronghorse44.tunnels.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme

/** Hosts any tunnel that has no activity of its own. */
class TunnelActivity : ComponentActivity() {
    private val vm: TunnelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_TUNNEL) ?: run { finish(); return }
        setContent { TunnelsTheme { AppLockGate { TunnelScreen(vm, id, onBack = ::finish) } } }
    }

    companion object {
        const val EXTRA_TUNNEL = "io.github.stronghorse44.tunnels.extra.TUNNEL"
        fun intent(context: Context, tunnelId: String): Intent =
            Intent(context, TunnelActivity::class.java).putExtra(EXTRA_TUNNEL, tunnelId)
    }
}
