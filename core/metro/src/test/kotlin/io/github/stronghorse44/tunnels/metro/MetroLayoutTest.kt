package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MetroLayoutTest {
    @Test
    fun everyTunnelHasExactlyOneStation() {
        val ids = MetroLayout.stations.map { it.second.tunnelId }
        assertEquals("no station twice", ids.size, ids.toSet().size)
        // Crossroads joins the other tunnels: on the map it is Central itself, where the lines meet, not a station.
        // Backups is only on the well home: this older map has no free spot on the Files line for another label.
        assertEquals(TunnelCatalog.all.map { it.id }.toSet() - TunnelCatalog.CROSSROADS - TunnelCatalog.BACKUPS, ids.toSet())
        MetroLayout.stations.forEach { (line, s) ->
            assertEquals("${s.tunnelId} sits on its catalog line", TunnelCatalog.byId(s.tunnelId)!!.line, line.line)
        }
        assertEquals(MetroLine.entries.toSet(), MetroLayout.lines.map { it.line }.toSet())
    }

    @Test
    fun stationsSitOnTheStraightPartOfTheirTube() {
        MetroLayout.stations.forEach { (line, s) ->
            val outline = MetroLayout.tubeOutline(line)
            val d = outline.zipWithNext().minOf { (a, b) -> distance(s.at, a, b) }
            assertTrue("${s.tunnelId} is ${"%.3f".format(d)} units off its tube", d < 0.01f)
            // Not inside a rounded corner: at least the corner radius from every inner corner of the path.
            line.path.drop(1).dropLast(1).forEach { corner ->
                val away = hypot(s.at.x - corner.x, s.at.y - corner.y)
                assertTrue("${s.tunnelId} sits in the curve at $corner", away >= MetroLayout.CORNER_RADIUS - 1e-4f)
            }
        }
    }

    @Test
    fun theTwoColumnsTakeTurns() {
        // A left-column station never shares a row with a right-column one, so their labels can both be long.
        val left = MetroLayout.stations.filter { it.second.side == Side.RIGHT }.map { it.second.at.y }
        val right = MetroLayout.stations.filter { it.second.side == Side.LEFT }.map { it.second.at.y }
        for (l in left) for (r in right) {
            assertTrue("rows $l and $r", abs(l - r) >= MetroLayout.ROW - 1e-4f)
        }
    }

    @Test
    fun roundedPolylineKeepsItsEndsAndCutsCorners() {
        val path = listOf(Pt(0f, 0f), Pt(0f, 10f), Pt(10f, 10f))
        val out = RoundedPolyline.sample(path, radius = 2f, samplesPerCorner = 4)
        assertEquals(path.first(), out.first())
        assertEquals(path.last(), out.last())
        // The curve starts 2 before the corner, ends 2 after it, and its middle is a quarter of the way to the corner.
        assertEquals(Pt(0f, 8f), out[1])
        assertEquals(Pt(2f, 10f), out[5])
        val mid = out[3]
        assertEquals(0.5f, mid.x, 1e-4f)
        assertEquals(9.5f, mid.y, 1e-4f)
        // A leg shorter than two radii lends half its length to the corner.
        val short = RoundedPolyline.sample(listOf(Pt(0f, 0f), Pt(0f, 2f), Pt(2f, 2f)), radius = 2f, samplesPerCorner = 2)
        assertEquals(Pt(0f, 1f), short[1])
        assertEquals(Pt(1f, 2f), short[3])
        // Fewer than three points have no corner.
        assertEquals(listOf(Pt(0f, 0f), Pt(1f, 1f)), RoundedPolyline.sample(listOf(Pt(0f, 0f), Pt(1f, 1f)), 2f, 4))
    }

    @Test
    fun theGridStretchesOnlyForLargeTextOnNarrowScreens() {
        // A 411 dp phone (375 dp map) at the default text size: no stretch; a 360 dp one (324 dp map) barely.
        assertEquals(1f, MetroScene.verticalStretch(37.5f, 1f))
        assertTrue(MetroScene.verticalStretch(32.4f, 1f) < 1.05f)
        val big = MetroScene.verticalStretch(32.4f, 1.3f)
        assertTrue(big > 1.2f)
        // Stretched, a one-line label fits the step between rows to within the tolerance.
        val label = (MetroScene.TITLE_LINE_SP + MetroScene.STATUS_LINE_SP) * 1.3f + MetroScene.LABEL_PADDING_DP
        assertTrue(label + MetroScene.MARGIN_DP - MetroScene.ROW_TOLERANCE_DP <= MetroLayout.ROW * 32.4f * big + 1e-3f)
    }

    private fun distance(p: GridPoint, a: GridPoint, b: GridPoint): Float =
        Geometry.pointSegmentDistance(Pt(p.x, p.y), Pt(a.x, a.y), Pt(b.x, b.y))
}
