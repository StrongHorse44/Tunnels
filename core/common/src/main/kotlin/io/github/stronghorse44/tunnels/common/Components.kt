package io.github.stronghorse44.tunnels.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.model.MetroLine

/** Screen frame for a tunnel: a glass top bar tinted with the tunnel's metro line, over the glass background. */
@Composable
fun TunnelScaffold(
    title: String,
    line: MetroLine,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val color = LineColors.of(line)
    GlassBackground {
        Scaffold(
            topBar = {
                GlassPanel(
                    tint = color,
                    shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GlassColors.text)
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = GlassColors.text)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${line.label.lowercase()} line",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                )
                            }
                        }
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
