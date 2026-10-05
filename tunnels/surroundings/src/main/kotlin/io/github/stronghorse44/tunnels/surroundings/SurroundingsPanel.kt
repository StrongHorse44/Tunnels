package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.ble.AppleFindMyFrame
import io.github.stronghorse44.tunnels.ble.CellLogText
import io.github.stronghorse44.tunnels.ble.FamilyFacts
import io.github.stronghorse44.tunnels.ble.FamilySummary
import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.IdentityFacts
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.ThreatSummary
import io.github.stronghorse44.tunnels.ble.TrackerGuides
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerVerdict
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val line: Color get() = LineColors.of(MetroLine.NETWORK)

/** Live scan readout, the cell logbook, the background-monitor switch with its opt-in text, the tracker section with per-identity detail, and the guide. */
@Composable
fun SurroundingsPanel(state: TunnelScreenState, actions: TunnelScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveCard(state)
        CellLogbookCard(state)
        MonitorCard(actions)
        TrackerSection(state, actions)
        GuideCard()
    }
}

private fun List<Observation>.fact(subject: String, key: String): String? = firstOrNull { it.subject == subject && it.key == key }?.value

@Composable
private fun LiveCard(state: TunnelScreenState) {
    val obs = state.observations
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Around you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.scan.running) {
                Text(state.scan.label.substringAfter(": ", state.scan.label), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = line)
            } else if (obs.isEmpty()) {
                Text(
                    "A scan listens for Bluetooth trackers for ${SurroundingsTunnel.BLE_WINDOW_MS / 1000} seconds, reads the Wi-Fi networks in " +
                        "range and the cell the phone is on. Only counts, names and kinds are kept: no addresses, no positions.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
            if (obs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Counter("Bluetooth", obs.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.DEVICES_TOTAL), "devices", obs.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.BLE_AVAILABLE))
                    Counter("Identities", obs.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.TRACKERS_TOTAL), "in 30 days", null)
                    Counter("Wi-Fi", obs.fact(SurroundingsKeys.WIFI_SUMMARY, SurroundingsKeys.WIFI_NETWORKS), "networks", obs.fact(SurroundingsKeys.WIFI_SUMMARY, SurroundingsKeys.WIFI_AVAILABLE))
                    Counter("Cell", obs.fact(SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_TYPE) ?: "—", obs.fact(SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_OPERATOR) ?: "", obs.fact(SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_AVAILABLE))
                }
                val twins = obs.count { it.key == SurroundingsKeys.WIFI_TWIN }
                val current = obs.firstOrNull { it.key == SurroundingsKeys.WIFI_CURRENT && it.value == "true" }?.subject
                Text(
                    listOfNotNull(
                        current?.let { "connected to \"$it\" (${obs.fact(it, SurroundingsKeys.WIFI_SECURITY) ?: "?"})" },
                        if (twins > 0) "$twins impostor suspect${if (twins == 1) "" else "s"}" else null,
                        obs.fact(SurroundingsKeys.CELL_SUMMARY, SurroundingsKeys.CELL_NEIGHBOURS)?.let { "$it neighbour cells" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
        }
    }
}

/**
 * The cell logbook: off until the user starts it, then what the last scan found at this place. Start and Clear are the
 * only controls; the row holds keyed hashes only and lasts until cleared (CLAUDE.md rule 3, hashed-set exception).
 */
