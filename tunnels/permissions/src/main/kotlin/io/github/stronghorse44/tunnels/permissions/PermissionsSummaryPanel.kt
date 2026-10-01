package io.github.stronghorse44.tunnels.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.permrules.PermissionSummary

/** Compact headline: how many apps hold each sensitive group, and how the Network and Sensors toggles stand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionsSummaryPanel(observations: List<Observation>) {
    val summary = remember(observations) { PermissionSummary.of(observations) }
    if (summary.apps == 0) return
    val line = LineColors.of(MetroLine.INSPECT)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${summary.apps} apps · ${summary.userApps} installed by you", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("Network: ${summary.networkOn} on · ${summary.networkOff} off")
                    if (summary.networkNotRequested > 0) append(" · ${summary.networkNotRequested} never ask")
                },
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            Text(
                if (summary.sensorsVisible) "Sensors: ${summary.sensorsOff} off" else "Sensors toggle not visible on this device",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (summary.perGroup.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.perGroup.forEach { (group, count) ->
                        val tint = when (group.weight) {
                            3 -> StatusColors.blocker
                            2 -> StatusColors.warn
                            else -> line
                        }
                        Chip("${group.label} · $count", tint)
                    }
                }
            } else {
                Text("No app holds a sensitive permission.", style = MaterialTheme.typography.bodySmall, color = StatusColors.ok)
            }
        }
    }
}

@Composable
private fun Chip(text: String, tint: Color) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
