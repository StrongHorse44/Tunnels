package io.github.stronghorse44.tunnels.runtime

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.TunnelModule

/**
 * Rule #6: asks for a tunnel's permissions only when the tunnel is opened, each with its one-line reason.
 * Renders [content] once everything is granted; re-checks whenever the screen resumes.
 */
@Composable
fun TunnelGate(module: TunnelModule, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { generation++; onPauseOrDispose { } }

    val missingPermissions = remember(generation) {
        module.requiredPermissions.filter { ContextCompat.checkSelfPermission(context, it.permission) != PackageManager.PERMISSION_GRANTED }
    }
    val missingAccess = remember(generation) { module.specialAccess.filterNot { it.isGranted() } }
    val fromFile = remember { RestrictedSettings.installedFromFile(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { generation++ }

    if (missingPermissions.isEmpty() && missingAccess.isEmpty()) {
        content()
        return
    }
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(module.info.line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${module.info.title} needs access", style = MaterialTheme.typography.titleMedium)
            missingPermissions.forEach { spec ->
                Column {
                    Text(spec.permission.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                    Text(spec.reason, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                }
            }
            if (missingPermissions.isNotEmpty()) {
                Button(onClick = { launcher.launch(missingPermissions.map { it.permission }.toTypedArray()) }) {
                    Text(if (missingPermissions.size == 1) "Allow" else "Allow ${missingPermissions.size} permissions")
                }
            }
            missingAccess.forEach { access ->
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(access.label, style = MaterialTheme.typography.bodyLarge)
                        Text(access.reason, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(RestrictedSettings.settingsIntent(context, access)) }.onFailure {
                            Toast.makeText(context, "No screen found for ${access.label}", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Open setting") }
                }
                if (access.restricted) RestrictedSettingsHelp(fromFile)
            }
            Text("Nothing is requested until you open a tunnel that needs it.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
        }
    }
}

/**
 * The extra step Android asks for before a restricted setting ([io.github.stronghorse44.tunnels.model.SpecialAccess.restricted])
 * can be switched on for an app installed from a file. Tunnels cannot read whether it was already taken, so
 * the steps say what Android will show.
 */
@Composable
private fun RestrictedSettingsHelp(fromFile: Boolean) {
    val context = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth(), tint = StatusColors.warn) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (fromFile) "Android restricts this for apps installed from a file" else "If Android says “App was denied access”",
                style = MaterialTheme.typography.labelLarge,
                color = StatusColors.warn,
            )
            Text(
                "1. Tap Open setting and switch it on. If Android answers “App was denied access”, one more step is needed:\n" +
                    "2. Tap Open App info below, then ⋮ at the top right → Allow restricted settings, and confirm with your PIN.\n" +
                    "3. Tap Open setting again and switch it on. Android keeps this choice across updates.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    runCatching { context.startActivity(RestrictedSettings.appInfoIntent(context)) }.onFailure {
                        Toast.makeText(context, "App info is not available", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open App info") }
            }
        }
    }
}