@Composable
private fun CellLogbookCard(state: TunnelScreenState) {
    val context = LocalContext.current
    val logbook = remember { CellLogStore(context.applicationContext) }
    val row by remember { logbook.rowFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val facts = remember(state.observations) {
        state.observations.filter { it.subject == SurroundingsKeys.CELL_SUMMARY && it.key.startsWith("log:") }.associate { it.key to it.value }
    }
    val current = row ?: return
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cell logbook", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                CellLogText.line(current, facts),
                style = MaterialTheme.typography.bodySmall,
                color = if (current is CellLogText.Row.Unreadable) StatusColors.warn else GlassColors.dim,
            )
            if (current !is CellLogText.Row.Off) {
                Text(
                    "Keyed hashes only, never a position or a cell identity, and no times. It lasts until you clear it and is not exported.",
                    style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                )
            }
            if (current is CellLogText.Row.Off) {
                Button(onClick = { scope.launch { message = logbook.start() } }, enabled = !state.scan.running) { Text("Start the cell logbook") }
            } else {
                OutlinedButton(onClick = { confirm = true }) { Text("Clear the cell logbook") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear the cell logbook?") },
            text = { Text("It forgets every place and tower it learned and turns itself off. You can start it again; it then learns from zero.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { message = logbook.clear() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Counter(label: String, value: String?, unit: String, available: String?) {
    val off = available != null && available != SurroundingsKeys.AVAILABLE_YES
    Column {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim)
        Text(if (off) "—" else value ?: "—", style = MaterialTheme.typography.titleLarge, color = if (off) GlassColors.dim else line)
        Text(if (off) available!! else unit, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (off) StatusColors.warn else GlassColors.dim)
    }
}

@Composable
private fun MonitorCard(actions: TunnelScreenActions) {
    val context = LocalContext.current
    val monitor by MonitorService.state.collectAsStateWithLifecycle()
    var refusal by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var wasRunning by remember { mutableStateOf(monitor.running) }
    LaunchedEffect(monitor.running) {
        // When the monitor ends, re-scan so the cards pick up what it recorded.
        if (wasRunning && !monitor.running) actions.scan()
        wasRunning = monitor.running
        while (monitor.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) MonitorService.start(context)
        else refusal = "Background location was not allowed (\"Allow all the time\"), so the monitor cannot run while the screen is off."
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The notification is a courtesy; the monitor runs either way once background location is in.
        if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) MonitorService.start(context) else background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    fun turnOn() {
        refusal = null
        when {
            !has(Manifest.permission.POST_NOTIFICATIONS) -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> MonitorService.start(context)
        }
    }

    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (monitor.running) "On for ${SurroundingsFormat.elapsed(now - monitor.startedAt)} · ${SurroundingsFormat.monitorLine(monitor.windows, monitor.keysByState, monitor.lastCell, monitor.keysClose, monitor.keysFollowing)}"
                        else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (monitor.running) StatusColors.ok else GlassColors.dim,
                    )
                }
                Switch(checked = monitor.running, onCheckedChange = { on -> if (on) turnOn() else MonitorService.stop(context) })
            }
            if (!monitor.running) {
                Text(
                    "Separate opt-in. While on, Tunnels scans for Bluetooth trackers every ${MonitorService.BLE_INTERVAL_MS / 60_000} minutes and " +
                        "checks Wi-Fi and the cell every ${MonitorService.WIFI_CELL_INTERVAL_MS / 60_000}, so a tag that travels with you shows up " +
                        "across places and hours. It needs location \"all the time\": Android ties Bluetooth and cell scanning to it, and each scan " +
                        "notes whether you moved since the last one (only that, never a position). It shows a persistent notification you can stop it from, never starts by itself and stops after " +
                        "${MonitorService.MAX_DURATION_MS / 3_600_000} hours.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            } else {
                monitor.bleAvailable?.takeIf { it != SurroundingsKeys.AVAILABLE_YES }?.let {
                    Text("Bluetooth: $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                }
            }
            (refusal ?: monitor.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
        }
    }
}

/** A guide sheet: title and body. */
private data class Sheet(val title: String, val body: String)

private const val LABEL_IDENTIFY = "How to identify it"
private const val LABEL_DISABLE = "How to disable it"
private const val LABEL_REPORT = "Report it"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerSection(state: TunnelScreenState, actions: TunnelScreenActions) {
    val cards = remember(state.observations) { SurroundingsFormat.trackerCards(state.observations) }
    if (cards.isEmpty()) return
    val identities = remember(cards) { cards.flatMap { it.identities } }
    val unlisted = state.observations.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.TRACKERS_UNLISTED)?.toIntOrNull() ?: 0
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val worst = identities.maxOfOrNull { it.level } ?: FollowingLevel.NONE
    val summaryColor = when {
        worst == FollowingLevel.CRITICAL -> StatusColors.blocker
        worst == FollowingLevel.WARN -> StatusColors.warn
        // A separated identity close to following is worth a glance; a crowd of passers-by is not.
        identities.any { it.state == TrackerState.SEPARATED && it.close } -> StatusColors.warn
        else -> GlassColors.text
    }

    Text("Trackers · ${identities.size + unlisted}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GlassColors.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    GlassPanel(Modifier.fillMaxWidth(), tint = summaryColor) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ThreatSummary.line(identities, unlisted), style = MaterialTheme.typography.titleSmall, color = summaryColor)
            SurroundingsFormat.placeNote(state.observations.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.PLACE_AVAILABLE))?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
            MonitorReconcile(state, actions)
        }
    }
    cards.forEach { card -> TypeCard(card, state, actions) { sheet = it } }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(s.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The monitor counts identities live since it started; the section shows the last scan. Both use the
 * same words, and this line says which is which and how to bring them together.
 */
@Composable
private fun MonitorReconcile(state: TunnelScreenState, actions: TunnelScreenActions) {
    val monitor by MonitorService.state.collectAsStateWithLifecycle()
    val lastScan = state.lastScan?.toEpochMilli() ?: 0L
    when {
        monitor.running -> Text(
            "Background monitor, live: ${ThreatSummary.monitorLine(monitor.keysByState, monitor.keysClose, monitor.keysFollowing)}. The summary above is the last scan's; scan again to fold the monitor's sightings in.",
            style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
        )
        monitor.stoppedAt > lastScan && monitor.trackerKeys > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The monitor saw ${ThreatSummary.identities(monitor.trackerKeys)} before it stopped; they are not in this summary yet.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions::scan, enabled = !state.scan.running) { Text("Scan now") }
        }
    }
}

