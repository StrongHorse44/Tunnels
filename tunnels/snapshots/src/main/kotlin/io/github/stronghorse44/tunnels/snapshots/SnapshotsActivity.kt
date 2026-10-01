package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate

/** The Snapshots screen: take a full snapshot, browse history, compare two, export/import, app lock. */
class SnapshotsActivity : ComponentActivity() {
    private val vm: SnapshotsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TunnelsTheme { AppLockGate { SnapshotsScreen(vm, onBack = ::finish) } } }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: a screen lock may have been set up or removed.
        vm.refreshLock()
    }

    companion object {
        /** The implicit, same-package action the home screen fires. */
        const val ACTION = "io.github.stronghorse44.tunnels.action.SNAPSHOTS"

        fun intent(context: Context): Intent = Intent(ACTION).setPackage(context.packageName)
    }
}
