package io.github.stronghorse44.tunnels.installer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.install.Check
import io.github.stronghorse44.tunnels.install.CheckLevel
import io.github.stronghorse44.tunnels.install.InstallKind
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.store.EventEntity
import java.text.DateFormat
import java.util.Date

private val PICK_TYPES = arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")

@Composable
fun InstallScreen(vm: InstallViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val canInstall by vm.canInstall.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }

    LaunchedEffect(confirm) {
        confirm?.let {
            context.startActivity(it)
            vm.confirmShown()
        }
    }

    val uninstall: (String) -> Unit = { pkg ->
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
    }

    TunnelScaffold("Installer", MetroLine.FILES, onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                InstallState.Idle -> IdleContent(history, onPick = { picker.launch(PICK_TYPES) })
                is InstallState.Working -> Centered { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(s.message) }
                is InstallState.Ready -> ReadyContent(
                    s, canInstall,
                    onInstall = vm::install,
                    onGrant = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    },
                    onUninstall = { uninstall(s.info.facts.packageName) },
                    onCancel = vm::reset,
                )
                is InstallState.Installing -> {
                    AppHeader(s.info)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text(if (s.awaitingUser) "Confirm in the system dialog…" else "Writing ${formatBytes(s.info.totalBytes)}…")
                }
                is InstallState.Done -> DoneContent(
                    s,
                    onOpen = {
                        context.packageManager.getLaunchIntentForPackage(s.info.facts.packageName)?.let(context::startActivity)
                    },
                    onUninstall = { uninstall(s.info.facts.packageName) },
                    onFinish = vm::reset,
                )
                is InstallState.Failed -> {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, color = StatusColors.blocker)
                    Text(s.detail)
                    Button(onClick = { picker.launch(PICK_TYPES) }) { Text("Pick another file") }
                }
            }
        }
    }
}

@Composable
private fun IdleContent(history: List<EventEntity>, onPick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.FILES)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Install an app", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick an APK or a bundle (.apks, .xapk, .apkm). Tunnels shows what's inside and checks it against " +
                    "the installed copy before anything is installed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onPick) { Text("Pick a file") }
            Text(
                "Make it your default: tap a downloaded APK, choose \"Tunnels Installer\", then \"Always\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (history.isNotEmpty()) {
        Text("Last 30 days", style = MaterialTheme.typography.titleSmall)
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        history.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (e.kind == "INSTALLED") StatusColors.ok else StatusColors.blocker)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                    Text("${e.subject} · ${fmt.format(Date(e.at))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(info: ApkInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = info.icon
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(info.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(info.facts.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadyContent(
    s: InstallState.Ready,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onGrant: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val f = s.info.facts
    val newVersion = "${f.versionName ?: "?"} (${f.versionCode})"
    val installed = s.info.installed
    AppHeader(s.info)
    Text(
        when (s.report.kind) {
            InstallKind.NEW -> "New install · $newVersion"
            InstallKind.UPDATE -> "Update · ${installed?.versionName ?: installed?.versionCode} → $newVersion"
            InstallKind.REINSTALL -> "Reinstall · same version $newVersion"
            InstallKind.DOWNGRADE -> "Downgrade · ${installed?.versionName ?: installed?.versionCode} → $newVersion"
        },
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        "${formatBytes(s.info.totalBytes)} · ${if (s.info.apkCount > 1) "${s.info.apkCount} APKs" else "1 APK"} · " +
            "min API ${f.minSdk} · target API ${f.targetSdk}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!canInstall) {
        GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.FILES)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow Tunnels to install apps", style = MaterialTheme.typography.titleSmall)
                Text("Android needs your OK once before Tunnels can hand APKs to the system installer.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onGrant) { Text("Open setting") }
            }
        }
    }

    HorizontalDivider()
    s.report.checks.forEach { CheckRow(it) }
    if (s.report.checks.isEmpty()) CheckRow(Check(CheckLevel.OK, "No problems found", ""))

    f.signerSha256.firstOrNull()?.let {
        Text("Signing certificate SHA-256", style = MaterialTheme.typography.labelMedium)
        Text(shortFingerprint(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }

    var showPerms by remember { mutableStateOf(false) }
    if (f.requestedPermissions.isNotEmpty()) {
        TextButton(onClick = { showPerms = !showPerms }) {
            Text("${if (showPerms) "Hide" else "Show"} ${f.requestedPermissions.size} requested permissions")
        }
        if (showPerms) {
            f.requestedPermissions.sorted().forEach {
                Text(it.removePrefix("android.permission."), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (s.pkg.droppedSplits.isNotEmpty()) {
        Text(
            "Skipped ${s.pkg.droppedSplits.size} parts for other processors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onInstall, enabled = canInstall && !s.report.blocked) {
            Text(if (s.report.kind == InstallKind.NEW) "Install" else "Update")
        }
        if (installed != null && s.report.blocked) {
            OutlinedButton(onClick = onUninstall) { Text("Uninstall installed copy") }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun DoneContent(s: InstallState.Done, onOpen: () -> Unit, onUninstall: () -> Unit, onFinish: () -> Unit) {
    AppHeader(s.info)
    val failure = s.failure
    if (failure == null) {
        Text("Installed", style = MaterialTheme.typography.titleLarge, color = StatusColors.ok)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onFinish) { Text("Done") }
        }
    } else {
        Text(failure.title, style = MaterialTheme.typography.titleLarge, color = StatusColors.blocker)
        Text(failure.detail)
        val raw = s.rawMessage
        if (raw != null) {
            Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failure.code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE" || failure.code == "INSTALL_FAILED_VERSION_DOWNGRADE") {
                OutlinedButton(onClick = onUninstall) { Text("Uninstall installed copy") }
            }
            TextButton(onClick = onFinish) { Text("Close") }
        }
    }
}

@Composable
private fun CheckRow(check: Check) {
    val color = when (check.level) {
        CheckLevel.OK -> StatusColors.ok
        CheckLevel.INFO -> StatusColors.info
        CheckLevel.WARN -> StatusColors.warn
        CheckLevel.BLOCKER -> StatusColors.blocker
    }
    Row {
        Dot(color, Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(check.title, style = MaterialTheme.typography.bodyLarge, color = if (check.level == CheckLevel.BLOCKER) color else Color.Unspecified)
            if (check.detail.isNotBlank()) {
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}
