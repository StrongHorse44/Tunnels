package io.github.stronghorse44.tunnels.installer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import java.io.File

class InstallActivity : ComponentActivity() {
    private val vm: InstallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        setContent { TunnelsTheme { AppLockGate { InstallScreen(vm, onBack = ::finish) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onDestroy() {
        if (isFinishing) vm.discard()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermission()
    }

    private fun route(intent: Intent) {
        val stagedPath = intent.getStringExtra(EXTRA_STAGED_PATH)
        if (stagedPath != null) {
            vm.openStaged(File(stagedPath))
        } else {
            Staging.incomingUri(intent)?.let(vm::open)
        }
    }

    companion object {
        private const val EXTRA_STAGED_PATH = "io.github.stronghorse44.tunnels.extra.STAGED_PATH"

        /** Opens the installer on a file already in [Staging]. Paths outside staging are refused. */
        fun stagedIntent(context: Context, file: File): Intent =
            Intent(context, InstallActivity::class.java).putExtra(EXTRA_STAGED_PATH, file.absolutePath)
    }
}
