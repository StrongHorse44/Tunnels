package io.github.stronghorse44.tunnels.crossroads

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import io.github.stronghorse44.tunnels.runtime.AppPage

/** Everything Tunnels knows about one app, from every tunnel, with that app's findings and actions. */
class AppActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrBlank()) {
            finish()
            return
        }
        vm.open(pkg)
        setContent { TunnelsTheme { AppLockGate { AppScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        /** Implicit, same-package action ([AppPage]): any screen that shows an app can open this page without depending on it. */
        const val ACTION = AppPage.ACTION
        const val EXTRA_PACKAGE = AppPage.EXTRA_PACKAGE

        fun intent(context: Context, packageName: String): Intent = AppPage.intent(context, packageName)
    }
}
