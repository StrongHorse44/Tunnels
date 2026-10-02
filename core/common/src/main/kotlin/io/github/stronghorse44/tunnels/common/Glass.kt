package io.github.stronghorse44.tunnels.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.metro.Depth

/** How far down the current screen sits; [DepthBackground] darkens with it. Home provides [Depth.HOME]. */
val LocalDepth = compositionLocalOf { Depth.TUNNEL }

@Composable
fun ProvideDepth(depth: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDepth provides depth, content = content)
}

/**
 * Frosted glass over the water: a faint tinted fill, a bright top edge fading down the rim, and a sheen
 * across the upper third. The fill thins as the water darkens so panels never glow brighter than home.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), tint: Color = Color.White): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f), tint.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f)),
        ),
    )
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
                endY = size.height * 0.4f,
            ),
        )
    }
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.34f), tint.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f)),
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

/** The water at [LocalDepth]. Kept for screens that predate depth; new screens call [DepthBackground]. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    DepthBackground(LocalDepth.current, modifier, content)
}

/**
 * The water at [depth]: violet light from the surface pooled at the top, sinking to black at the bottom, with
 * a faint glow of the corridor's spectrum underneath. Every step down the app is darker than the one above.
 */
@Composable
fun DepthBackground(depth: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val water = Depth.water(depth)
    val light = Depth.surfaceLight(depth)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(water.top), Color(water.mid), Color(water.bottom))))
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
                glow(Color(0xFFB9A8FF), 0.5f, -0.05f, 1.1f, 0.35f * light)
                glow(Color(0xFFFF3FA4), 0.05f, 0.55f, 0.7f, 0.10f)
                glow(Color(0xFFFF7A2E), 0.95f, 0.85f, 0.7f, 0.07f)
            },
    ) {
        // These screens have no Surface, so give text a light default instead of black.
        CompositionLocalProvider(LocalContentColor provides GlassColors.text, LocalDepth provides depth) { content() }
    }
}
