package io.github.stronghorse44.tunnels.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.stronghorse44.tunnels.model.Stratum

object StrataColors {
    val surface = Color(0xFF5E7F45)
    val topsoil = Color(0xFF6E4A2E)
    val bedrock = Color(0xFF474B54)
    val core = Color(0xFFB4441D)
    val coreGlow = Color(0xFFE8892B)
    val explore = Color(0xFF2C5A74)

    fun of(stratum: Stratum): Color = when (stratum) {
        Stratum.SURFACE -> surface
        Stratum.TOPSOIL -> topsoil
        Stratum.BEDROCK -> bedrock
        Stratum.CORE -> core
        Stratum.EXPLORE -> explore
    }
}

object StatusColors {
    val ok = Color(0xFF7FB069)
    val info = Color(0xFF7FA7C9)
    val warn = Color(0xFFE0A84A)
    val blocker = Color(0xFFE5654B)
}

private val scheme = darkColorScheme(
    primary = Color(0xFFE8892B),
    onPrimary = Color(0xFF1E1206),
    secondary = Color(0xFFB9A48A),
    background = Color(0xFF15110E),
    onBackground = Color(0xFFEDE3D6),
    surface = Color(0xFF1F1915),
    onSurface = Color(0xFFEDE3D6),
    surfaceVariant = Color(0xFF2B231D),
    onSurfaceVariant = Color(0xFFC9B9A6),
    error = StatusColors.blocker,
)

/** Always dark: the cross-section reads as underground. */
@Composable
fun TunnelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
