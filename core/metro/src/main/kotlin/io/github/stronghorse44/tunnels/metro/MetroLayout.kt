package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog

/** A point on the map grid, in grid units: the map is [MetroLayout.WIDTH] units across. */
data class GridPoint(val x: Float, val y: Float)

enum class Side { RIGHT, LEFT, ABOVE, BELOW }

/** A station's spot on the grid and the side its label prefers. */
data class MapStation(val tunnelId: String, val at: GridPoint, val side: Side)

data class MapLine(
    val line: MetroLine,
    /** The tube's centre line. The tube is drawn with its corners rounded by [MetroLayout.CORNER_RADIUS]. */
    val path: List<GridPoint>,
    val stations: List<MapStation>,
    /** The terminus the line's name badge sits by, and the side it prefers. */
    val badgeAt: GridPoint,
    val badgeSide: Side,
)

/**
 * The schematic map. Five lines meet at Central; Explore runs on its own, apart from the security network.
 *
 * Labels of the left-hand lines (Files, Network) sit right of their stations and labels of the right-hand
 * lines (Inspect, System) left of theirs, so both reach into the middle of the map. The two columns take
 * turns: every station is [ROW] units below the station before it on the other side, so a long label on
 * one side never shares its row with a long label on the other. Stations keep [CORNER_RADIUS] clear of
 * tube corners, so the dot sits on the straight part of the tube. [LabelPlacer] resolves what is left,
 * such as large font sizes and narrow screens, with the labels' real measured sizes.
 */
object MetroLayout {
    const val WIDTH = 10f
    const val HEIGHT = 24.4f

    /** Rounding of tube corners, in grid units: the draw code's corner path effect and the obstacle model use it. */
    const val CORNER_RADIUS = 1f

    /** Vertical step between a station and the next one on the opposite column. */
    const val ROW = 0.9f

    private const val LEFT_X = 1.8f
    private const val RIGHT_X = 8.2f
    private const val EXPLORE_Y = 23f

    val central = GridPoint(5f, 11.2f)

