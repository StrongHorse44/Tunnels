package io.github.stronghorse44.tunnels.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Frosted glass: a faint tinted fill, a bright top edge fading down the rim, and a specular sheen
 * across the upper third. Reads as glass over [GlassBackground]'s colored light.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), tint: Color = Color.White): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.09f), tint.copy(alpha = 0.07f), Color.White.copy(alpha = 0.025f)),
        ),
    )
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
                endY = size.height * 0.4f,
            ),
        )
    }
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.38f), tint.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)),
        ),
        shape,
    )

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tint), content = content)
}

/** Deep background with soft pools of colored light for the glass to sit over. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(GlassColors.void)
            .drawBehind {
                fun glow(color: Color, x: Float, y: Float, r: Float, a: Float) = drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = a), Color.Transparent),
                        center = Offset(size.width * x, size.height * y),
                        radius = size.width * r,
                    ),
                    radius = size.width * r,
                    center = Offset(size.width * x, size.height * y),
                )
                glow(Color(0xFF2BC4D6), 0.1f, 0.08f, 0.85f, 0.22f)
                glow(Color(0xFF8B5CF6), 0.95f, 0.42f, 0.8f, 0.18f)
                glow(Color(0xFFF59E0B), 0.15f, 0.88f, 0.75f, 0.10f)
            },
        content = content,
    )
}
