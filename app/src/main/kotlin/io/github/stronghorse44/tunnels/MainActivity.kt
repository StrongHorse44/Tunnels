package io.github.stronghorse44.tunnels

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.stronghorse44.tunnels.breaches.BreachActivity
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.devicecheck.DeviceChecksActivity
import io.github.stronghorse44.tunnels.installer.InstallActivity
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.unzip.UnzipActivity
import io.github.stronghorse44.tunnels.updater.UpdateActivity
import io.github.stronghorse44.tunnels.watch.InboxActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TunnelsTheme {
                AppLockGate {
                WellHome(
                    onOpenTunnel = { id ->
                        when (id) {
                            TunnelCatalog.INSTALLER -> startActivity(Intent(this, InstallActivity::class.java))
                            TunnelCatalog.UNZIP -> startActivity(Intent(this, UnzipActivity::class.java))
                            else -> startActivity(TunnelActivity.intent(this, id))
                        }
                    },
                    onOpenUpdates = { startActivity(UpdateActivity.intent(this)) },
                    onOpenBreaches = { startActivity(BreachActivity.intent(this)) },
                    onOpenFindings = { startActivity(InboxActivity.intent(this)) },
                    onOpenChecks = { startActivity(DeviceChecksActivity.intent(this)) },
                )
                }
            }
        }
    }
}
