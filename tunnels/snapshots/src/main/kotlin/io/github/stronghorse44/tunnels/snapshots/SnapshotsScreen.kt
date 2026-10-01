package io.github.stronghorse44.tunnels.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.runtime.snapshotRetention
import java.text.DateFormat
import java.util.Date

private val Mono = FontFamily.Monospace
private const val MIN_PASSWORD = 8
private val IMPORT_TYPES = arrayOf("application/octet-stream", "*/*")

@Composable
fun SnapshotsScreen(vm: SnapshotsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val line = MetroLine.SYSTEM
    val tint = LineColors.of(line)
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportTo(uri) else vm.cancelExport()
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pickImport) }
    var askExportPassword by rememberSaveable { mutableStateOf(false) }

    TunnelScaffold("Snapshots", line, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item("take") { TakePanel(state, tint, vm::takeSnapshot) }

                item("history") { SectionTitle("History", state.snapshots.size) }
                if (state.snapshots.isEmpty()) {
                    item("history-empty") {
                        Text(
                            if (state.ready) "No snapshots yet. Take one above, or import an export." else "Opening the store…",
                            color = GlassColors.dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                } else {
                    item("history-hint") {
                        Text(
                            if (state.selected.size == 1) "Tap a second snapshot to compare." else "Tap two snapshots to see what changed between them.",
                            color = GlassColors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
                items(state.snapshots, key = { "snap-${it.id}" }) { row ->
                    val badge = when {
                        row.id == state.diff?.fromId -> "A"
                        row.id == state.diff?.toId -> "B"
                        row.id in state.selected -> "•"
                        else -> null
                    }
                    SnapshotCard(row, badge, fmt, tint, onSelect = { vm.toggleSelect(row.id) }, onPin = { vm.setPinned(row.id, !row.pinned) })
                }

                val diff = state.diff
                if (diff != null) {
                    item("diff-header") { DiffHeader(diff, fmt, tint, vm::clearSelection) }
                    if (diff.report.isEmpty) {
                        item("diff-empty") { Text("No differences between these two snapshots.", color = StatusColors.ok, modifier = Modifier.padding(horizontal = 6.dp)) }
                    }
                    for (t in diff.report.tunnels) {
                        item("diff-t-${t.tunnelId}") { TunnelDiffTitle(t, tint) }
                        items(t.subjects, key = { "diff-s-${t.tunnelId}-${it.subject}" }) { s -> SubjectDiffCard(s) }
                    }
                    if (diff.report.truncatedSubjects > 0) {
                        item("diff-more") {
                            Text(
                                "${diff.report.truncatedSubjects} more subjects changed but aren't listed here. Export both snapshots to see everything.",
                                color = GlassColors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }

                item("transfer") {
                    TransferPanel(
                        state, tint,
                        onExport = { askExportPassword = true },
                        onImport = { openDocument.launch(IMPORT_TYPES) },
                    )
                }
                item("settings") { SettingsPanel(state, tint, vm::setAppLock) }
                item("bottom") { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
            }

            val note = state.error ?: state.message
            if (note != null) {
                Snackbar(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    containerColor = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = GlassColors.text,
                    action = { TextButton(onClick = vm::clearMessage) { Text("OK", color = tint) } },
                ) { Text(note) }
            }
        }
    }

    if (askExportPassword) {
        PasswordDialog(
            title = "Protect the export",
            body = "The file is encrypted with this password. Nobody can read it without it, including Tunnels: there is no recovery.",
            confirm = true,
            onConfirm = { pw ->
                askExportPassword = false
                vm.stageExport(pw)
                createDocument.launch(exportFileName())
            },
            onDismiss = { askExportPassword = false },
        )
    }
    if (state.exportNeedsPassword) {
        PasswordDialog(
            title = "Enter the export password again",
            body = "The screen was recreated while you picked the destination, so the password has to be typed again.",
            confirm = true,
            onConfirm = vm::exportWithPassword,
            onDismiss = vm::cancelExport,
        )
    }
    if (state.importPending && state.busy == null) {
        PasswordDialog(
            title = "Import snapshots",
            body = "Enter the password the export was protected with. Imported snapshots are pinned and their findings are re-derived on the next scan.",
            confirm = false,
            error = state.importError,
            onConfirm = vm::importWithPassword,
            onDismiss = vm::cancelImport,
        )
    }
}

@Composable
private fun TakePanel(state: SnapshotsState, tint: Color, onTake: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Take snapshot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Scans every tunnel at once and stores one snapshot of what it saw. Findings update as they would from each tunnel's own scan.",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onTake, enabled = state.ready && !state.scan.running && state.busy == null) {
                    Text(if (state.scan.running) "Scanning…" else "Take")
                }
            }
            Text(
                when (state.tunnelCount) {
                    0 -> "No tunnels are registered in this build yet; a snapshot will be empty but still recorded."
                    1 -> "1 tunnel registered"
                    else -> "${state.tunnelCount} tunnels registered"
                },
                fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim,
            )
            if (state.scan.running) {
                LinearProgressIndicator(
                    progress = { if (state.scan.total == 0) 0f else state.scan.done.toFloat() / state.scan.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(state.scan.label.ifEmpty { "Starting…" }, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            }
            val r = state.lastResult
            if (r != null && !state.scan.running) {
                Text(
                    "${r.observations} observations · ${r.newFindings} new findings · ${r.clearedFindings} cleared",
                    fontFamily = Mono, fontSize = 11.sp, color = tint,
                )
                state.snapshots.firstOrNull { it.id == r.snapshotId }?.let {
                    Text("Snapshot ${fmt.format(Date(it.takenAt))}", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
                }
                r.failures.forEach { (tunnelId, why) ->
                    Text("${TunnelCatalog.byId(tunnelId)?.title ?: tunnelId}: $why", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                }
            }
        }
    }
}

@Composable
private fun SnapshotCard(row: SnapshotRow, badge: String?, fmt: DateFormat, tint: Color, onSelect: () -> Unit, onPin: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = if (badge != null) tint else Color.White) {
        Row(Modifier.clickable(onClick = onSelect).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (badge != null) tint else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge ?: "", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (badge != null) GlassColors.void else GlassColors.dim)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmt.format(Date(row.takenAt)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${row.tunnelIds.size} ${if (row.tunnelIds.size == 1) "tunnel" else "tunnels"} · ${row.observations} observations",
                    fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim,
                )
                if (row.tunnelIds.isNotEmpty()) {
                    Text(
                        row.tunnelIds.joinToString(" ") { TunnelCatalog.byId(it)?.title?.lowercase() ?: it },
                        fontFamily = Mono, fontSize = 10.sp, color = tint.copy(alpha = 0.8f), maxLines = 2,
                    )
                }
            }
            TextButton(onClick = onPin) {
                Text(if (row.pinned) "★ pinned" else "☆ pin", fontFamily = Mono, fontSize = 11.sp, color = if (row.pinned) tint else GlassColors.dim)
            }
        }
    }
}

@Composable
private fun DiffHeader(diff: DiffView, fmt: DateFormat, tint: Color, onClear: () -> Unit) {
    val c = diff.report.counts
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Changes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear", color = GlassColors.dim) }
            }
            Text("A  ${fmt.format(Date(diff.fromAt))}", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
            Text("B  ${fmt.format(Date(diff.toAt))}", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CountChip("+${c.added}", "added", StatusColors.ok)
                CountChip("−${c.removed}", "removed", StatusColors.blocker)
                CountChip("~${c.changed}", "changed", StatusColors.warn)
            }
        }
    }
}

@Composable
private fun CountChip(number: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
    }
}

@Composable
private fun TunnelDiffTitle(t: TunnelDiff, tint: Color) {
    Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(TunnelCatalog.byId(t.tunnelId)?.title ?: t.tunnelId, fontFamily = Mono, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.weight(1f))
        Text("+${t.counts.added} −${t.counts.removed} ~${t.counts.changed}", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
    }
}

@Composable
private fun SubjectDiffCard(s: SubjectDiff) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.subject, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${s.counts.total}", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
            }
            s.entries.forEach { e -> DiffRow(e) }
            if (s.hidden > 0) Text("… ${s.hidden} more", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
        }
    }
}