    val lines: List<MapLine> = listOf(
        MapLine(
            MetroLine.FILES,
            listOf(central, GridPoint(5f, 9f), GridPoint(LEFT_X, 5.8f), GridPoint(LEFT_X, 1.7f)),
            listOf(
                MapStation(TunnelCatalog.UNZIP, GridPoint(LEFT_X, 2.5f), Side.RIGHT),
                MapStation(TunnelCatalog.INSTALLER, GridPoint(LEFT_X, 2.5f + 2 * ROW), Side.RIGHT),
            ),
            GridPoint(LEFT_X, 1.7f), Side.ABOVE,
        ),
        MapLine(
            MetroLine.INSPECT,
            listOf(central, GridPoint(RIGHT_X, 8f), GridPoint(RIGHT_X, 1.2f)),
            listOf(
                MapStation("hardening", GridPoint(RIGHT_X, 1.6f), Side.LEFT),
                MapStation("doors", GridPoint(RIGHT_X, 1.6f + 2 * ROW), Side.LEFT),
                MapStation("apk_excavation", GridPoint(RIGHT_X, 1.6f + 4 * ROW), Side.LEFT),
                MapStation("permissions", GridPoint(RIGHT_X, 1.6f + 6 * ROW), Side.LEFT),
            ),
            GridPoint(RIGHT_X, 1.2f), Side.ABOVE,
        ),
        MapLine(
            MetroLine.SYSTEM,
            listOf(central, GridPoint(RIGHT_X, 14.4f), GridPoint(RIGHT_X, 21.2f)),
            listOf(
                MapStation("system_packages", GridPoint(RIGHT_X, 15.4f), Side.LEFT),
                MapStation("trust_store", GridPoint(RIGHT_X, 15.4f + 2 * ROW), Side.LEFT),
                MapStation("silicon", GridPoint(RIGHT_X, 15.4f + 4 * ROW), Side.LEFT),
                MapStation("deep_mode", GridPoint(RIGHT_X, 15.4f + 6 * ROW), Side.LEFT),
            ),
            GridPoint(RIGHT_X, 21.2f), Side.BELOW,
        ),
        MapLine(
            MetroLine.NETWORK,
            // Drops straight down out of Central before it turns, leaving room under the Activity line for its label.
            listOf(central, GridPoint(5f, 12f), GridPoint(LEFT_X, 15.2f), GridPoint(LEFT_X, 20.3f)),
            listOf(
                MapStation("traffic", GridPoint(LEFT_X, 15.4f + ROW), Side.RIGHT),
                MapStation("surroundings", GridPoint(LEFT_X, 15.4f + 3 * ROW), Side.RIGHT),
                MapStation("home_network", GridPoint(LEFT_X, 15.4f + 5 * ROW), Side.RIGHT),
            ),
            GridPoint(LEFT_X, 20.3f), Side.BELOW,
        ),
        MapLine(
            MetroLine.ACTIVITY,
            listOf(central, GridPoint(0.7f, central.y)),
            listOf(
                // One label below the line and one above: side by side they would not fit between the edge and Central.
                MapStation("notifications", GridPoint(1.5f, central.y), Side.BELOW),
                MapStation("timeline", GridPoint(3.2f, central.y), Side.ABOVE),
            ),
            GridPoint(0.7f, central.y), Side.ABOVE,
        ),
        MapLine(
            MetroLine.EXPLORE,
            listOf(GridPoint(1f, EXPLORE_Y), GridPoint(9f, EXPLORE_Y)),
            listOf(
                MapStation("sensors", GridPoint(1.4f, EXPLORE_Y), Side.BELOW),
                MapStation("cameras", GridPoint(3.8f, EXPLORE_Y), Side.BELOW),
                MapStation("satellites", GridPoint(6.2f, EXPLORE_Y), Side.BELOW),
                MapStation("radio", GridPoint(8.6f, EXPLORE_Y), Side.BELOW),
            ),
            GridPoint(1f, EXPLORE_Y), Side.ABOVE,
        ),
    )

    /** Every station with its line, in drawing order. */
    val stations: List<Pair<MapLine, MapStation>> = lines.flatMap { l -> l.stations.map { l to it } }

    /** The tube's centre line with its corners rounded, sampled into short straight pieces (grid units). */
    fun tubeOutline(line: MapLine, samplesPerCorner: Int = 8): List<GridPoint> =
        RoundedPolyline.sample(line.path.map { Pt(it.x, it.y) }, CORNER_RADIUS, samplesPerCorner).map { GridPoint(it.x, it.y) }
}

/**
 * The shape a corner path effect gives a polyline (Skia's SkCornerPathEffect, which Android's
 * CornerPathEffect and Compose's `PathEffect.cornerPathEffect` use): every inner corner becomes a quadratic
 * curve from [radius] before the corner to [radius] after it, with the corner as its control point. A leg
 * shorter than two radii lends half its length to each corner instead. The end points stay where they are.
 */
object RoundedPolyline {
    fun sample(path: List<Pt>, radius: Float, samplesPerCorner: Int): List<Pt> {
        if (path.size < 3) return path
        fun step(a: Pt, b: Pt): Float {
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            return if (len <= 2 * radius) 0.5f else radius / len
        }
        fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        val out = ArrayList<Pt>()
        out += path.first()
        for (i in 1 until path.size - 1) {
            val prev = path[i - 1]
            val corner = path[i]
            val next = path[i + 1]
            val start = lerp(corner, prev, step(prev, corner))
            val end = lerp(corner, next, step(corner, next))
            for (s in 0..samplesPerCorner) {
                val t = s.toFloat() / samplesPerCorner
                val u = 1 - t
                out += Pt(
                    u * u * start.x + 2 * u * t * corner.x + t * t * end.x,
                    u * u * start.y + 2 * u * t * corner.y + t * t * end.y,
                )
            }
        }
        out += path.last()
        return out
    }
}
