package io.github.stronghorse44.tunnels.runtime

import android.content.Intent
import android.content.pm.PackageManager
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
                        runCatching {
                            val intent = Intent(access.settingsAction)
                            if (access.settingsAction.contains("APPLICATION_DETAILS") || access.settingsAction.contains("UNKNOWN_APP")) {
                                intent.data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }) { Text("Open setting") }
                }
            }
            Text("Nothing is requested until you open a tunnel that needs it.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
        }
    }
}
