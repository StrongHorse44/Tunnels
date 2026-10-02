package io.github.stronghorse44.tunnels.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate

/** Findings from every tunnel in one list, and the background checks that keep it current. */
class InboxActivity : ComponentActivity() {
    private val vm: InboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TunnelsTheme { AppLockGate { InboxScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        /** The implicit, same-package action the home screen fires. */
        const val ACTION = "io.github.stronghorse44.tunnels.action.INBOX"

        fun intent(context: Context): Intent = Intent(ACTION).setPackage(context.packageName)
    }
}
