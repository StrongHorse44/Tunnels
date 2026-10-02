package io.github.stronghorse44.tunnels.runtime

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import io.github.stronghorse44.tunnels.common.GlassBackground
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel

/**
 * Frame for a screen that spans tunnels rather than being one (the findings inbox, device checks): the tunnel
 * screens' glass top bar with a free subtitle instead of a metro line.
 */
@Composable
fun ToolScaffold(
    title: String,
    subtitle: String,
    tint: Color,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    GlassBackground {
        Scaffold(
            topBar = {
                GlassPanel(
                    tint = tint,
                    shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GlassColors.text)
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = GlassColors.text)
                            Text(subtitle, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = tint)
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

/** A small rounded toggle for choosing one of a few options. */
@Composable
fun Choice(label: String, selected: Boolean, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (selected) 0.9f else 0.35f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) GlassColors.text else GlassColors.dim)
    }
}
