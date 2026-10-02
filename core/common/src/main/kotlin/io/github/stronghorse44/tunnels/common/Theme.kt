package io.github.stronghorse44.tunnels.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Stratum

/**
 * Prism well palette. The light of the coloured corridor (magenta, red-orange, amber, green) lights the
 * stairwell's rings; the glory's pastels ring the light at the bottom; the water darkens with depth (see
 * [io.github.stronghorse44.tunnels.metro.Depth]).
 */
object LineColors {
    fun of(line: MetroLine): Color = when (line) {
        MetroLine.FILES -> Color(0xFFFF5C8A)
        MetroLine.INSPECT -> Color(0xFFFF7A2E)
        MetroLine.SYSTEM -> Color(0xFFFFC83D)
        MetroLine.ACTIVITY -> Color(0xFF46E889)
        MetroLine.NETWORK -> Color(0xFF4FD8FF)
        MetroLine.EXPLORE -> Color(0xFFCBB8FF)
    }
}

/** One band of the corridor's light per stratum, Surface to Core; Explore is the lilac side shaft. */
object StratumColors {
    /** The unlit violet wall around the well. */
    val rim = Color(0xFF7D5CFF)

    fun of(s: Stratum?): Color = when (s) {
        Stratum.SURFACE -> Color(0xFFFF3FA4)
        Stratum.TOPSOIL -> Color(0xFFFF7A2E)
        Stratum.BEDROCK -> Color(0xFFFFC83D)
        Stratum.CORE -> Color(0xFF46E889)
        Stratum.EXPLORE -> Color(0xFFCBB8FF)
        null -> rim
    }

    /** What the user sees for a stratum: a plain name for what its tunnels look at, not the geology. */
    fun label(s: Stratum): String = when (s) {
        Stratum.SURFACE -> "Network"
        Stratum.TOPSOIL -> "Apps"
        Stratum.BEDROCK -> "System"
        Stratum.CORE -> "Deep checks"
        Stratum.EXPLORE -> "Explore"
    }
}

/** The glory's pastel spectrum, closed so a sweep gradient joins up. */
object Glory {
    val colors = listOf(
        Color(0xFFFFADD6), Color(0xFFFFD6A3), Color(0xFFFFF0A3), Color(0xFFB3F0CC),
        Color(0xFFA6DCFF), Color(0xFFC8B3FF), Color(0xFFFFADD6),
    )
}

/** The corridor's hot end, for the one primary action on a finding. */
object Spectrum {
    val primary = listOf(Color(0xFFFF3FA4), Color(0xFFFF7A2E), Color(0xFFFFC83D))
}

object StatusColors {
    val ok = Color(0xFF46E889)
    val info = Color(0xFF9AD7FF)
    val warn = Color(0xFFFF9A3C)
    val blocker = Color(0xFFFF3F5E)
}

object GlassColors {
    /** Deep ink: railings, text on light fills, the darkest water. */
    val void = Color(0xFF0D0736)
    val text = Color(0xFFF6F3FF)
    val dim = Color(0xFFC4BDEB)
}

private val scheme = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF1F0A00),
    secondary = Color(0xFFFF3FA4),
    onSecondary = Color(0xFF2A0016),
    tertiary = Color(0xFF46E889),
    onTertiary = Color(0xFF00210E),
    background = GlassColors.void,
    onBackground = GlassColors.text,
    surface = Color(0xFF1A0F55),
    onSurface = GlassColors.text,
    surfaceVariant = Color(0xFF241670),
    onSurfaceVariant = GlassColors.dim,
    surfaceContainerLowest = Color(0xFF0D0736),
    surfaceContainerLow = Color(0xFF150B4A),
    surfaceContainer = Color(0xFF1A0F55),
    surfaceContainerHigh = Color(0xFF221466),
    surfaceContainerHighest = Color(0xFF2A1A78),
    outline = Color(0x40FFFFFF),
    outlineVariant = Color(0x26FFFFFF),
    error = StatusColors.blocker,
    onError = Color(0xFF2A0008),
)

/** Always dark: the deeper you go, the darker the water (see [DepthBackground]). */
@Composable
fun TunnelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
