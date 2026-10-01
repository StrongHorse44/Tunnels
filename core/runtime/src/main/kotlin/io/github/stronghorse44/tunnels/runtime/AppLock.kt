package io.github.stronghorse44.tunnels.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassBackground
import io.github.stronghorse44.tunnels.common.GlassColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional app lock (rule #5): unlock with the device credential or a strong biometric. Off by default.
 * The lock re-arms after the app has been in the background for [RELOCK_AFTER_MS]. The enabled flag is
 * not sensitive and lives in plain SharedPreferences; everything it protects is in the encrypted store.
 */
object AppLock {
    private const val PREFS = "tunnels.applock"
    private const val KEY_ENABLED = "enabled"
    private const val RELOCK_AFTER_MS = 30_000L
    private const val AUTHENTICATORS = BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private var startedActivities = 0
    private var backgroundedAt = 0L
    private var installed = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _locked.value = false
    }

    /** True when the device has a credential set, so the lock can actually be used. */
    fun canLock(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Call once from Application.onCreate. */
    fun install(app: Application) {
        if (installed) return
        installed = true
        _locked.value = isEnabled(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0 && backgroundedAt != 0L && isEnabled(app) &&
                    System.currentTimeMillis() - backgroundedAt > RELOCK_AFTER_MS
                ) {
                    _locked.value = true
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) backgroundedAt = System.currentTimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Shows the system credential prompt. */
    fun prompt(activity: Activity, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Tunnels")
            .setSubtitle("Your findings and snapshots stay locked until you confirm it's you.")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
    }
}

/** Wraps a screen: shows the lock screen and the system prompt while [AppLock] is engaged. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!locked) {
        content()
        return
    }
    val activity = context as? Activity
    LaunchedEffect(Unit) { activity?.let { AppLock.prompt(it) {} } }
    GlassBackground {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Locked", style = MaterialTheme.typography.headlineMedium, color = GlassColors.text)
            Text("Confirm it's you to continue.", color = GlassColors.dim, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            Button(onClick = { activity?.let { AppLock.prompt(it) {} } }) { Text("Unlock") }
        }
    }
}
