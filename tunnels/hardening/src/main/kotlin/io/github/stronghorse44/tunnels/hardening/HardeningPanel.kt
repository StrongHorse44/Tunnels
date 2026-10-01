package io.github.stronghorse44.tunnels.hardening

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
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.elf.AppHardening
import io.github.stronghorse44.tunnels.elf.HardeningStats
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

/** Totals of the last scan and the three apps whose native code is least hardened. */
@Composable
fun HardeningPanel(state: TunnelScreenState) {
    val stats = remember(state.observations) { HardeningStats.from(state.observations) }
    if (stats.apps == 0) return
    val line = LineColors.of(MetroLine.INSPECT)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Native code mitigations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("apps scanned", stats.apps, line, Modifier.weight(1f))
                Stat("libs parsed", stats.libsParsed, line, Modifier.weight(1f))
                Stat("weak apps", stats.weakApps, if (stats.weakApps > 0) StatusColors.warn else StatusColors.ok, Modifier.weight(1f))
            }
            Text(
                "${stats.appsWithNative} of ${stats.apps} apps ship native code" +
                    (if (stats.libsSeen > stats.libsParsed) " · ${stats.libsSeen - stats.libsParsed} libraries not read" else "") +
                    (if (stats.legacy32Apps > 0) " · ${stats.legacy32Apps} 32-bit only" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (stats.weakest.isEmpty()) {
                Text("Every parsed library has PIE and a non-executable stack.", style = MaterialTheme.typography.bodyMedium, color = StatusColors.ok)
                return@Column
            }
            Text("Weakest apps", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
            val max = stats.weakest.first().score.coerceAtLeast(1)
            stats.weakest.forEach { app -> WeakAppRow(app, app.score.toFloat() / max) }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("$value", fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    }
}

@Composable
private fun WeakAppRow(app: AppHardening, fraction: Float) {
    val color = if (app.weak > 0 || app.legacy32) StatusColors.warn else StatusColors.info
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
            Spacer(Modifier.width(8.dp))
            Text(describe(app), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(color.copy(alpha = 0.85f)))
        }
    }
}

/** "2/7 weak · 32-bit · 3 lazy" style summary of what is wrong with one app. */
private fun describe(app: AppHardening): String = buildList {
    if (app.weak > 0) add("${app.weak}/${app.parsed} weak")
    if (app.legacy32) add("32-bit")
    if (app.partialRelro > 0) add("${app.partialRelro} lazy")
    if (app.noCanary > 0 && app.weak == 0 && app.partialRelro == 0) add("${app.noCanary} no canary")
}.joinToString(" · ")
