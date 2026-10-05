package io.github.stronghorse44.tunnels.backups

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.Choice
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Backups screen: the export folder, how old is too old, one row per app with its newest bundle date, and the
 * restore drill. Every figure is read back from the last scan's observations ([BackupView]); a change of folder,
 * threshold, watched apps or drill date starts a scan, so the findings follow at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupsPanel(module: BackupsTunnel, state: TunnelScreenState, screen: TunnelScreenActions) {
    val line = LineColors.of(MetroLine.FILES)
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(BackupSettings()) }
    var folder by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var datePicker by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, state.lastScan) {
        settings = module.config.settings()
        folder = module.config.folder()
    }
    val view = remember(state.observations) { BackupView.from(state.observations) }

    fun change(update: suspend () -> Unit) {
        scope.launch {
            update()
            reload++
            screen.scan()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            change {
                problem = runCatching { module.chooseFolder(uri) }.exceptionOrNull()?.let { "Android did not keep access to that folder. Choose it again." }
            }
        }
    }

    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Backups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick a folder on the phone's own storage: a cloud-backed folder may download whole files just to read a header, in the background check too. " +
                    "Reads only the plaintext header (the first 200 bytes or so) of each .fwx or .tsnap file in the folder you pick. " +
                    "It never asks for a passphrase and never reads the encrypted part. A header's date is not checked without the " +
                    "passphrase, so this is a reminder, not proof: only a restore drill proves a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )

            // Folder
            Label("Export folder")
            Text(
                when {
                    folder == null -> "No folder chosen yet."
                    else -> folderLabel(folder!!)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (folder != null && view.folder == FolderState.LOST) {
                Text(
                    "Tunnels cannot read this folder now (it was moved or deleted, or access was removed). Choose it again. " +
                        "Until then no app is judged; a warning says so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn,
                )
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) { Text(if (folder == null) "Choose folder" else "Change folder") }
                if (folder != null) {
                    TextButton(onClick = { change { module.clearFolder() } }) { Text("Forget folder") }
                }
            }
            if (view.folder == FolderState.OK) {
                val extras = buildList {
                    add("${view.bundles} bundle file${if (view.bundles == 1) "" else "s"}")
                    if (view.unreadable > 0) add("${view.unreadable} not readable as a bundle")
                    if (view.skipped > 0) add("${view.skipped} other file${if (view.skipped == 1) "" else "s"} left unopened")
                }
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                if (view.truncated) {
                    Text(
                        "The folder has more files than one scan reads (${FolderScanner.MAX_HEADERS} headers, ${FolderScanner.MAX_PER_APP} per app name, newest names first). " +
                            "An app whose newest files were read is still judged; one that may have unread files is not. Move old exports into another folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
                if (view.faults > 0) {
                    Text(
                        "${view.faults} file${if (view.faults == 1) "" else "s"} or folder${if (view.faults == 1) "" else "s"} could not be opened. " +
                            "An app that one of them may belong to is not judged. Pick a folder on the phone itself, then scan again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
            }

            // Threshold
            Label("Tell me when an app's newest bundle is older than")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupSettings.THRESHOLDS.forEach { days ->
                    Choice(if (days == 1) "1 day" else "$days days", settings.thresholdDays == days, line, { change { module.config.update { it.copy(thresholdDays = days) } } })
                }
            }

            // Apps
            Label("Apps")
            view.apps.forEach { row ->
                // Without a readable folder the scan describes no app, so the switch shows what the settings say
                // (switched on by you, or seen in the folder before; both survive a change of folder).
                val readable = view.folder == FolderState.OK
                val shown = if (readable) row else row.copy(tracked = settings.isTracked(row.app.id))
                AppRowView(shown, readable, module, onWatch = { on -> change { module.config.update { it.withTracked(row.app.id, on) } } })
            }
            if (view.otherFiles > 0) {
                Text(
                    "other · ${view.otherFiles} bundle file${if (view.otherFiles == 1) "" else "s"} from apps this version does not know" +
                        (view.otherNewestMs?.let { " · newest header ${BackupRules.dateOf(it, module.zoneId)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
            }

            // Restore drill
            Label("Restore drill")
            Text(
                when {
                    settings.drill == null -> "No drill date set. A drill is importing one export into its app and checking the data."
                    else -> "Last drill ${settings.drill} · ${Freshness.drillAgeDays(settings.drill!!, module.today())} days ago" +
                        if (view.drill.status == DrillStatus.DUE) " · due" else ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (view.drill.status == DrillStatus.DUE) StatusColors.warn else GlassColors.text,
            )
            Text(
                "You get a reminder ${BackupSettings.DRILL_EVERY_DAYS} days after the date you set.",
                style = MaterialTheme.typography.labelSmall,
                color = GlassColors.dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { datePicker = true }) { Text("Set date") }
                OutlinedButton(onClick = { change { module.config.update { it.copy(drill = module.today()) } } }) { Text("Today") }
                if (settings.drill != null) TextButton(onClick = { change { module.config.update { it.copy(drill = null) } } }) { Text("Clear") }
            }
        }
    }

    if (datePicker) {
        DrillDateDialog(
            initial = settings.drill ?: module.today(),
            today = module.today(),
            onDismiss = { datePicker = false },
            onPick = { date ->
                datePicker = false
                change { module.config.update { it.copy(drill = date) } }
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun AppRowView(row: AppRow, folderReadable: Boolean, module: BackupsTunnel, onWatch: (Boolean) -> Unit) {
    val (detail, color) = describe(row, folderReadable, module)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.app.name, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Switch(checked = row.tracked, onCheckedChange = onWatch)
    }
}

private fun describe(row: AppRow, folderReadable: Boolean, module: BackupsTunnel): Pair<String, Color> {
    if (!folderReadable) return (if (row.tracked) "watched" else "not watched") to GlassColors.dim
    val newest = row.newestMs?.let { BackupRules.dateOf(it, module.zoneId) }
    val files = "${row.files} file${if (row.files == 1) "" else "s"}" +
        if (row.items > 0) " + ${row.items} item bundle${if (row.items == 1) "" else "s"}" else ""
    val what = if (row.fromFileDate) "old format, file date" else "header says"
    return when (row.status) {
        AppStatus.FRESH -> "$what $newest · ${row.ageDays} days ago · $files" to StatusColors.ok
        AppStatus.STALE -> "$what $newest · ${row.ageDays} days ago · $files · older than your limit" to StatusColors.warn
        AppStatus.MISSING -> "no bundle in the folder" to StatusColors.warn
        AppStatus.SUSPICIOUS -> "dated in the future, not counted · $files" to StatusColors.warn
        AppStatus.UNKNOWN_DATE -> "present, date unknown · $files" to GlassColors.dim
        AppStatus.NO_MANIFEST -> "${row.items} item bundle${if (row.items == 1) "" else "s"} but no manifest: incomplete, can't be imported" to StatusColors.warn
        AppStatus.INCOMPLETE -> "not judged: files that may be its own were not read" to StatusColors.warn
        AppStatus.UNREADABLE -> "not judged: a ${row.app.name} file could not be read" to StatusColors.warn
        AppStatus.UNTRACKED -> (if (newest != null) "$what $newest · ${row.ageDays} days ago · not watched" else "not watched") to GlassColors.dim
        null -> "no bundle seen · not watched" to GlassColors.dim
    }
}

/** The folder's name as the picker showed it, without the volume prefix. */
private fun folderLabel(treeUri: String): String =
    runCatching { Uri.parse(treeUri).lastPathSegment?.substringAfter(':')?.ifEmpty { null } }.getOrNull() ?: "chosen folder"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrillDateDialog(initial: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            // A drill that has happened: no future dates.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = { pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