@Composable
private fun TypeCard(card: SurroundingsFormat.TrackerCard, state: TunnelScreenState, actions: TunnelScreenActions, openSheet: (Sheet) -> Unit) {
    // The family is a summary: its tint follows its worst identity, never family-wide counts.
    val tint = levelTint(card.worstLevel, card.muted)
    val family = card.family
    var open by remember(card.subject) { mutableStateOf<String?>(null) }
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.type.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${TrackerSignatures.of(card.type).confidence.name.lowercase()} confidence", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim)
                if (card.muted) {
                    Spacer(Modifier.width(8.dp))
                    Text("muted", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim)
                }
            }
            val module = state.module as? SurroundingsTunnel
            // Family-wide mutes are no longer offered; one saved by an earlier version still applies until it ends.
            if (card.muted && module != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Muted as a whole family: no warnings for any of its identities until the mute ends or you unmute it. New mutes apply to one identity.",
                        style = MaterialTheme.typography.labelSmall, color = GlassColors.dim, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { actions.perform(module.unmuteAction(card.subject)) }, enabled = !state.scan.running) { Text("Unmute") }
                }
            }
            Text(
                FamilySummary.headline(family) + (if (card.seenToday) " · seen today" else ""),
                style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
            )
            Text(FamilySummary.states(family), style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            ClosestMeter(family, tint)
            Text(TrackerSignatures.of(card.type).basis, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            if (card.devices.isEmpty()) {
                Text("Identities beyond the first ${SurroundingsKeys.MAX_LISTED_DEVICES} are counted, not listed.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
            card.devices.forEach { d ->
                val f = d.identity(card.type)
                IdentityRow(f, open == d.key) { open = if (open == d.key) null else d.key }
                if (open == d.key) IdentityDetail(f, card.muted, state, actions, openSheet)
            }
        }
    }
}

private fun levelTint(level: FollowingLevel, muted: Boolean): Color = when {
    muted -> GlassColors.dim
    level == FollowingLevel.CRITICAL -> StatusColors.blocker
    level == FollowingLevel.WARN -> StatusColors.warn
    level == FollowingLevel.STAYS -> StatusColors.info
    else -> line
}

/** The family's closest identity against the following threshold: one line and two small bars (scans, minutes). */
@Composable
private fun ClosestMeter(family: FamilyFacts, tint: Color) {
    val progress = family.closestProgress
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            FamilySummary.closest(family),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (family.following > 0) tint else GlassColors.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { progress?.scanFraction ?: 0f }, modifier = Modifier.weight(1f).height(4.dp), color = tint)
            LinearProgressIndicator(progress = { progress?.minuteFraction ?: 0f }, modifier = Modifier.weight(1f).height(4.dp), color = tint)
        }
    }
}

