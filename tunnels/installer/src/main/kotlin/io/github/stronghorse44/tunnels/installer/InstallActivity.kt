package io.github.stronghorse44.tunnels.installer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
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
        setContent { TunnelsTheme { AppLockGate { InstallScreen(vm, onBack = ::finish, onHandOff = ::handOffToSystemInstaller) } } }
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

    /**
     * Passes the incoming install request on to the system package installer, which the sender already
     * granted read access to. Tunnels holds no grant, so none is passed along; a result the sender asked
     * for goes straight back to it.
     */
    private fun handOffToSystemInstaller() {
        val incoming = intent
        val uri = Staging.incomingUri(incoming) ?: return
        // A shared (SEND) file becomes a plain VIEW; VIEW and INSTALL_PACKAGE requests keep their extras.
        val shared = incoming.action == Intent.ACTION_SEND
        val probe = Intent(if (shared) Intent.ACTION_VIEW else incoming.action).setDataAndType(uri, APK_MIME)
        val target = packageManager.queryIntentActivities(
            probe,
            PackageManager.ResolveInfoFlags.of((PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).toLong()),
        ).map { it.activityInfo }.firstOrNull { it.packageName != packageName }
        if (target == null) {
            Toast.makeText(this, "Android's installer isn't available", Toast.LENGTH_LONG).show()
            return
        }
        val forward = (if (shared) probe else Intent(incoming)).apply {
            component = ComponentName(target.packageName, target.name)
            flags = if (callingActivity != null) Intent.FLAG_ACTIVITY_FORWARD_RESULT else 0
        }
        try {
            startActivity(forward)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Android's installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val EXTRA_STAGED_PATH = "io.github.stronghorse44.tunnels.extra.STAGED_PATH"

        /** Opens the installer on a file already in [Staging]. Paths outside staging are refused. */
        fun stagedIntent(context: Context, file: File): Intent =
            Intent(context, InstallActivity::class.java).putExtra(EXTRA_STAGED_PATH, file.absolutePath)
    }
}
