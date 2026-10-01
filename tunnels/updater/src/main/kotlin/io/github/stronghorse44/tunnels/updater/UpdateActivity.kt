package io.github.stronghorse44.tunnels.updater

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import io.github.stronghorse44.tunnels.updates.Channel
import io.github.stronghorse44.tunnels.updates.Update
import io.github.stronghorse44.tunnels.updates.UpdateSource

/** Checks GitHub for a newer Tunnels, downloads and verifies it, and hands it to the system installer. */
class UpdateActivity : ComponentActivity() {
    private val vm: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TunnelsTheme { AppLockGate { UpdateScreen(vm, onBack = ::finish) } } }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, UpdateActivity::class.java)
    }
}

private val accent = LineColors.of(MetroLine.FILES)

@Composable
fun UpdateScreen(vm: UpdateViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.networkAllowed.collectAsStateWithLifecycle()
    val canInstall by vm.canInstall.collectAsStateWithLifecycle()
    val tokenHint by vm.tokenHint.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    LaunchedEffect(confirm) {
        confirm?.let {
            runCatching { context.startActivity(it) }
            vm.confirmShown()
        }
    }
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
                Text("Updates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Card {
                Line("installed", "v${vm.installed.versionName}")
                Line(
                    "follows",
                    if (vm.installed.channel == Channel.DEBUG) "Debug build #N prereleases" else "vX.Y.Z releases",
                )
                Line("source", "${UpdateSource.OWNER}/${UpdateSource.REPO} on GitHub")
            }

            if (!network) {
                Notice(
                    "Network is off for Tunnels",
                    "GrapheneOS blocks Tunnels from the internet, which is how it should be outside a session. For the update: " +
                        "App info → Permissions → Network → Allow, then come back. Turn it off again once the update is installed.",
                    StatusColors.warn,
                ) { TextButton(onClick = appInfo) { Text("Open App info") } }
            }

            TokenCard(tokenHint, vm::saveToken, vm::forgetToken, highlight = (state as? UpdateState.Failed)?.tokenProblem == true)

            when (val s = state) {
                UpdateState.Idle -> Button(onClick = vm::check, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
                UpdateState.Checking -> Busy("Asking GitHub for the release list…")
                is UpdateState.UpToDate -> {
                    Card { Text("Up to date" + (s.latest?.let { ": $it is the newest" } ?: ""), color = StatusColors.ok) }
                    OutlinedButton(onClick = vm::check, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
                }
                is UpdateState.Available -> {
                    UpdateCard(s.update)
                    Button(onClick = { vm.download(s.update) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download ${formatBytes(s.update.apk.size)}")
                    }
                }
                is UpdateState.Downloading -> {
                    UpdateCard(s.update)
                    LinearProgressIndicator(progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth(), color = accent)
                    Text("${formatBytes(s.done)} of ${formatBytes(s.total)}", fontFamily = FontFamily.Monospace, color = GlassColors.dim)
                    OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
                }
                is UpdateState.Verifying -> Busy("Checking the download against the installed Tunnels…")
                is UpdateState.Ready -> {
                    UpdateCard(s.update)
                    Card {
                        Text("Verified", style = MaterialTheme.typography.titleSmall, color = StatusColors.ok)
                        s.checks.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (!canInstall) {
                        Notice(
                            "Allow Tunnels to install apps",
                            "Android asks once: Install unknown apps → Tunnels → Allow.",
                            StatusColors.warn,
                        ) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                            }) { Text("Open setting") }
                        }
                    }
                    Button(onClick = { vm.install(s) }, enabled = canInstall, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
                    Text(
                        "Android asks you to confirm, then closes Tunnels to update it. Your snapshots and findings stay.",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                    TextButton(onClick = vm::reset) { Text("Not now") }
                }
                is UpdateState.Installing -> Busy(if (s.awaitingUser) "Confirm the update in the system dialog…" else "Handing ${s.update.label} to Android…")
                is UpdateState.Done -> Card { Text(s.message, color = StatusColors.ok) }
                is UpdateState.Failed -> {
                    Notice(s.title, s.detail, StatusColors.blocker) {}
                    Button(onClick = vm::check, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }

            Text(
                "Tunnels goes online only from this screen, only when you tap Check or Download, and only to GitHub: " +
                    "api.github.com for the release list and GitHub's download host for the file. It sends nothing about you or this " +
                    "phone; a saved token goes to api.github.com only. Before installing, the file must be this app, newer, signed " +
                    "with the same key as the installed Tunnels and, when the release publishes one, match its SHA-256.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The read-only token a private repository needs, with how to make one. */
@Composable
private fun TokenCard(hint: String?, save: (String) -> Boolean, forget: () -> Unit, highlight: Boolean) {
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val tint = if (highlight) StatusColors.warn else Color.White
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GitHub token", style = MaterialTheme.typography.titleSmall)
                    Text(
                        hint?.let { "Saved: $it · forgotten after 30 days without a check" }
                            ?: "Needed while the repository is private",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                if (hint != null) TextButton(onClick = forget) { Text("Forget") }
                TextButton(onClick = { open = !open }) { Text(if (open) "Close" else if (hint == null) "Add" else "Replace") }
            }
            if (open || (highlight && hint == null)) {
                Text(
                    "github.com → Settings → Developer settings → Fine-grained tokens → Generate new token. Repository access: " +
                        "only ${UpdateSource.OWNER}/${UpdateSource.REPO}. Permissions: Contents, read-only. Copy it and paste it here. " +
                        "It is kept in Tunnels' encrypted store and sent to api.github.com only.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; rejected = false },
                    label = { Text("github_pat_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = rejected,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rejected) Text("That does not look like a token.", style = MaterialTheme.typography.bodySmall, color = StatusColors.blocker)
                Button(onClick = {
                    if (save(input)) {
                        input = ""
                        open = false
                    } else rejected = true
                }, enabled = input.isNotBlank()) { Text("Save token") }
            }
        }
    }
}

@Composable
private fun UpdateCard(update: Update) {
    Card {
        Text("${update.label} is available", style = MaterialTheme.typography.titleSmall, color = accent)
        Line("file", "${update.apk.name} · ${formatBytes(update.apk.size)}")
        update.release.publishedAt?.let { Line("published", it.replace('T', ' ').removeSuffix("Z") + " UTC") }
        val notes = update.release.body.substringBefore("\n---").trim()
        if (notes.isNotEmpty()) Text(notes.take(1_200), style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
    }
}

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
