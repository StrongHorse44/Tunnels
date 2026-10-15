package io.github.stronghorse44.tunnels.pairing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate

/** Checks one phone from another, Auditor-style, through two QR codes and no network. */
class PairingActivity : ComponentActivity() {
    private val vm: PairingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The pairing codes and the camera view are never captured: no screenshot, recording or Recents thumbnail (audit L6).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { TunnelsTheme { AppLockGate { PairingScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        /** The implicit, same-package action device checks fire. */
        const val ACTION = "io.github.stronghorse44.tunnels.action.PAIRING"

        fun intent(context: Context): Intent = Intent(ACTION).setPackage(context.packageName)
    }
}
