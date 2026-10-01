package io.github.stronghorse44.tunnels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo

enum class Side { RIGHT, LEFT, ABOVE, BELOW }

/** A station's spot on the map grid, and which side its label sits on. */
data class MapStation(val tunnelId: String, val x: Float, val y: Float, val side: Side)

data class MapLine(
    val line: MetroLine,
    val path: List<Offset>,
    val stations: List<MapStation>,
    /** Where the line's name badge goes: at a terminus, on [badgeSide]. */
    val badgeAt: Offset,
    val badgeSide: Side,
)

/**
 * The schematic map, on a grid [WIDTH] units wide. Five lines meet at Central; Explore runs on its own,
 * apart from the security network. Coordinates were laid out so no labels collide on a phone-width screen.
 */
object MetroLayout {
    const val WIDTH = 10f
    const val HEIGHT = 22.6f
    val central = Offset(5f, 10f)

    val lines = listOf(
        MapLine(
            MetroLine.FILES, listOf(central, Offset(5f, 8f), Offset(2f, 5f), Offset(2f, 1.6f)),
            listOf(MapStation(TunnelCatalog.INSTALLER, 2f, 4.4f, Side.RIGHT), MapStation(TunnelCatalog.UNZIP, 2f, 2.2f, Side.RIGHT)),
            Offset(2f, 1.6f), Side.ABOVE,
        ),
        MapLine(
            MetroLine.INSPECT, listOf(central, Offset(8f, 7f), Offset(8f, 1.2f)),
            listOf(
                MapStation("permissions", 8f, 6.2f, Side.LEFT),
                MapStation("apk_excavation", 8f, 4.6f, Side.LEFT),
                MapStation("doors", 8f, 3.0f, Side.LEFT),
                MapStation("hardening", 8f, 1.4f, Side.LEFT),
            ),
            Offset(8f, 1.2f), Side.ABOVE,
        ),
        MapLine(
            MetroLine.SYSTEM, listOf(central, Offset(8f, 13f), Offset(8f, 18.8f)),
            listOf(
                MapStation("system_packages", 8f, 14f, Side.LEFT),
                MapStation("trust_store", 8f, 15.6f, Side.LEFT),
                MapStation("silicon", 8f, 17.2f, Side.LEFT),
                MapStation("deep_mode", 8f, 18.8f, Side.LEFT),
            ),
            Offset(8f, 18.8f), Side.BELOW,
        ),
        MapLine(
            MetroLine.NETWORK, listOf(central, Offset(2f, 13f), Offset(2f, 18.8f)),
            listOf(
                MapStation("traffic", 2f, 14.6f, Side.RIGHT),
                MapStation("surroundings", 2f, 16.6f, Side.RIGHT),
                MapStation("home_network", 2f, 18.6f, Side.RIGHT),
            ),
            Offset(2f, 18.8f), Side.BELOW,
        ),
        MapLine(
            MetroLine.ACTIVITY, listOf(central, Offset(0.8f, 10f)),
            listOf(MapStation("notifications", 3.1f, 10f, Side.ABOVE), MapStation("timeline", 1.1f, 10f, Side.BELOW)),
            Offset(0.8f, 9.15f), Side.ABOVE,
        ),
        MapLine(
            MetroLine.EXPLORE, listOf(Offset(1f, 21.2f), Offset(9f, 21.2f)),
            listOf(
                MapStation("sensors", 1.4f, 21.2f, Side.BELOW),
                MapStation("cameras", 3.8f, 21.2f, Side.BELOW),
                MapStation("satellites", 6.2f, 21.2f, Side.BELOW),
                MapStation("radio", 8.6f, 21.2f, Side.BELOW),
            ),
            Offset(1f, 21.2f), Side.ABOVE,
        ),
    )
}

