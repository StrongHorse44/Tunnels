package io.github.stronghorse44.tunnels.deepmode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.model.MetroLine

/**
 * The "Open setting" target of Deep mode's Shizuku special access. Shows the status card with the
 * connect flow and closes itself once Tunnels is granted, back to the tunnel screen, which re-checks on resume.
 */
class ShizukuConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TunnelsTheme {
                TunnelScaffold("Deep mode", MetroLine.SYSTEM, onBack = ::finish) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShizukuStatusCard(onGranted = ::finish)
                        Text(
                            "Shizuku gives apps you choose the same access as a USB debugging shell: it can read every app's " +
                                "permission history and hidden system settings, and revoke permissions directly. Tunnels only ever " +
                                "stores summaries from it, and only while you run a scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassColors.dim,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        OutlinedButton(onClick = ::finish) { Text("Back") }
                    }
                }
            }
        }
    }

    companion object {
        /** Implicit action the tunnel gate fires; declared on this activity for this app only. */
        const val ACTION = "io.github.stronghorse44.tunnels.deepmode.action.CONNECT_SHIZUKU"
    }
}
