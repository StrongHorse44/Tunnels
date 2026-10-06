package io.github.stronghorse44.tunnels.convert

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

class ConvertActivity : ComponentActivity() {
    private val vm: ConvertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { TunnelsTheme { AppLockGate { ConvertScreen(vm, onBack = ::finish) } } }
    }

    override fun onDestroy() {
        if (isFinishing) vm.discard()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = Staging.incomingUri(intent)
        if (uri != null) {
            vm.open(uri)
            return
        }
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrEmpty()) vm.openText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    companion object {
        /** Event stream for finished conversions: file name and "RTF → PDF · 3 pages", never content. */
        const val STREAM = "convert"

        fun intent(context: Context) = Intent(context, ConvertActivity::class.java)
    }
}