@Composable
private fun DiffRow(e: DiffEntry) {
    val (symbol, color) = when (e) {
        is DiffEntry.Added -> "+" to StatusColors.ok
        is DiffEntry.Removed -> "−" to StatusColors.blocker
        is DiffEntry.Changed -> "~" to StatusColors.warn
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(symbol, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = color, modifier = Modifier.width(14.dp))
        Text(e.key.key, fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.weight(0.42f))
        Column(Modifier.weight(0.58f)) {
            when (e) {
                is DiffEntry.Added -> Text(e.observation.value, fontFamily = Mono, fontSize = 11.sp, color = color)
                is DiffEntry.Removed -> Text(e.observation.value, fontFamily = Mono, fontSize = 11.sp, color = color)
                is DiffEntry.Changed -> {
                    Text(e.before.value, fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
                    Text("→ ${e.after.value}", fontFamily = Mono, fontSize = 11.sp, color = color)
                }
            }
        }
    }
}

@Composable
private fun TransferPanel(state: SnapshotsState, tint: Color, onExport: () -> Unit, onImport: () -> Unit) {
    val idle = state.ready && state.busy == null && !state.scan.running
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Export and import", style = MaterialTheme.typography.titleMedium)
            Text(
                "Export writes every snapshot to a password-encrypted .tsnap file in a place you choose. Nothing leaves the device unless you move that file yourself. " +
                    "Import brings snapshots from such a file back, pinned, so retention keeps them.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, enabled = idle && state.snapshots.isNotEmpty()) { Text("Export") }
                OutlinedButton(onClick = onImport, enabled = idle) { Text("Import") }
            }
            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.busy, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            }
        }
    }
}