@Composable
private fun IdentityRow(f: IdentityFacts, open: Boolean, onClick: () -> Unit) {
    val tint = levelTint(f.level, f.muted)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = tint, modifier = Modifier.weight(0.3f))
        Text(
            listOfNotNull(
                SurroundingsFormat.stateLabel(f.state),
                f.proximityLast?.label,
                when {
                    f.muted -> "muted"
                    f.level.isFollowing -> "following"
                    f.level == FollowingLevel.STAYS -> "stays put"
                    else -> f.progress.scansHint
                },
                if (f.seenThisScan) "this scan" else f.lastSeen?.let { "last ${SurroundingsFormat.timeOf(it)}" },
            ).joinToString(" · "),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(0.7f),
        )
        Text(if (open) "▾" else "▸", color = GlassColors.dim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityDetail(
    f: IdentityFacts,
    /** The identity's family carries a mute saved before v3: unmuting the identity has to lift that too. */
    familyMuted: Boolean,
    state: TunnelScreenState,
    actions: TunnelScreenActions,
    openSheet: (Sheet) -> Unit,
) {
    val context = LocalContext.current
    val tunnel = state.module as? SurroundingsTunnel
    val guide = TrackerGuides.of(f.type)
    val subject = SurroundingsKeys.trackerSubject(f.type, f.key)
    Column(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FactRow("state", SurroundingsFormat.stateLabel(f.state))
            FactRow("signal", "last ${SurroundingsFormat.signal(f.rssiLast)} · average ${SurroundingsFormat.signal(f.rssiAvg)}")
            FactRow("first seen", f.firstSeen?.let { SurroundingsFormat.timeOf(it) } ?: "—")
            FactRow("last seen", (f.lastSeen?.let { SurroundingsFormat.timeOf(it) } ?: "—") + if (f.seenThisScan) " (this scan)" else "")
            FactRow("scans", "${f.scans} · ${f.sightings} advertisement${if (f.sightings == 1) "" else "s"}")
            FactRow("spanned", TrackerVerdict.minutes(f.spanMinutes))
            FactRow("moves", SurroundingsFormat.movementLabel(f))
            f.battery?.let { FactRow("battery", it) }
            AppleFindMyFrame.kindLabel(f.kind)?.let { FactRow("kind", it) }
        }
        Text(TrackerVerdict.line(f), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { actions.perform(TrackerActions.findIt(context, f.type, f.key)) }) { Text(TrackerActions.LABEL_FIND_IT) }
            OutlinedButton(onClick = { actions.perform(TrackerActions.unknownTrackerAlerts(context)) }) { Text(TrackerActions.LABEL_ALERTS) }
            OutlinedButton(onClick = { openSheet(Sheet("${LABEL_IDENTIFY} · ${f.type.label}", guide.identify)) }) { Text(LABEL_IDENTIFY) }
            OutlinedButton(onClick = { openSheet(Sheet("${LABEL_DISABLE} · ${f.type.label}", guide.disable)) }) { Text(LABEL_DISABLE) }
            OutlinedButton(onClick = {
                openSheet(Sheet(LABEL_REPORT, TrackerGuides.report.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n") + "\n\n" + guide.reportNote))
            }) { Text(LABEL_REPORT) }
            if (tunnel != null && !f.muted) OutlinedButton(onClick = { actions.perform(tunnel.muteAction(subject)) }) { Text(SurroundingsTunnel.LABEL_MUTE) }
            if (tunnel != null && f.muted) {
                OutlinedButton(onClick = {
                    val subjects = if (familyMuted) arrayOf(subject, SurroundingsKeys.typeSubject(f.type)) else arrayOf(subject)
                    actions.perform(tunnel.unmuteAction(*subjects))
                }) { Text("Unmute") }
            }
        }
        Text(TrackerGuides.UNKNOWN_TRACKER_ALERTS, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.width(84.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GuideCard() {
    var open by remember { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How to find a tracker", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (open) "▾" else "▸", color = GlassColors.dim)
            }
            if (open) {
                Text(TrackerGuides.SEARCH, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Each identity is judged on its own, so a crowd of strangers' tags of one kind never adds up to \"following\". " +
                        "A tag of your own, or a companion's, follows you too: mute it from its row and it stays listed without a warning. " +
                        "Only Apple tags say whether they are near their owner; for the others Tunnels counts how often they recur. " +
                        "With location on, each scan also notes whether you moved since the last one (never where you are): a tag heard " +
                        "before and after a move travelled with you, one that only turns up where you stay is a neighbour's as a rule. " +
                        "Each identity's row opens a detail with its times, signal and the steps to identify, disable and report the tag.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
        }
    }
}