/** Clickable glass metro map: tap a station or its label to open it. */
@Composable
fun MetroMap(status: (TunnelInfo) -> String, onStation: (TunnelInfo) -> Unit, modifier: Modifier = Modifier) {
    val stations = MetroLayout.lines.flatMap { l -> l.stations.map { l to it } }
        .mapNotNull { (l, s) -> TunnelCatalog.byId(s.tunnelId)?.let { Triple(l, s, it) } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val unit = maxWidth / MetroLayout.WIDTH
        Box(Modifier.fillMaxWidth().height(unit * MetroLayout.HEIGHT)) {
            Canvas(Modifier.fillMaxWidth().height(unit * MetroLayout.HEIGHT)) {
                val u = size.width / MetroLayout.WIDTH
                MetroLayout.lines.forEach { drawTube(it, u) }
                drawCentral(MetroLayout.central * u)
                stations.forEach { (l, s, t) -> drawStation(Offset(s.x, s.y) * u, LineColors.of(l.line), t.isLive) }
            }

            // Labels, station hit targets and line badges, positioned on the same grid.
            Layout(
                content = {
                    stations.forEach { (l, s, t) -> StationLabel(t, s.side, LineColors.of(l.line), status(t)) { onStation(t) } }
                    stations.forEach { (_, _, t) ->
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onStation(t) })
                    }
                    MetroLayout.lines.forEach { LineBadge(it.line) }
                },
                modifier = Modifier.fillMaxWidth().height(unit * MetroLayout.HEIGHT),
            ) { measurables, constraints ->
                val u = constraints.maxWidth / MetroLayout.WIDTH
                val gap = 14.dp.roundToPx()
                val placeables = measurables.map { it.measure(Constraints()) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    fun place(i: Int, at: Offset, side: Side, g: Int) {
                        val p = placeables[i]
                        val x = at.x * u
                        val y = at.y * u
                        val (px, py) = when (side) {
                            Side.RIGHT -> x + g to y - p.height / 2f
                            Side.LEFT -> x - g - p.width to y - p.height / 2f
                            Side.ABOVE -> x - p.width / 2f to y - g - p.height
                            Side.BELOW -> x - p.width / 2f to y + g
                        }
                        p.place(px.toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)), py.toInt())
                    }
                    val n = stations.size
                    stations.forEachIndexed { i, (_, s, _) -> place(i, Offset(s.x, s.y), s.side, gap) }
                    stations.forEachIndexed { i, (_, s, _) ->
                        val p = placeables[n + i]
                        p.place((s.x * u - p.width / 2f).toInt(), (s.y * u - p.height / 2f).toInt())
                    }
                    MetroLayout.lines.forEachIndexed { i, l -> place(2 * n + i, l.badgeAt, l.badgeSide, 16.dp.roundToPx()) }
                }
            }
        }
    }
}

@Composable
private fun StationLabel(t: TunnelInfo, side: Side, color: Color, status: String, onClick: () -> Unit) {
    val align = when (side) {
        Side.RIGHT -> Alignment.Start
        Side.LEFT -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .widthIn(max = 168.dp),
        horizontalAlignment = align,
    ) {
        Text(
            t.title,
            fontSize = 13.sp,
            fontWeight = if (t.isLive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (t.isLive) GlassColors.text else GlassColors.text.copy(alpha = 0.6f),
            maxLines = 1,
        )
        if (t.isLive) {
            Text(
                status,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (side == Side.LEFT) TextAlign.End else TextAlign.Start,
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
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.border(1.2.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
        Text(line.label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = color.copy(alpha = 0.8f))
    }
}

/** A line as liquid in a glass tube: frosted body, glow, colored liquid and a sheen along the top edge. */
private fun DrawScope.drawTube(line: MapLine, u: Float) {
    val color = LineColors.of(line.line)
    val live = line.stations.any { TunnelCatalog.byId(it.tunnelId)?.isLive == true }
    val path = Path().apply {
        line.path.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * u, p.y * u) else lineTo(p.x * u, p.y * u) }
    }
    val corners = PathEffect.cornerPathEffect(1.2f * u)
    fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = corners)

    drawPath(path, Color.White.copy(alpha = 0.09f), style = stroke(20.dp.toPx()))
    if (live) drawPath(path, color.copy(alpha = 0.25f), style = stroke(15.dp.toPx()))
    drawPath(path, color.copy(alpha = if (live) 0.95f else 0.55f), style = stroke(6.dp.toPx()))
    translate(-4.dp.toPx(), -2.dp.toPx()) {
        drawPath(path, Color.White.copy(alpha = 0.22f), style = stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawCentral(c: Offset) {
    val w = 64.dp.toPx()
    val h = 28.dp.toPx()
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
