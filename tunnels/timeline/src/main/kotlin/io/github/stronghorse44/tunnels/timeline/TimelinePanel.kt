package io.github.stronghorse44.tunnels.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import java.util.Locale

/** The last 30 days at a glance: the five most used apps, the five heaviest on data, and the unused count. */
@Composable
fun TimelinePanel(state: TunnelScreenState) {
    val stats = remember(state.observations) { TimelineStats.from(state.observations) }
    if (stats.accessGranted == null) return
    val line = LineColors.of(MetroLine.ACTIVITY)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Last 30 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (stats.accessGranted == false) {
                Text(
                    "Usage access was not granted at the last scan, so only the app count is known. Grant it and scan again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
                return@Column
            }
            Text(
                buildString {
                    append("${stats.apps} apps")
                    stats.totalMb?.let { append(" · ${formatMb(it)} moved") }
                    append(" · ${stats.unused60} not opened in ${TimelineKeys.UNUSED_DAYS}+ days")
                },
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            SectionLabel("Most used · minutes in the foreground")
            if (stats.topByForeground.isEmpty()) {
                Text("No foreground use recorded yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val max = stats.topByForeground.first().amount.coerceAtLeast(1)
                stats.topByForeground.forEach { e ->
                    BarRow(e.label, formatMinutes(e.amount), e.amount.toFloat() / max, line)
                }
            }
            Spacer(Modifier.height(2.dp))
            SectionLabel("Most data · Wi-Fi and mobile")
            when {
                stats.totalMb == null -> Text("Network counters could not be read this time.", style = MaterialTheme.typography.bodyMedium)
                stats.topByData.isEmpty() -> Text("No app moved a megabyte.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val max = stats.topByData.first().amount.coerceAtLeast(1)
                    stats.topByData.forEach { e ->
                        BarRow(e.label, formatMb(e.amount), e.amount.toFloat() / max, Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

private fun formatMb(mb: Long): String =
    if (mb >= 1000) String.format(Locale.ROOT, "%.1f GB", mb / 1000.0) else "$mb MB"

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
}

@Composable
private fun BarRow(title: String, amount: String, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(amount, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(color.copy(alpha = 0.85f)))
        }
    }
}
