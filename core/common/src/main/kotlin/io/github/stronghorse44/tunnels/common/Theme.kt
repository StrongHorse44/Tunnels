package io.github.stronghorse44.tunnels.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.stronghorse44.tunnels.model.MetroLine

/** The liquid color inside each metro line's glass tube. */
object LineColors {
    fun of(line: MetroLine): Color = when (line) {
        MetroLine.FILES -> Color(0xFF4DD8E6)
        MetroLine.INSPECT -> Color(0xFFA78BFA)
        MetroLine.SYSTEM -> Color(0xFFF5B341)
        MetroLine.ACTIVITY -> Color(0xFF5EE39A)
        MetroLine.NETWORK -> Color(0xFFFF6B9A)
        MetroLine.EXPLORE -> Color(0xFF6FA8FF)
    }
}

object StatusColors {
    val ok = Color(0xFF6EE7A8)
    val info = Color(0xFF7FB8FF)
    val warn = Color(0xFFF5C451)
    val blocker = Color(0xFFFF6B6B)
}

object GlassColors {
    val void = Color(0xFF060910)
    val text = Color(0xFFE6EEF7)
    val dim = Color(0xFF8C9AAD)
}

private val scheme = darkColorScheme(
    primary = Color(0xFF4DD8E6),
    onPrimary = Color(0xFF02181B),
    secondary = Color(0xFFA78BFA),
    background = GlassColors.void,
    onBackground = GlassColors.text,
    surface = Color(0xFF0D121A),
    onSurface = GlassColors.text,
    surfaceVariant = Color(0xFF151C27),
    onSurfaceVariant = GlassColors.dim,
    outline = Color(0x33FFFFFF),
    error = StatusColors.blocker,
)

/** Always dark: glass reads best over a deep background. */
@Composable
fun TunnelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
