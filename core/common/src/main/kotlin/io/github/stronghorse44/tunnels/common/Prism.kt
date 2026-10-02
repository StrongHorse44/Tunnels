package io.github.stronghorse44.tunnels.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.metro.Well
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** True when the user turned animations off (Developer options or Accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The well seen from above: violet rim, one ring of corridor light per stratum with a shaded edge, a
 * handrail and balusters, then the oculus floor. [tickTurn] rotates each ring's balusters (degrees, by ring
 * index with the rim at 0) for the scan; [lit] picks each ring's colour.
 */
fun DrawScope.drawWell(
    tickTurn: (Int) -> Float = { 0f },
    lit: (Stratum?) -> Color = StratumColors::of,
    ink: Color = GlassColors.void,
    oculusTint: Color = Color(0xFFEFEAFF),
) {
    val s = size.minDimension
    val u = s / 260f
    val rings = listOf(Well.rim) + Well.rings
    // Corridor light spilling up the well.
    drawCircle(
        Brush.radialGradient(
            0.80f to StratumColors.of(Stratum.SURFACE).copy(alpha = 0.30f),
            1f to Color.Transparent,
            center = Offset(Well.rim.cx * s, Well.rim.cy * s),
            radius = Well.rim.r * s * 1.12f,
        ),
        radius = Well.rim.r * s * 1.12f,
        center = Offset(Well.rim.cx * s, Well.rim.cy * s),
    )
    rings.forEachIndexed { i, ring ->
        val c = Offset(ring.cx * s, ring.cy * s)
        val r = ring.r * s
        drawCircle(lit(ring.stratum), r, c)
        drawCircle(Brush.radialGradient(0.72f to Color.Transparent, 1f to ink.copy(alpha = 0.5f), center = c, radius = r), r, c)
        drawCircle(ink.copy(alpha = 0.7f), r, c, style = Stroke(1.2f * u))
        rotate(tickTurn(i), pivot = c) {
            drawCircle(
                ink.copy(alpha = 0.45f), r - 4f * u, c,
                style = Stroke(width = 5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.9f * u, 2.6f * u))),
            )
        }
    }
    val oc = Offset(Well.oculus.cx * s, Well.oculus.cy * s)
    val or = Well.oculus.r * s
    drawCircle(Brush.radialGradient(0.55f to Color.White, 1f to oculusTint, center = oc, radius = or), or, oc)
    drawCircle(ink.copy(alpha = 0.7f), or + 1f * u, oc, style = Stroke(1.2f * u))
}

/** A soft ring of the glory's pastels, blurred, filling [modifier]'s box. */
@Composable
fun GloryHalo(modifier: Modifier = Modifier, ringFraction: Float = 0.14f, blur: Dp = 5.dp, alpha: Float = 0.95f) {
    Canvas(modifier.blur(blur, BlurredEdgeTreatment.Unbounded)) {
        val stroke = size.minDimension * ringFraction
        drawCircle(
            Brush.sweepGradient(Glory.colors, center),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(stroke),
            alpha = alpha,
        )
    }
}

/**
 * The well, square, with a glory around the oculus and [oculus] content inside it. [overlay] draws on top of
 * the rings and the glory (beads, labels) with the canvas side length in pixels. [tint] colours the oculus
 * floor's edge.
 */
@Composable
fun WellView(
    modifier: Modifier = Modifier,
    tickTurn: (Int) -> Float = { 0f },
    tint: Color = Color(0xFFEFEAFF),
    overlay: DrawScope.(side: Float) -> Unit = {},
    oculus: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        Canvas(Modifier.size(side)) { drawWell(tickTurn, oculusTint = tint) }
        val o = Well.oculus
        // The glory hugs the oculus rim and stays inside the innermost ring, so that ring and its label stay readable.
        val halo = side * (o.r * 2f * GLORY_SPAN)
        GloryHalo(Modifier.offset(side * o.cx - halo / 2, side * o.cy - halo / 2).size(halo), ringFraction = 0.10f, blur = 3.dp, alpha = 0.85f)
        Canvas(Modifier.size(side)) { overlay(size.minDimension) }
        Box(
            Modifier.offset(side * (o.cx - o.r), side * (o.cy - o.r)).size(side * (o.r * 2f)).clip(CircleShape),
            contentAlignment = Alignment.Center,
            content = oculus,
        )
    }
}

