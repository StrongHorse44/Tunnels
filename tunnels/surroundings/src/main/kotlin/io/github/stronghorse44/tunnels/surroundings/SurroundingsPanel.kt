package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.delay

private val line: Color get() = LineColors.of(MetroLine.NETWORK)

/** Live scan readout, the background-monitor switch with its opt-in text, the tracker cards and the finding guide. */
@Composable
fun SurroundingsPanel(state: TunnelScreenState, actions: TunnelScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveCard(state)
        MonitorCard(actions)
        TrackerCards(state.observations)
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
                    Counter("Trackers", obs.fact(SurroundingsKeys.BLE_SUMMARY, SurroundingsKeys.TRACKERS_TOTAL), "in 30 days", null)
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
    androidx.compose.runtime.LaunchedEffect(monitor.running) {
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
                        if (monitor.running) "On for ${SurroundingsFormat.elapsed(now - monitor.startedAt)} · ${SurroundingsFormat.monitorLine(monitor.windows, monitor.trackerKeys, monitor.lastCell)}"
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
                        "across places and hours. It needs location \"all the time\" (Android ties Bluetooth and cell scanning to it; no position is " +
                        "ever stored) and a persistent notification you can stop it from. It never starts by itself and stops after " +
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

@Composable
private fun TrackerCards(observations: List<Observation>) {
    val cards = remember(observations) { SurroundingsFormat.trackerCards(observations) }
    if (cards.isEmpty()) return
    Text("Trackers · ${cards.sumOf { it.facts[SurroundingsKeys.DEVICES]?.toIntOrNull() ?: 0 }}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GlassColors.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    cards.forEach { card ->
        val state = TrackerState.bySlug(card.facts[SurroundingsKeys.STATE])
        val sessions = card.facts[SurroundingsKeys.SEEN_SESSIONS]?.toIntOrNull() ?: 0
        val span = card.facts[SurroundingsKeys.SEEN_SPAN]?.toLongOrNull() ?: 0L
        val muted = card.facts[SurroundingsKeys.MUTED] == "true"
        val tint = when {
            muted -> GlassColors.dim
            state == TrackerState.SEPARATED -> StatusColors.warn
            else -> line
        }
        var open by remember(card.subject) { mutableStateOf(false) }
        GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
            Column(Modifier.clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.type.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${TrackerSignatures.of(card.type).confidence.name.lowercase()} confidence",
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim,
                    )
                    if (muted) {
                        Spacer(Modifier.width(8.dp))
                        Text("muted", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim)
                    }
                }
                Text(
                    "${card.facts[SurroundingsKeys.DEVICES] ?: "?"} identit${if (card.facts[SurroundingsKeys.DEVICES] == "1") "y" else "ies"} · " +
                        "$sessions scan${if (sessions == 1) "" else "s"} over ${SurroundingsRules.duration(span)} · ${SurroundingsFormat.stateLabel(state)}" +
                        (if (card.facts[SurroundingsKeys.SEEN_LAST_DAY] == "true") " · seen today" else ""),
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
                Text(TrackerSignatures.of(card.type).basis, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                if (open) {
                    card.devices.forEach { d ->
                        Row(Modifier.padding(top = 2.dp)) {
                            Text(d.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = tint, modifier = Modifier.weight(0.3f))
                            Text(
                                listOfNotNull(
                                    d.facts[SurroundingsKeys.SEEN_SESSIONS]?.let { "$it scan${if (it == "1") "" else "s"}" },
                                    d.facts[SurroundingsKeys.RSSI_AVG]?.let { "$it dBm" },
                                    d.facts[SurroundingsKeys.STATE]?.let { SurroundingsFormat.stateLabel(TrackerState.bySlug(it)) },
                                    d.facts[SurroundingsKeys.BATTERY]?.let { "battery $it" },
                                ).joinToString(" · "),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(0.7f),
                            )
                        }
                    }
                    if (card.devices.isEmpty()) Text("Identities beyond the first ${SurroundingsKeys.MAX_LISTED_DEVICES} are counted, not listed.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                }
            }
        }
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
                Text(SurroundingsTunnel.FIND_GUIDE, style = MaterialTheme.typography.bodySmall)
                Text(
                    "A tag of your own, or a companion's, follows you too: mute it from its warning and it stays listed without one. " +
                        "Only Apple tags say whether they are away from their owner; for the others Tunnels can only count how often they recur.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
        }
    }
}
