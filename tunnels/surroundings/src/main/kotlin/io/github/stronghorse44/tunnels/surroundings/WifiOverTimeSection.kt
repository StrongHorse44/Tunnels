package io.github.stronghorse44.tunnels.surroundings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.ble.ManyNames
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.ble.WifiOverTime
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.launch

private fun apTint(level: WifiFollowing.Level, muted: Boolean): Color = when {
    muted -> GlassColors.dim
    level == WifiFollowing.Level.FOLLOWING -> StatusColors.warn
    level == WifiFollowing.Level.REPEATED -> StatusColors.info
    else -> GlassColors.text
}

private fun apLabel(level: WifiFollowing.Level, muted: Boolean): String = when {
    muted -> "muted"
    level == WifiFollowing.Level.FOLLOWING -> "travelled with you"
    level == WifiFollowing.Level.REPEATED -> "keeps turning up"
    else -> ""
}

/**
 * Wi-Fi over time: every access point the last 30 days of scans heard, with how often, over how long, at how many
 * places and across how many moves; the threshold (scans over 30 minutes) the user sets; devices that broadcast many
 * names; Mute and Unmute per row and "Mute all" for the ones that only keep turning up. Read back from observations.
 */
@Composable
internal fun WifiOverTimeSection(state: TunnelScreenState, actions: TunnelScreenActions) {
    val summary = remember(state.observations) { WifiOverTime.summary(state.observations) } ?: return
    val aps = remember(state.observations) { WifiOverTime.accessPoints(state.observations) }
    val devices = remember(state.observations) { WifiOverTime.devices(state.observations) }
    val tunnel = state.module as? SurroundingsTunnel
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    val busy = state.scan.running

    val flagged = aps.filter { it.level != WifiFollowing.Level.NONE && !it.muted }
    val rest = aps - flagged.toSet()
    val worstDevice = devices.filter { !it.muted }.maxOfOrNull { it.level } ?: ManyNames.Level.NONE
    val tint = when {
        summary.following > 0 || worstDevice == ManyNames.Level.WARN -> StatusColors.warn
        summary.repeated > 0 || worstDevice == ManyNames.Level.NOTICE -> StatusColors.info
        else -> LineColors.of(MetroLine.NETWORK)
    }
    val headline = listOfNotNull(
        if (summary.following > 0) "${summary.following} travelled with you" else null,
        if (summary.repeated > 0) "${summary.repeated} keep turning up" else null,
    ).joinToString(" · ").ifEmpty { "None passed the threshold" }

    Text(
        "Wi-Fi over time · ${summary.tracked}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GlassColors.dim,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall, color = tint)
            Text(
                "Every access point heard in the last 30 days of scans. Android shows apps only access points (routers, phone " +
                    "hotspots, mobile Wi-Fi, car Wi-Fi, cameras in setup mode): a phone or camera that only joins a network never appears.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Flag after ${WifiFollowing.thresholdText(summary.threshold)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launch { WifiSightings.setThreshold(context, summary.threshold - 1); actions.scan() } },
                    enabled = !busy && summary.threshold > WifiFollowing.MIN_THRESHOLD,
                ) { Text("−") }
                TextButton(
                    onClick = { scope.launch { WifiSightings.setThreshold(context, summary.threshold + 1); actions.scan() } },
                    enabled = !busy && summary.threshold < WifiFollowing.MAX_THRESHOLD,
                ) { Text("+") }
            }

            devices.forEach { d ->
                val dTint = if (d.muted) GlassColors.dim else if (d.level == ManyNames.Level.WARN) StatusColors.warn else StatusColors.info
                Column {
                    Text(
                        "One device, ${d.names} names" + if (d.open > 0) ", ${d.open} open" else "",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = dTint,
                    )
                    Text(d.sample, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                    if (tunnel != null) Row {
                        if (d.muted) TextButton(onClick = { actions.perform(tunnel.wifiUnmuteAction(d.subject, "this device")) }, enabled = !busy) { Text("Unmute") }
                        else TextButton(onClick = { actions.perform(tunnel.wifiMuteAction(d.subject)) }, enabled = !busy) { Text(SurroundingsTunnel.LABEL_MUTE_WIFI) }
                    }
                }
            }

            flagged.forEach { ap -> ApRow(ap, tunnel, actions, busy) }
            val muteAll = WifiOverTime.muteAllCandidates(aps)
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
                rest.forEach { ap -> ApRow(ap, tunnel, actions, busy) }
                if (summary.unlisted > 0) {
                    Text("${summary.unlisted} more, each seen less often, are not listed.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                }
            }
        }
    }
}

@Composable
private fun ApRow(ap: WifiOverTime.ApFacts, tunnel: SurroundingsTunnel?, actions: TunnelScreenActions, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ap.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                val label = apLabel(ap.level, ap.muted)
                if (label.isNotEmpty()) Text("  $label", style = MaterialTheme.typography.labelSmall, color = apTint(ap.level, ap.muted))
            }
            Text(
                ap.counts + if (ap.thisScan) " · in range now" else ap.lastSeen?.let { " · last seen ${SurroundingsRules.lastSeenLabel(it)}" }.orEmpty(),
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
        }
        if (tunnel != null) {
            when {
                ap.muted -> TextButton(onClick = { actions.perform(tunnel.wifiUnmuteAction(ap.subject, ap.name)) }, enabled = !busy) { Text("Unmute") }
                ap.level != WifiFollowing.Level.NONE -> TextButton(onClick = { actions.perform(tunnel.wifiMuteAction(ap.subject)) }, enabled = !busy) { Text("Mute") }
            }
        }
    }
}
