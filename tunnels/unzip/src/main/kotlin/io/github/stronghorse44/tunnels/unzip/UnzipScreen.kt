package io.github.stronghorse44.tunnels.unzip

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.archive.ArchiveEntry
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.install.PackageShape
import io.github.stronghorse44.tunnels.installer.InstallActivity
import io.github.stronghorse44.tunnels.model.Stratum

@Composable
fun UnzipScreen(vm: UnzipViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> tree?.let(vm::extractTo) }

    LaunchedEffect(nav) {
        when (val n = nav) {
            is UnzipNav.Install -> context.startActivity(InstallActivity.stagedIntent(context, n.file))
            null -> Unit
        }
        if (nav != null) vm.navHandled()
    }

    TunnelScaffold("Unzip", Stratum.TOPSOIL, onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (val s = state) {
                UnzipState.Idle -> {
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Open an archive", style = MaterialTheme.typography.titleMedium)
                            Text("zip (including password-protected), 7z, tar, tar.gz, tar.xz, tar.bz2, gz, xz and bz2.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick an archive") }
                            Text(
                                "You can also open archives from Files or your browser with \"Tunnels Unzip\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is UnzipState.Working -> Busy(s.message)
                is UnzipState.NeedsPassword -> {
                    Busy("Waiting for password…")
                    PasswordDialog(s, onSubmit = vm::submitPassword, onDismiss = onBack)
                }
                is UnzipState.Listing -> ListingContent(
                    s,
                    onToggle = vm::toggle,
                    onSelectAll = vm::selectAll,
                    onExtract = { folderPicker.launch(null) },
                    onInstallEntry = vm::installEntry,
                    onInstallBundle = vm::installBundle,
                )
                is UnzipState.Extracting -> {
                    Spacer(Modifier.height(24.dp))
                    Text("Extracting ${s.name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    if (s.bytesTotal > 0) {
                        LinearProgressIndicator(progress = { (s.bytesDone.toFloat() / s.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${formatBytes(s.bytesDone)}${if (s.bytesTotal > 0) " of ${formatBytes(s.bytesTotal)}" else ""}")
                    Text(s.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = vm::cancel) { Text("Cancel") }
                }
                is UnzipState.Done -> {
                    Spacer(Modifier.height(24.dp))
                    Text("Extracted", style = MaterialTheme.typography.titleLarge, color = StatusColors.ok)
                    Text("${s.result.files} files · ${formatBytes(s.result.bytes)} into folder \"${s.folder}\"")
                    if (s.result.skipped.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Skipped ${s.result.skipped.size} for safety:", color = StatusColors.warn)
                        s.result.skipped.take(20).forEach {
                            Text("${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::backToListing) { Text("Back to contents") }
                        TextButton(onClick = onBack) { Text("Done") }
                    }
                }
                is UnzipState.Failed -> {
                    Spacer(Modifier.height(24.dp))
                    Text(s.title, style = MaterialTheme.typography.titleLarge, color = StatusColors.blocker)
                    Text(s.detail)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick another archive") }
                }
            }
        }
    }
}

@Composable
private fun ListingContent(
    s: UnzipState.Listing,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onExtract: () -> Unit,
    onInstallEntry: (ArchiveEntry) -> Unit,
    onInstallBundle: () -> Unit,
) {
    val files = s.entries.filterNot { it.isDirectory }
    val totalSize = files.sumOf { it.size.coerceAtLeast(0) }
    Spacer(Modifier.height(12.dp))
    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
        "${s.format.label} · ${files.size} files · ${formatBytes(totalSize)}${if (s.entries.any { it.encrypted }) " · encrypted" else ""}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (s.shape != PackageShape.NOT_AN_APP) {
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.shape == PackageShape.BUNDLE) "This is an app bundle." else "This is an APK.",
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onInstallBundle) { Text("Install") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val allSelected = s.selected.size == files.size && files.isNotEmpty()
        Checkbox(checked = allSelected, onCheckedChange = { onSelectAll(it) })
        Text("${s.selected.size} selected", modifier = Modifier.weight(1f))
        Button(onClick = onExtract, enabled = s.selected.isNotEmpty()) { Text("Extract to…") }
    }
    HorizontalDivider()
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.index }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(e.index) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = e.index in s.selected, onCheckedChange = { onToggle(e.index) })
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val dir = e.path.removeSuffix(e.name).trimEnd('/')
                    Text(
                        listOfNotNull(dir.ifEmpty { null }, formatBytes(e.size), if (e.encrypted) "encrypted" else null, if (e.isSymlink) "link, skipped" else null)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (e.name.endsWith(".apk", ignoreCase = true) && s.shape != PackageShape.BUNDLE) {
                    TextButton(onClick = { onInstallEntry(e) }) { Text("Install") }
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(s: UnzipState.NeedsPassword, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password") },
        text = {
            Column {
                Text(if (s.wrong) "That password didn't work. Try again." else "${s.name} is encrypted.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(value) }, enabled = value.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Busy(message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message)
    }
}