/** The glory's outer diameter as a multiple of the oculus diameter. */
private const val GLORY_SPAN = 1.32f

/** Where [RingArc] draws its band in a box of [size]: the ring's centre and radius, in pixels. */
private class ArcGeometry(size: Size, bandFromBottomPx: Float) {
    val u = size.width / 300f
    val r = 368f * u
    val c = Offset(size.width / 2f, size.height - bandFromBottomPx - r)

    /** Bead [i] of [n], left to right in catalog order, spread over 40° around the band's lowest point. */
    fun bead(i: Int, n: Int): Offset {
        val deg = if (n <= 1) 0.0 else -20.0 + i * 40.0 / (n - 1)
        val a = (90.0 - deg) * PI / 180.0
        return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    }
}

/** The tunnels that share [stratum]'s band, in catalog order; Explore's side shaft has its own. */
private fun bandMates(stratum: Stratum?) = if (stratum == null) emptyList() else TunnelCatalog.all.filter { it.stratum == stratum }

/**
 * A tunnel's header band: you stand on [stratum]'s ring, a sweep of its light across the screen with the
 * group above showing below it and the one beneath faint above (Explore, a side shaft, shows only its own).
 * Beads are that group's tunnels, left to right in list order; [tunnelId] is lit, and [onLitBead] receives its
 * centre in root coordinates (the scan's bead drop starts there). The band's lowest point sits [bandFromBottom]
 * above the bottom of the box.
 */
@Composable
fun RingArc(
    stratum: Stratum?,
    tunnelId: String?,
    color: Color,
    modifier: Modifier = Modifier,
    bandFromBottom: Dp = 26.dp,
    onLitBead: (Offset) -> Unit = {},
) {
    val ink = GlassColors.void
    val mates = remember(stratum) { bandMates(stratum) }
    val density = LocalDensity.current
    Canvas(
        modifier.onGloballyPositioned { coords ->
            val i = mates.indexOfFirst { it.id == tunnelId }
            if (i >= 0) {
                val g = ArcGeometry(Size(coords.size.width.toFloat(), coords.size.height.toFloat()), with(density) { bandFromBottom.toPx() })
                onLitBead(coords.positionInRoot() + g.bead(i, mates.size))
            }
        },
    ) {
        val g = ArcGeometry(size, bandFromBottom.toPx())
        val u = g.u
        val r = g.r
        val c = g.c
        val side = stratum == Stratum.EXPLORE
        val above = if (side) null else stratum?.let { s -> Stratum.entries.getOrNull(s.ordinal - 1) }
        val below = if (side) null else stratum?.let { s -> Stratum.entries.getOrNull(s.ordinal + 1)?.takeIf { it != Stratum.EXPLORE } }
        if (above != null) drawCircle(StratumColors.of(above).copy(alpha = 0.55f), r + 26f * u, c, style = Stroke(16f * u))
        if (below != null) drawCircle(StratumColors.of(below).copy(alpha = 0.35f), r - 24f * u, c, style = Stroke(8f * u))
        drawCircle(color.copy(alpha = 0.35f), r, c, style = Stroke(40f * u))
        drawCircle(color, r, c, style = Stroke(22f * u))
        drawCircle(ink.copy(alpha = 0.45f), r, c, style = Stroke(12f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.1f * u, 3.2f * u))))
        drawCircle(ink.copy(alpha = 0.7f), r + 11f * u, c, style = Stroke(1.2f * u))
        drawCircle(ink.copy(alpha = 0.7f), r - 11f * u, c, style = Stroke(1.2f * u))
        mates.forEachIndexed { i, t ->
            val p = g.bead(i, mates.size)
            val here = t.id == tunnelId
            if (here) drawCircle(Color.White.copy(alpha = 0.45f), 9f * u, p)
            drawCircle(Color.White, (if (here) 4.8f else 3f) * u, p)
            drawCircle(ink, (if (here) 4.8f else 3f) * u, p, style = Stroke((if (here) 1.6f else 1f) * u))
        }
    }
}

/**
 * The scan's opening: the tunnel's bead lifts off the band at [from], falls to [to] (the glory's centre) and
 * splashes, a ring of light spreading out as the glory blooms there. [progress] runs 0..1; nothing is drawn
 * outside it. Coordinates are in this canvas's space.
 */
