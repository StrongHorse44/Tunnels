package io.github.stronghorse44.tunnels.metro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacerTest {
    private val bounds = Box(0f, 0f, 400f, 400f)

    private fun placer(obstacles: Obstacles = Obstacles()) = LabelPlacer(bounds, obstacles, step = 2f, margin = 3f, dp = 1f)

    private fun label(x: Float, y: Float, side: Side, vararg sizes: Dim, slide: Float = 20f) =
        LabelSpec(Pt(x, y), MetroScene.sidesFor(side), sizes.toList(), gap = 14f, maxSlide = slide)

    @Test
    fun geometryDistancesAndIntersections() {
        val box = Box(10f, 10f, 20f, 20f)
        assertEquals(0f, box.distanceTo(Pt(15f, 15f)))
        assertEquals(5f, box.distanceTo(Pt(25f, 15f)), 1e-4f)
        assertEquals(5f, box.distanceTo(Pt(23f, 24f)), 1e-4f)
        assertEquals(5f, Geometry.pointSegmentDistance(Pt(5f, 5f), Pt(0f, 0f), Pt(0f, 10f)), 1e-4f)
        assertEquals(5f, Geometry.pointSegmentDistance(Pt(0f, 15f), Pt(0f, 0f), Pt(0f, 10f)), 1e-4f)
        // A segment crossing the box, one passing beside it, one ending inside it.
        assertTrue(Geometry.segmentIntersectsBox(Pt(0f, 0f), Pt(30f, 30f), box))
        assertFalse(Geometry.segmentIntersectsBox(Pt(0f, 25f), Pt(30f, 25f), box))
        assertTrue(Geometry.segmentIntersectsBox(Pt(0f, 15f), Pt(12f, 15f), box))
        assertEquals(0f, Geometry.segmentBoxDistance(Pt(0f, 0f), Pt(30f, 30f), box))
        assertEquals(5f, Geometry.segmentBoxDistance(Pt(0f, 25f), Pt(30f, 25f), box), 1e-4f)
        // Diagonally off a corner: the segment on x + y = 48 passes (24, 24), 4 * sqrt(2) from the corner (20, 20).
        assertEquals(4f * Math.sqrt(2.0).toFloat(), Geometry.segmentBoxDistance(Pt(22f, 26f), Pt(26f, 22f), box), 1e-3f)
        assertTrue(box.intersects(Box(19f, 19f, 30f, 30f)))
        assertFalse(box.intersects(Box(20f, 10f, 30f, 20f)))
        assertEquals(1f, box.overlapArea(Box(19f, 19f, 30f, 30f)), 1e-4f)
    }

    @Test
    fun freeLabelTakesItsPreferredSpot() {
        val p = placer().place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f)))).single()
        assertTrue(p.clear)
        assertEquals(Side.RIGHT, p.side)
        assertEquals(0, p.variant)
        assertEquals(0f, p.slide)
        assertEquals(Box(114f, 85f, 174f, 115f), p.box)
        assertEquals(0f, p.cost)
    }

    @Test
    fun labelSlidesAwayFromAnObstacle() {
        // A box sits where the label would go, reaching down to y = 100.
        val blocker = Box(110f, 60f, 200f, 100f)
        val p = placer(Obstacles(boxes = listOf(blocker))).place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f)))).single()
        assertTrue(p.clear)
        assertEquals(Side.RIGHT, p.side)
        assertTrue("slid down below the blocker", p.box.top >= blocker.bottom + 3f)
        assertTrue(p.slide > 0f && p.slide <= 20f)
    }

    @Test
    fun labelKeepsClearOfATube() {
        // A vertical tube 10 px to the right of where the label would start.
        val tube = Capsule(Pt(140f, 0f), Pt(140f, 400f), radius = 10f)
        val p = placer(Obstacles(capsules = listOf(tube))).place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f), Dim(10f, 30f)))).single()
        assertTrue(p.clear)
        assertEquals("the narrow rendering fits between the anchor and the tube", 1, p.variant)
        assertTrue(Geometry.segmentBoxDistance(tube.a, tube.b, p.box) >= tube.radius + 3f)
    }

    @Test
    fun blockedSideFallsBackToTheOppositeOne() {
        val wall = Box(110f, 0f, 400f, 400f)
        val p = placer(Obstacles(boxes = listOf(wall))).place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f)))).single()
        assertTrue(p.clear)
        assertEquals(Side.LEFT, p.side)
        assertEquals(100f - 14f, p.box.right)
    }

    @Test
    fun impossibleLabelIsReportedWithTheLeastOverlap() {
        // Everything but a sliver is taken: no candidate is clear.
        val p = placer(Obstacles(boxes = listOf(Box(0f, 0f, 400f, 400f)))).place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f)))).single()
        assertFalse(p.clear)
    }

    @Test
    fun twoLabelsOnOneRowDoNotOverlap() {
        // Anchors face each other across a 160 px gap; both labels are 100 px wide.
        val left = label(100f, 200f, Side.RIGHT, Dim(100f, 30f), Dim(60f, 44f), Dim(60f, 30f), Dim(40f, 18f))
        val right = label(260f, 200f, Side.LEFT, Dim(100f, 30f), Dim(60f, 44f), Dim(60f, 30f), Dim(40f, 18f))
        val (a, b) = placer().place(listOf(left, right))
        assertTrue(a.clear && b.clear)
        assertFalse(a.box.inflate(3f).intersects(b.box))
        assertTrue("nobody drops the status to make room", a.variant < 3 && b.variant < 3)
    }

    @Test
    fun crowdedLabelsBecomeEvenlyCompactInsteadOfLosingStatuses() {
        // Two columns of labels facing each other, rows 30 px apart: full-width labels can never share a row,
        // wrapped or short ones can. The placer must not let a few full-width labels push the rest to bare titles.
        val labels = (0 until 6).map { i ->
            val y = 60f + i * 30f
            val variants = arrayOf(Dim(150f, 30f), Dim(70f, 44f), Dim(70f, 30f), Dim(45f, 18f))
            if (i % 2 == 0) label(60f, y, Side.RIGHT, *variants, slide = 6f) else label(340f, y, Side.LEFT, *variants, slide = 6f)
        }
        val result = placer().place(labels)
        assertTrue(result.all { it.clear })
        assertTrue("every label keeps a status: ${result.map { it.variant }}", result.all { it.variant < 3 })
        for (i in result.indices) for (j in i + 1 until result.size) {
            assertFalse("labels $i and $j overlap", result[i].box.intersects(result[j].box))
        }
    }

    @Test
    fun sameSizedVariantsCostNothingExtra() {
        // A "ready" status renders the same in every variant but the title-only one.
        val ready = Dim(50f, 30f)
        val p = placer().place(listOf(label(100f, 100f, Side.RIGHT, ready, ready, ready, Dim(40f, 18f)))).single()
        assertEquals(0, p.variant)
        assertEquals(0f, p.cost)
    }

    @Test
    fun placementIsDeterministic() {
        val labels = listOf(
            label(100f, 100f, Side.RIGHT, Dim(120f, 30f), Dim(70f, 44f)),
            label(300f, 110f, Side.LEFT, Dim(120f, 30f), Dim(70f, 44f)),
            label(100f, 140f, Side.RIGHT, Dim(120f, 30f), Dim(70f, 44f)),
        )
        assertEquals(placer().place(labels), placer().place(labels))
    }

    @Test
    fun fixedBoxesAreAvoided() {
        val taken = Box(110f, 80f, 200f, 120f)
        val p = placer().place(listOf(label(100f, 100f, Side.RIGHT, Dim(60f, 30f))), fixed = listOf(taken)).single()
        assertTrue(p.clear)
        assertFalse(p.box.intersects(taken))
    }
}
