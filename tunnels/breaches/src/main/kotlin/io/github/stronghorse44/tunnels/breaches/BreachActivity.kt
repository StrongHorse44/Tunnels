package io.github.stronghorse44.tunnels.breaches

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassBackground
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Fetches the public breach list on a tap and hands it, in memory, to Linx on this phone. Not exported. */
class BreachActivity : ComponentActivity() {
    private val vm: BreachViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TunnelsTheme { AppLockGate { BreachScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BreachActivity::class.java)
    }
}

private val accent = LineColors.of(MetroLine.FILES)

@Composable
fun BreachScreen(vm: BreachViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.networkAllowed.collectAsStateWithLifecycle()
    val linx by vm.linxInstalled.collectAsStateWithLifecycle()
    val sendProblem by vm.sendProblem.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val appInfo = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GlassColors.text) }
                Text("Breach list for Linx", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Card {
                Text(
                    "Linx can warn you when one of your accounts was in a known data breach. For that it needs the public list of breaches. " +
                        "Tunnels downloads it when you tap Fetch, keeps it in memory only, and hands it to Linx on this phone when you tap " +
                        "Send to Linx. It is never saved to a file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Line("source", "${BreachSource.HOST} (Have I Been Pwned)")
                Line("licence", "${Catalogue.LICENCE}, attribution required")
            }

            if (!network) {
                Notice(
                    "Network is off for Tunnels",
                    "Tunnels' Network permission must be on for this; turn it off afterwards. On GrapheneOS: " +
                        "App info → Permissions → Network → Allow, then come back.",
                    StatusColors.warn,
                ) { TextButton(onClick = appInfo) { Text("Open App info") } }
            }

            when (val s = state) {
                BreachState.Idle -> Button(onClick = vm::fetch, modifier = Modifier.fillMaxWidth()) { Text("Fetch") }
                BreachState.Fetching -> {
                    Busy("Asking ${BreachSource.HOST} for the breach list…")
                    OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
                BreachState.Gone -> {
                    Notice("The list is no longer held", "It is kept for ten minutes, or until it has been handed over three times. Fetch it again to send it.", StatusColors.warn) {}
                    Button(onClick = vm::fetch, modifier = Modifier.fillMaxWidth()) { Text("Fetch") }
                }
                is BreachState.Failed -> {
                    Notice(s.title, s.detail, StatusColors.blocker) {}
                    Button(onClick = vm::fetch, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                is BreachState.Held -> {
                    Card {
                        Text("Fetched", style = MaterialTheme.typography.titleSmall, color = StatusColors.ok)
                        Line("breaches", s.count.toString())
                        Line("skipped", "${s.skipped} (entries Tunnels could not represent)")
                        Line("fetched", s.fetched.replace('T', ' ').removeSuffix("Z") + " UTC")
                        Line("size", formatBytes(s.bytes.toLong()))
                        Line("held until", clock(s.expiresAtMs) + " · ${s.servesLeft} hand-over${if (s.servesLeft == 1) "" else "s"} left")
                        Text(s.attribution, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                    }
                    Button(
                        onClick = {
                            val intent = vm.sendIntent()
                            if (intent == null) vm.refresh()
                            else {
                                runCatching { context.startActivity(intent) }.onFailure { vm.sendFailed() }
                                vm.sent()
                            }
                        },
                        enabled = linx,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send to Linx") }
                    if (!linx) Text(BreachMessages.LINX_MISSING, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                    sendProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.blocker) }
                    Row {
                        OutlinedButton(onClick = vm::fetch) { Text("Fetch again") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = vm::forgetHeld) { Text("Forget it now") }
                    }
                }
            }

            Text(
                "Tunnels goes online only from this screen, only when you tap Fetch, and only to ${BreachSource.HOST}, over HTTPS, " +
                    "once. The request carries no account, address, domain or key; the site sees this phone's IP address. The list " +
                    "holds no data about you. It stays in this app's memory for ten minutes (or three hand-overs to Linx) and is " +
                    "never written to storage. Tunnels keeps one line about the last fetch: when, and how many breaches.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(ms: Long): String = clockFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun Card(content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = accent) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Notice(title: String, body: String, color: Color, action: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = color, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(body, style = MaterialTheme.typography.bodySmall)
            action()
        }
    }
}

@Composable
private fun Busy(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = accent)
        Text(message)
    }
}

@Composable
private fun Line(key: String, value: String) {
    Row {
        Text(key, fontFamily = FontFamily.Monospace, color = GlassColors.dim, modifier = Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall)
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
    }
}
