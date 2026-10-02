package io.github.stronghorse44.tunnels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.runtime.TunnelSummary
import io.github.stronghorse44.tunnels.runtime.severityColor

/**
 * The home screen's way into everything that spans tunnels: open findings across all of them (the inbox, where
 * background checks live) and the device checks. Its own file so a new home layout can place it as one piece.
 */
@Composable
fun FindingsConsole(summaries: Collection<TunnelSummary>, onOpenFindings: () -> Unit, onOpenChecks: () -> Unit) {
    val counts = Severity.entries.associateWith { s -> summaries.sumOf { it.counts[s] ?: 0 } }
    val total = counts.values.sum()
    val worst = Severity.entries.lastOrNull { (counts[it] ?: 0) > 0 }
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.FILES)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = GlassColors.dim)) { append("> ") }
                    withStyle(SpanStyle(color = GlassColors.text)) { append("findings ") }
                    if (total == 0) {
                        withStyle(SpanStyle(color = StatusColors.ok)) { append("none open") }
                    } else {
                        withStyle(SpanStyle(color = GlassColors.text)) { append("$total open") }
                        Severity.entries.reversed().filter { it >= Severity.WARN && (counts[it] ?: 0) > 0 }.forEach { s ->
                            withStyle(SpanStyle(color = severityColor(s))) { append("  [${counts[s]} ${s.name}]") }
                        }
                    }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenFindings) {
                    Text("findings ›", fontFamily = FontFamily.Monospace, color = worst?.takeIf { it >= Severity.WARN }?.let(::severityColor) ?: LineColors.of(MetroLine.FILES))
                }
                TextButton(onClick = onOpenChecks) { Text("checks ›", fontFamily = FontFamily.Monospace, color = LineColors.of(MetroLine.SYSTEM)) }
            }
        }
    }
}