fun DrawScope.drawBeadDrop(from: Offset, to: Offset, progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val bead = 6.dp.toPx()
    val glow = Glory.colors
    when {
        progress < 0.25f -> {
            val k = progress / 0.25f
            val p = from + Offset(0f, -6.dp.toPx() * k)
            drawCircle(Color.White.copy(alpha = 0.35f * k), bead * (1.5f + 2f * k), p)
            drawCircle(Color.White, bead * (1f + 0.5f * k), p)
        }
        progress < 0.78f -> {
            val k = (progress - 0.25f) / 0.53f
            val fall = k * k // gravity: slow off the band, fast into the water
            val start = from + Offset(0f, -6.dp.toPx())
            val p = start + (to - start) * fall
            // A short trail of the glory's colours behind the drop.
            for (i in 1..4) {
                val q = start + (to - start) * (fall - i * 0.035f).coerceAtLeast(0f)
                drawCircle(glow[i % glow.size].copy(alpha = 0.35f - i * 0.07f), bead * (1.2f - i * 0.15f), q)
            }
            drawCircle(Color.White.copy(alpha = 0.3f), bead * 2.6f, p)
            drawCircle(Color.White, bead * (1.5f - 0.4f * k), p)
        }
        else -> {
            val k = (progress - 0.78f) / 0.22f
            drawCircle(Color.White.copy(alpha = 0.8f * (1f - k)), bead * (1f + 3f * k), to)
            drawCircle(
                Brush.sweepGradient(glow, to), radius = 20.dp.toPx() + 70.dp.toPx() * k, center = to,
                style = Stroke(2.dp.toPx() * (1f - k) + 0.5f), alpha = 1f - k,
            )
        }
    }
}

/**
 * The scan in progress: the glory floating on the water with ripples spreading out from it, [done] of
 * [total] items inside it, and [caption] (tunnel and current item) under it. No frame, so it sits in the
 * screen like the rest of the well. [total] 0 means the tunnel has not reported a count yet.
 */
@Composable
fun GloryScan(
    done: Int,
    total: Int,
    caption: String,
    modifier: Modifier = Modifier,
    enterDelayMillis: Int = 0,
    onCenter: (Offset) -> Unit = {},
) {
    val still = rememberReducedMotion()
    // The glory blooms in where the bead lands, so it waits for the drop before it appears.
    val enter = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) {
            delay(enterDelayMillis.toLong())
            enter.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }
    val t = rememberInfiniteTransition(label = "glory")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4_500, easing = LinearEasing)), label = "ripple")
    val breathe by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "breathe")
    val rip = if (still) 0.35f else ripple
    val glow = if (still) 1f else breathe

    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(200.dp)
                .onGloballyPositioned { onCenter(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
                .graphicsLayer {
                    alpha = enter.value
                    scaleX = 0.55f + 0.45f * enter.value
                    scaleY = 0.55f + 0.45f * enter.value
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r0 = size.minDimension * 0.30f
                for (k in 0 until 2) {
                    val p = (rip + k / 2f) % 1f
                    drawCircle(
                        Color.White.copy(alpha = (1f - p) * 0.6f), r0 * (0.75f + 0.75f * p),
                        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))),
                    )
                }
            }
            GloryHalo(Modifier.size(150.dp), ringFraction = 0.16f, blur = 4.dp, alpha = glow)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (total > 0) "$done" else "…", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light, fontSize = 36.sp, color = GlassColors.text)
                Text(
                    if (total > 0) "OF $total" else "STARTING",
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.4.sp, color = GlassColors.dim,
                )
            }
        }
        Text(
            caption, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(Color.White.copy(alpha = 0.18f))) {
            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(2.dp).background(Brush.horizontalGradient(Glory.colors)))
        }
    }
}

/** A pill-shaped primary action lit with the corridor's spectrum. */
@Composable
fun SpectrumButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF1F0A00)),
    ) {
        Box(
            Modifier.fillMaxWidth().background(Brush.horizontalGradient(Spectrum.primary)).padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, fontWeight = FontWeight.SemiBold) }
    }
}

/** The Prism edge button: dark glass with a rim of the corridor's spectrum. Used for Scan and quieter actions. */
@Composable
fun PrismButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(Color(0xFF170D50))
            .border(1.5.dp, Brush.horizontalGradient(Spectrum.rim), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (enabled) GlassColors.text else GlassColors.dim)
    }
}
