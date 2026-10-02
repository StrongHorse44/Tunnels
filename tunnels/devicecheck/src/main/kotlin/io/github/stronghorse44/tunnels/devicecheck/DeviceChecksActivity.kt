package io.github.stronghorse44.tunnels.devicecheck

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate

/** Is Tunnels reading this phone right? Each reading it relies on, checked on the device. */
class DeviceChecksActivity : ComponentActivity() {
    private val vm: DeviceChecksViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TunnelsTheme { AppLockGate { DeviceChecksScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        /** The implicit, same-package action the home screen fires. */
        const val ACTION = "io.github.stronghorse44.tunnels.action.DEVICE_CHECKS"

        fun intent(context: Context): Intent = Intent(ACTION).setPackage(context.packageName)
    }
}
