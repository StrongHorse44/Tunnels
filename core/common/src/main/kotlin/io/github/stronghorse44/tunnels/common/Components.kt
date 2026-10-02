package io.github.stronghorse44.tunnels.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.metro.Depth
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog

/**
 * Screen frame for a tunnel: you stand on its stratum's ring (a band of that stratum's light curving across
 * the header) in water one step darker than home, darker still for deeper strata. [stratum] defaults to the
 * catalog entry whose title matches; screens outside the catalog sit at plain tunnel depth in [line]'s colour.
 */
@Composable
fun TunnelScaffold(
    title: String,
    line: MetroLine,
    onBack: () -> Unit,
    stratum: Stratum? = null,
    tunnelId: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val entry = TunnelCatalog.all.firstOrNull { it.id == tunnelId } ?: TunnelCatalog.all.firstOrNull { it.title == title }
    val s = stratum ?: entry?.stratum
    val color = if (s != null) StratumColors.of(s) else LineColors.of(line)
    DepthBackground(Depth.ofTunnel(s)) {
        Scaffold(
            topBar = {
                Box(Modifier.fillMaxWidth()) {
                    RingArc(s, entry?.id, color, Modifier.matchParentSize())
                    Column(Modifier.statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 64.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GlassColors.text)
                            }
                            Text(
                                listOfNotNull(s?.let(StratumColors::label), "${line.label.uppercase()} LINE").joinToString(" · "),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                letterSpacing = 1.4.sp,
                                color = GlassColors.dim,
                            )
                        }
                        Text(
                            title.uppercase(),
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp,
                            lineHeight = 27.sp,
                            letterSpacing = 2.sp,
                            color = GlassColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                        )
                    }
                }
            },
            containerColor = Color.Transparent,
            contentColor = GlassColors.text,
            modifier = Modifier.fillMaxSize(),
        ) { padding -> content(padding) }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
