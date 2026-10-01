package io.github.stronghorse44.tunnels.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.model.MetroLine

/** The explore line's liquid color, for every card in this module. */
internal val exploreTint: Color get() = LineColors.of(MetroLine.EXPLORE)

/** Opens every explore screen: a reminder that nothing below is a security finding. */
@Composable
internal fun ExploreNote() {
    Text(
        ExploreFormat.NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = GlassColors.dim,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/** Shown before the first scan. */
@Composable
internal fun ExploreEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim, modifier = Modifier.padding(horizontal = 6.dp))
}

/** A card heading: title on the left, a monospace count in the line color on the right. */
@Composable
internal fun CardTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(trailing, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = exploreTint)
    }
}

/** One labelled fact: dim monospace label, then the value. */
@Composable
internal fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.62f))
    }
}

/** A short run of small facts separated by middle dots, e.g. "12 MP · f/1.85 · 4.38 mm". */
@Composable
internal fun FactLine(parts: List<String>, color: Color = GlassColors.dim) {
    val text = parts.filter { it.isNotBlank() }.joinToString(" · ")
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
internal fun CardColumn(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}
