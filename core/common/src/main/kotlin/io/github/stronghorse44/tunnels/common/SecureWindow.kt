package io.github.stronghorse44.tunnels.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * Marks the screen's window FLAG_SECURE while this is in the composition (audit L6): no screenshot, screen recording or
 * Recents thumbnail of a passphrase being typed or a pairing code being shown. Counted, so two secure parts of one screen
 * do not clear each other.
 */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureCount.acquire(activity)
        onDispose { SecureCount.release(activity) }
    }
}

/** For an AlertDialog holding a secret: its own window is FLAG_SECURE too, whatever the screen behind it is. */
val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

private object SecureCount {
    private val held = java.util.WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) {
        val n = held[activity] ?: 0
        held[activity] = n + 1
        if (n == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        val n = (held[activity] ?: 1) - 1
        if (n <= 0) {
            held.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            held[activity] = n
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
