package io.github.stronghorse44.tunnels.apk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.trackers.TrackerCatalog
import io.github.stronghorse44.tunnels.trackers.TrackerStats

private const val TOP_TRACKERS = 8

/** The most common embedded SDKs and category totals across the last scan. */
@Composable
fun ApkSummaryPanel(state: TunnelScreenState) {
    val stats = remember(state.observations) { TrackerStats.from(state.observations) }
    if (stats.apps == 0) return
    val line = LineColors.of(MetroLine.INSPECT)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Embedded SDKs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${stats.appsWithTrackers} of ${stats.apps} apps embed a known SDK" +
                    if (stats.appsSkipped > 0) " · ${stats.appsSkipped} too large to read fully" else "",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (stats.perTracker.isEmpty()) {
                Text("No known SDKs found.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val max = stats.perTracker.first().second.coerceAtLeast(1)
            SectionLabel("Most common")
            stats.perTracker.take(TOP_TRACKERS).forEach { (trackerId, apps) ->
                val tracker = TrackerCatalog.byId(trackerId)
                BarRow(
                    title = tracker?.name ?: trackerId,
                    subtitle = tracker?.vendor,
                    count = apps,
                    fraction = apps.toFloat() / max,
                    color = line,
                )
            }
            if (stats.perTracker.size > TOP_TRACKERS) {
                Text("and ${stats.perTracker.size - TOP_TRACKERS} more", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
            Spacer(Modifier.height(2.dp))
            SectionLabel("By category · apps")
            val catMax = stats.perCategory.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
            stats.perCategory.forEach { (category, apps) ->
                BarRow(title = category.label, subtitle = null, count = apps, fraction = apps.toFloat() / catMax, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
}

@Composable
private fun BarRow(title: String, subtitle: String?, count: Int, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                Spacer(Modifier.width(8.dp))
            }
            Text("$count", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(color.copy(alpha = 0.85f)))
        }
    }
}
