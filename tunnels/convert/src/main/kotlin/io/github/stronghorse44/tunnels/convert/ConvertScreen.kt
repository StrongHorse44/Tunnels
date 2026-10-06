package io.github.stronghorse44.tunnels.convert

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.model.MetroLine

/** What the converter offers, shown before a file is picked. */
private val menu = listOf(
    "RTF → PDF or text",
    "Word (.docx) → PDF or text",
    "OpenDocument (.odt) → PDF or text",
    "Plain text → PDF",
    "PDF → PNG or JPEG images, one per page",
    "PNG, JPEG, WebP, HEIC → PDF, or to another image format",
)

@Composable
fun ConvertScreen(vm: ConvertViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> tree?.let(vm::convertToFolder) }
    // One save picker per output type: the contract fixes the MIME type when it's created.
    val savers = OutputFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> uri?.let(vm::convertTo) }
    }
    val pickFile = { picker.launch(arrayOf("*/*")) }

    TunnelScaffold("Convert", MetroLine.FILES, onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            when (val s = state) {
                ConvertState.Idle -> {
                    Spacer(Modifier.height(16.dp))
                    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.FILES)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Convert a file", style = MaterialTheme.typography.titleMedium)
                            menu.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                            Button(onClick = pickFile) { Text("Pick a file") }
                            Text(
                                "Everything happens on this phone; nothing is uploaded. You can also share a file to \"Tunnels Convert\", or open RTF and Word files with it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is ConvertState.Working -> Busy(s.message)
                is ConvertState.Ready -> {
                    Spacer(Modifier.height(16.dp))
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.format.label} · ${formatBytes(s.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (s.targets.isEmpty()) {
                        Text(s.refusal ?: "This file can't be converted.", color = StatusColors.warn)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = pickFile) { Text("Pick another file") }
                    } else {
                        Text("Convert to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.targets.forEach { target ->
                                val folder = Conversions.writesFolder(s.format, target)
                                Button(
                                    onClick = {
                                        vm.choose(target)
                                        if (folder) folderPicker.launch(null) else savers.getValue(target).launch(Conversions.outputName(s.name, target))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(targetLabel(s.format, target)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(note(s.format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = pickFile) { Text("Pick another file") }
                    }
                }
                is ConvertState.Converting -> {
                    Spacer(Modifier.height(24.dp))
                    Text("Converting ${s.name} to ${s.target.label}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    if (s.total > 0) {
                        LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Page ${s.done} of ${s.total}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (s.done > 0) Text("${s.done} ${if (s.done == 1) "page" else "pages"}")
                    }
                    TextButton(onClick = vm::cancel) { Text("Cancel") }
                }
                is ConvertState.Done -> {
                    Spacer(Modifier.height(24.dp))
                    Text("Converted", style = MaterialTheme.typography.titleLarge, color = StatusColors.ok)
                    Text(listOf(s.target.label, s.detail).filter { it.isNotEmpty() }.joinToString(" · "))
                    Spacer(Modifier.height(8.dp))
                    Text("Saved as", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.outputName, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { open(context, s) }) { Text(if (s.isFolder) "Open folder" else "Open") }
                        OutlinedButton(onClick = vm::backToFile) { Text("Convert again") }
                        TextButton(onClick = onBack) { Text("Done") }
                    }
                }
                is ConvertState.Failed -> {
                    Spacer(Modifier.height(24.dp))
                    Text(s.title, style = MaterialTheme.typography.titleLarge, color = StatusColors.blocker)
                    Text(s.detail)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = pickFile) { Text("Pick a file") }
                        OutlinedButton(onClick = vm::backToFile) { Text("Back") }
                    }
                }
            }
        }
    }
}

private fun targetLabel(from: InputFormat, to: OutputFormat): String = when {
    Conversions.writesFolder(from, to) -> "To ${to.label} (one per page)…"
    to == OutputFormat.PNG || to == OutputFormat.JPEG -> "To ${to.label.removeSuffix(" images")} image…"
    else -> "To ${to.label}…"
}

/** What the conversion keeps and drops, said before the user commits. */
private fun note(format: InputFormat): String = when (format) {
    InputFormat.RTF, InputFormat.DOCX, InputFormat.ODT ->
        "Keeps text, headings, lists, tables (as rows), bold, italic, underline, sizes and alignment. Pictures, headers, footers, footnotes and comments are left out."
    InputFormat.TXT -> "Laid out in a fixed-width font on A4 pages."
    InputFormat.PDF -> "Pages are rendered at 150 dpi into a new folder you pick. Password-protected PDFs can't be opened."
    else -> "One A4 page, image centred and never enlarged. Photos are turned the right way up."
}

@Composable
private fun Busy(message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message)
    }
}

/** Opens the converted file in a viewer, or its folder in Files. */
private fun open(context: Context, done: ConvertState.Done) {
    val type = if (done.isFolder) DocumentsContract.Document.MIME_TYPE_DIR else done.target.mime
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(done.uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, if (done.isFolder) "Open the Files app to find it." else "No app here opens ${done.target.label} files.", Toast.LENGTH_LONG).show()
    }
}
