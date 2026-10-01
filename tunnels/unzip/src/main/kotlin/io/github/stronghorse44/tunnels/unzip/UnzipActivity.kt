package io.github.stronghorse44.tunnels.unzip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate

class UnzipActivity : ComponentActivity() {
    private val vm: UnzipViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) Staging.incomingUri(intent)?.let(vm::open)
        setContent { TunnelsTheme { AppLockGate { UnzipScreen(vm, onBack = ::finish) } } }
    }

    override fun onDestroy() {
        if (isFinishing) vm.discard()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Staging.incomingUri(intent)?.let(vm::open)
    }
}
