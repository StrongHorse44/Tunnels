package io.github.stronghorse44.tunnels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.metro.Dim
import io.github.stronghorse44.tunnels.metro.MapLine
import io.github.stronghorse44.tunnels.metro.MetroLayout
import io.github.stronghorse44.tunnels.metro.MetroScene
import io.github.stronghorse44.tunnels.metro.Pt
import io.github.stronghorse44.tunnels.metro.Side
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Station titles and statuses set their own line height: inheriting the theme's 24 sp body line height made every label about 50 dp tall. */
private val TitleStyle = TextStyle(fontSize = 13.sp, lineHeight = MetroScene.TITLE_LINE_SP.sp, letterSpacing = 0.sp)
private val StatusStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = MetroScene.STATUS_LINE_SP.sp, letterSpacing = 0.sp)
private val BadgeCodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp)
private val BadgeNameStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.5.sp)

/**
 * Renderings of a station label, preferred first; [LabelPlacer][io.github.stronghorse44.tunnels.metro.LabelPlacer]
 * picks one per station. "12 findings · 3 WARN" is one line, then two lines, then only "3 WARN"; other statuses
 * wrap, then shorten with an ellipsis. The title alone is the last resort.
 */
private enum class LabelVariant { ONE_LINE, WRAPPED, SHORT, TITLE_ONLY }

/**
 * Clickable glass metro map: tap a station or its label to open it. Every label is measured in all its
 * renderings, then [MetroScene] places each one clear of the others, the tubes and the line badges.
 * [onLaidOut] receives each placement, for tests and debugging.
 */
