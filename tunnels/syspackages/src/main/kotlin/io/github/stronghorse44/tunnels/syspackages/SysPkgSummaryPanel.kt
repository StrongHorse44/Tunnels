package io.github.stronghorse44.tunnels.syspackages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.syspkg.PackageLine
import io.github.stronghorse44.tunnels.syspkg.SysPkgStats

private const val LIST_PREVIEW = 6

/** Totals, namespaces, disabled and unknown packages across the last scan. */
@Composable
fun SysPkgSummaryPanel(state: TunnelScreenState) {
    val stats = remember(state.observations) { SysPkgStats.from(state.observations) }
    if (stats.total == 0) return
    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("System packages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("total", stats.total, line)
                Stat("disabled", stats.disabled.size, if (stats.disabled.isEmpty()) GlassColors.dim else StatusColors.warn)
                Stat("unknown", stats.unknown.size, if (stats.unknown.isEmpty()) GlassColors.dim else StatusColors.info)
                Stat("privileged", stats.privileged, GlassColors.dim)
                Stat("launchable", stats.withLauncher, GlassColors.dim)
            }
            if (stats.skipped > 0) {
                Text("${stats.skipped} more packages were not inspected (per-scan cap).", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
            if (stats.updated > 0) {
                Text("${stats.updated} preinstalled packages run an installed update.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            }

            SectionLabel("By namespace")
            val nsMax = stats.byNamespace.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
            stats.byNamespace.forEach { (ns, count) ->
                BarRow(title = ns, count = count, fraction = count.toFloat() / nsMax, color = line)
            }

            PackageList("Disabled", stats.disabled, "Nothing is disabled.", StatusColors.warn)
            PackageList("Not in the knowledge base", stats.unknown, "Every package is known.", StatusColors.info)
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Column {
        Text("$value", fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
}

@Composable
private fun BarRow(title: String, count: Int, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$count", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(color.copy(alpha = 0.85f)))
        }
    }
}

/** A titled list of packages, collapsed to a preview until tapped. */
@Composable
private fun PackageList(title: String, lines: List<PackageLine>, emptyText: String, color: Color) {
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = lines.size > LIST_PREVIEW) { expanded = !expanded }) {
        SectionLabel("$title · ${lines.size}")
        Spacer(Modifier.weight(1f))
        if (lines.size > LIST_PREVIEW) Text(if (expanded) "show less" else "show all", style = MaterialTheme.typography.labelSmall, color = color)
    }
    if (lines.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = StatusColors.ok)
        return
    }
    val shown = if (expanded) lines else lines.take(LIST_PREVIEW)
    shown.forEach { l ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.label, style = MaterialTheme.typography.bodyMedium)
                if (l.label != l.packageName) Text(l.packageName, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim)
            }
            Spacer(Modifier.width(8.dp))
            Text(l.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
        }
    }
    if (!expanded && lines.size > LIST_PREVIEW) {
        Text("and ${lines.size - LIST_PREVIEW} more", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    }
}
