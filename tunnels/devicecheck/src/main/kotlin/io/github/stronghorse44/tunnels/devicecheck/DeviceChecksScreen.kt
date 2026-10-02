package io.github.stronghorse44.tunnels.devicecheck

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.RestrictedSettings
import io.github.stronghorse44.tunnels.runtime.ToolScaffold
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

fun statusColor(s: CheckStatus): Color = when (s) {
    CheckStatus.PASS -> StatusColors.ok
    CheckStatus.NOTE -> StatusColors.info
    CheckStatus.TODO -> GlassColors.text
    CheckStatus.WARN -> StatusColors.warn
    CheckStatus.FAIL -> StatusColors.blocker
}

/** Every reading Tunnels relies on, checked on this phone, with what to do next. Re-runs when you come back. */
@Composable
fun DeviceChecksScreen(vm: DeviceChecksViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from Settings (a toggle flipped, an access granted): read the phone again.
    LifecycleResumeEffect(Unit) {
        vm.run()
        onPauseOrDispose { }
    }
    val tint = LineColors.of(MetroLine.SYSTEM)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ToolScaffold("Device checks", "is Tunnels reading this phone right", tint, onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Tunnels' findings rest on readings it takes from the system. These checks confirm each one on this " +
                                "phone, so a quiet tunnel means nothing is wrong rather than nothing is visible. They are not " +
                                "findings, and nothing here is stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassColors.dim,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val worst = ChecksSummary.worst(state.groups)
                                Text(
                                    if (state.groups.isEmpty()) "Not run yet" else ChecksSummary.line(state.groups),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = worst?.let(::statusColor) ?: GlassColors.dim,
                                )
                                if (state.ranAt > 0) Text("Checked at ${fmt.format(Date(state.ranAt))}", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                            }
                            Button(onClick = vm::run, enabled = !state.running) { Text(if (state.running) "Checking…" else "Run again") }
                        }
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { Text(it, color = StatusColors.blocker, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            state.groups.forEach { group ->
                item(key = "title:${group.title}") {
                    Text(group.title.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GlassColors.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                }
                items(group.results, key = { "check:${it.id}" }) { r ->
                    CheckCard(r) { action -> scope.launch { open(context, action) } }
                }
            }
        }
    }
}

@Composable
private fun CheckCard(r: CheckResult, onAction: (CheckAction) -> Unit) {
    val color = statusColor(r.status)
    GlassPanel(Modifier.fillMaxWidth(), tint = color) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(r.status.label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
                Spacer(Modifier.width(10.dp))
                Text(r.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(r.detail, style = MaterialTheme.typography.bodyMedium)
            r.action?.let { a -> OutlinedButton(onClick = { onAction(a) }) { Text(a.label) } }
        }
    }
}

/** Opens what a check's button points at; a phone without that screen gets a short note instead. */
private suspend fun open(context: Context, action: CheckAction) {
    fun start(intent: Intent) = try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No screen on this phone handles that.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Android did not allow opening that screen.", Toast.LENGTH_SHORT).show()
    }
    when (action) {
        is CheckAction.OpenSettings -> start(
            Intent(action.action).apply { if (action.forThisApp) data = Uri.parse("package:${context.packageName}") },
        )
        is CheckAction.OpenTunnel -> start(TunnelActivity.intent(context, action.tunnelId))
        is CheckAction.OpenScreen -> start(Intent(action.action).setPackage(context.packageName))
        is CheckAction.OpenApp -> context.packageManager.getLaunchIntentForPackage(action.packageName)?.let(::start)
            ?: Toast.makeText(context, "That app is not installed.", Toast.LENGTH_SHORT).show()
        is CheckAction.GrantAccess -> {
            val access = TunnelsRuntime.get(context).registry[action.tunnelId]?.specialAccess?.firstOrNull { it.id == action.accessId }
            if (access == null || !RestrictedSettings.open(context, access)) {
                Toast.makeText(context, "That setting could not be opened. Open the tunnel instead.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
