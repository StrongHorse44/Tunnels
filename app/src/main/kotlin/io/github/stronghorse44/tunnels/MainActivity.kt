package io.github.stronghorse44.tunnels

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.installer.InstallActivity
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.unzip.UnzipActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TunnelsTheme {
                AppLockGate {
                MetroHome(onOpenTunnel = { id ->
                    when (id) {
                        TunnelCatalog.INSTALLER -> startActivity(Intent(this, InstallActivity::class.java))
                        TunnelCatalog.UNZIP -> startActivity(Intent(this, UnzipActivity::class.java))
                        else -> startActivity(TunnelActivity.intent(this, id))
                    }
                })
                }
            }
        }
    }
}