@Composable
fun MetroMap(
    status: (TunnelInfo) -> String,
    isLive: (TunnelInfo) -> Boolean,
    onStation: (TunnelInfo) -> Unit,
    modifier: Modifier = Modifier,
    onLaidOut: ((MetroScene, MetroScene.Solution) -> Unit)? = null,
) {
    val stations = MetroLayout.stations.map { (l, s) -> Triple(l, s, TunnelCatalog.byId(s.tunnelId) ?: error("No catalog entry for ${s.tunnelId}")) }

    Box(modifier.fillMaxWidth()) {
        // The labels decide the height (large text can push the bottom ones below the grid); the drawing fills it.
        Canvas(Modifier.matchParentSize()) {
            val scene = MetroScene(size.width, density, fontScale)
            MetroLayout.lines.forEach { l -> drawTube(scene, l, l.stations.any { s -> TunnelCatalog.byId(s.tunnelId)?.let(isLive) == true }) }
            drawCentral(scene.px(MetroLayout.central).toOffset())
            stations.forEach { (l, s, t) -> drawStation(scene.px(s.at).toOffset(), LineColors.of(l.line), isLive(t)) }
        }

        Layout(
            content = {
                stations.forEach { (l, s, t) ->
                    val live = isLive(t)
                    val text = if (live) status(t) else null
                    LabelVariant.entries.forEach { v -> StationLabel(t, live, s.side, LineColors.of(l.line), text, v) { onStation(t) } }
                }
                stations.forEach { (_, _, t) -> Box(Modifier.size(44.dp).clip(CircleShape).clickable { onStation(t) }) }
                MetroLayout.lines.forEach { LineBadge(it.line) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val n = stations.size
            val variants = LabelVariant.entries.size
            val loose = Constraints(maxWidth = constraints.maxWidth)
            val placeables = measurables.map { it.measure(loose) }
            fun dim(p: Placeable) = Dim(p.width.toFloat(), p.height.toFloat())

            val scene = MetroScene(constraints.maxWidth.toFloat(), density, fontScale)
            val solution = scene.solve(
                labelSizes = List(n) { i -> List(variants) { v -> dim(placeables[i * variants + v]) } },
                badgeSizes = List(MetroLayout.lines.size) { j -> dim(placeables[n * variants + n + j]) },
            )
            onLaidOut?.invoke(scene, solution)

            layout(constraints.maxWidth, ceil(solution.heightPx).toInt()) {
                solution.labels.forEachIndexed { i, p ->
                    placeables[i * variants + p.variant].place(p.box.left.roundToInt(), p.box.top.roundToInt())
                }
                stations.forEachIndexed { i, (_, s, _) ->
                    val target = placeables[n * variants + i]
                    val c = scene.px(s.at)
                    target.place((c.x - target.width / 2f).roundToInt(), (c.y - target.height / 2f).roundToInt())
                }
                solution.badges.forEachIndexed { j, p ->
                    placeables[n * variants + n + j].place(p.box.left.roundToInt(), p.box.top.roundToInt())
                }
            }
        }
    }
}

private fun Pt.toOffset() = Offset(x, y)

@Composable
private fun StationLabel(t: TunnelInfo, live: Boolean, side: Side, color: Color, status: String?, variant: LabelVariant, onClick: () -> Unit) {
    val align = when (side) {
        Side.RIGHT -> Alignment.Start
        Side.LEFT -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val textAlign = when (side) {
        Side.RIGHT -> TextAlign.Start
        Side.LEFT -> TextAlign.End
        else -> TextAlign.Center
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = align,
    ) {
        Text(
            t.title,
            style = LocalTextStyle.current.merge(TitleStyle),
            fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
            color = if (live) GlassColors.text else GlassColors.text.copy(alpha = 0.6f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null && variant != LabelVariant.TITLE_ONLY) {
            // Finding counts read "12 findings · 3 WARN": the part after the dot is the worst severity.
            val parts = status.split(" · ")
            val wrapped = variant == LabelVariant.WRAPPED
            Text(
                when {
                    wrapped && parts.size > 1 -> parts.joinToString("\n")
                    // Shortened, a finding count keeps its worst severity and "last: x" keeps x.
                    variant == LabelVariant.SHORT -> parts.last().removePrefix("last: ")
                    else -> status
                },
                style = LocalTextStyle.current.merge(StatusStyle),
                color = color,
                maxLines = if (wrapped) maxOf(parts.size, 2) else 1,
                softWrap = wrapped,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                // Only the status is capped; a title is never cut.
                modifier = Modifier.widthIn(
                    max = when (variant) {
                        LabelVariant.ONE_LINE -> 200.dp
                        LabelVariant.WRAPPED -> 124.dp
                        else -> 108.dp
                    },
                ),
            )
        }
    }
}

@Composable
private fun LineBadge(line: MetroLine) {
    val color = LineColors.of(line)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            line.code,
            style = LocalTextStyle.current.merge(BadgeCodeStyle),
            color = color,
            modifier = Modifier.border(1.2.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
        Text(line.label.uppercase(), style = LocalTextStyle.current.merge(BadgeNameStyle), color = color.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
    }
}

/** A line as liquid in a glass tube: frosted body, glow, colored liquid and a sheen along the top edge. */
private fun DrawScope.drawTube(scene: MetroScene, line: MapLine, live: Boolean) {
    val color = LineColors.of(line.line)
    val path = Path().apply {
        line.path.map(scene::px).forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    }
    // The same rounding MetroScene models, so labels keep clear of the curve that is actually drawn.
    val corners = PathEffect.cornerPathEffect(scene.cornerRadiusPx)
    fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = corners)

    drawPath(path, Color.White.copy(alpha = 0.09f), style = stroke(2 * MetroScene.TUBE_RADIUS_DP.dp.toPx()))
    if (live) drawPath(path, color.copy(alpha = 0.25f), style = stroke(15.dp.toPx()))
    drawPath(path, color.copy(alpha = if (live) 0.95f else 0.55f), style = stroke(6.dp.toPx()))
    translate(-4.dp.toPx(), -2.dp.toPx()) {
        drawPath(path, Color.White.copy(alpha = 0.22f), style = stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawCentral(c: Offset) {
    val w = MetroScene.CENTRAL_WIDTH_DP.dp.toPx()
    val h = MetroScene.CENTRAL_HEIGHT_DP.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), c, w), w, c)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White, Color(0xFFC9D6E6)), startY = c.y - h / 2, endY = c.y + h / 2),
        topLeft = Offset(c.x - w / 2, c.y - h / 2),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2),
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.9f),
        topLeft = Offset(c.x - w / 2, c.y - h / 2),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2),
        style = Stroke(1.5.dp.toPx()),
    )
}

private fun DrawScope.drawStation(c: Offset, color: Color, live: Boolean) {
    if (live) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), c, 20.dp.toPx()), 20.dp.toPx(), c)
        drawCircle(Brush.radialGradient(listOf(Color.White, color), c + Offset(-2.dp.toPx(), -2.dp.toPx()), 11.dp.toPx()), 9.dp.toPx(), c)
        drawCircle(Color.White.copy(alpha = 0.9f), 2.4.dp.toPx(), c + Offset(-3.dp.toPx(), -3.dp.toPx()))
    } else {
        drawCircle(GlassColors.void, 7.5.dp.toPx(), c)
        drawCircle(color.copy(alpha = 0.85f), 7.5.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
    }
}
