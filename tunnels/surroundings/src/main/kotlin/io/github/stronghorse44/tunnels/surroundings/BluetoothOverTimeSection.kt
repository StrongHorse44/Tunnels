package io.github.stronghorse44.tunnels.surroundings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.ble.BtFollowing
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.ble.WifiOverTime
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

/**
 * Bluetooth over time: every fixed-address device (not a tracker) heard in the last 30 days of scans that listened to
 * everything, with how often, over how long, at how many places and across how many moves; camera and bug hints;
 * Find it, Mute and Unmute per row and "Mute all" for the plain repeats. The threshold is the Wi-Fi one. Read back
 * from observations.
 */
@Composable
internal fun BluetoothOverTimeSection(state: TunnelScreenState, actions: TunnelScreenActions) {
    val summary = remember(state.observations) { WifiOverTime.summary(state.observations, SurroundingsKeys.BT_SUMMARY) } ?: return
    val devices = remember(state.observations) { WifiOverTime.bluetoothDevices(state.observations) }
    val facts = remember(state.observations) { state.observations.filter { it.subject == SurroundingsKeys.BT_SUMMARY } }
    val logged = SurroundingsKeys.value(facts, SurroundingsKeys.BT_LOGGED) == "true"
    val rotating = SurroundingsKeys.value(facts, SurroundingsKeys.BT_ROTATING)?.toIntOrNull() ?: 0
    val tunnel = state.module as? SurroundingsTunnel
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    val busy = state.scan.running

    val flagged = devices.filter { it.flagged }
    val rest = devices - flagged.toSet()
    val tint = when {
        flagged.any { overTimeTint(it) == StatusColors.warn } -> StatusColors.warn
        flagged.isNotEmpty() -> StatusColors.info
        else -> LineColors.of(MetroLine.NETWORK)
    }
    fun name(d: WifiOverTime.ApFacts) = BtFollowing.displayName(d.name, d.subject)
    fun findIt(d: WifiOverTime.ApFacts) = SurroundingsKeys.parseBtDeviceSubject(d.subject)?.let { TrackerActions.findDevice(context, it) }

    Text(
        "Bluetooth over time · ${summary.tracked}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GlassColors.dim,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(overTimeHeadline(summary), style = MaterialTheme.typography.titleSmall, color = tint)
            Text(
                "Bluetooth devices with a fixed address (speakers, TVs, cameras, gadgets) from the last 30 days of scans that " +
                    "listened to everything: your scans, and the monitor's passes while the screen is on. Phones, earbuds and " +
                    "watches change their address every few minutes and cannot be followed" +
                    (if (rotating > 0) "; this scan heard $rotating of those" else "") + ". Trackers are listed above.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
            if (!logged) {
                Text(
                    "This scan did not listen to every device (Bluetooth off, no permission, or the screen went off), so it logged none.",
                    style = MaterialTheme.typography.bodySmall, color = StatusColors.warn,
                )
            }
            Text(
                "Flags after ${WifiFollowing.thresholdText(summary.threshold)}, the Wi-Fi setting above.",
                style = MaterialTheme.typography.bodySmall,
            )

            flagged.forEach { d -> OverTimeRow(d, name(d), tunnel, actions, busy, findIt(d)) }
            val muteAll = WifiOverTime.muteAllCandidates(devices)
            if (tunnel != null && muteAll.size > 1) {
                OutlinedButton(onClick = { actions.perform(tunnel.wifiMuteAllAction(muteAll)) }, enabled = !busy) {
                    Text("Mute all ${muteAll.size} that keep turning up")
                }
            }
            if (rest.isNotEmpty() || summary.unlisted > 0) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Hide the rest" else "Show the other ${rest.size + summary.unlisted}")
                }
            }
            if (showAll) {
                rest.forEach { d -> OverTimeRow(d, name(d), tunnel, actions, busy, findIt(d)) }
                if (summary.unlisted > 0) {
                    Text("${summary.unlisted} more, each seen less often, are not listed.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                }
            }
        }
    }
}