@Composable
private fun SettingsPanel(state: SnapshotsState, tint: Color, onLock: (Boolean) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.canLock) "Ask for your screen lock or fingerprint when Tunnels comes back after 30 seconds in the background."
                        else "Unavailable: set a screen lock (PIN, pattern or password) in Android Settings → Security first.",
                        style = MaterialTheme.typography.bodySmall, color = if (state.canLock) GlassColors.dim else StatusColors.warn,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.appLockEnabled && state.canLock, onCheckedChange = onLock, enabled = state.canLock)
            }
            Text(
                "Retention: the newest $snapshotRetention snapshots are kept, plus any you pin. Events expire after 30 days. " +
                    "Everything is stored encrypted" + (if (state.keyLevel.isNotEmpty()) " (key: ${state.keyLevel})" else "") + ".",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Text("$title · $count", fontFamily = Mono, fontWeight = FontWeight.Bold, color = GlassColors.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

/** Asks for a password; with [confirm] it must be typed twice and be at least [MIN_PASSWORD] characters. */
@Composable
private fun PasswordDialog(
    title: String,
    body: String,
    confirm: Boolean,
    error: String? = null,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = confirm && password.length < MIN_PASSWORD
    val mismatch = confirm && repeat != password
    val ok = password.isNotEmpty() && !tooShort && !mismatch
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirm) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text("Repeat password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = repeat.isNotEmpty() && mismatch,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (password.isNotEmpty() && tooShort) Text("At least $MIN_PASSWORD characters.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                    else if (repeat.isNotEmpty() && mismatch) Text("The passwords don't match.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                }
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = StatusColors.blocker)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(password.toCharArray()) }) { Text(if (confirm) "Continue" else "Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = GlassColors.dim) } },
    )
}
