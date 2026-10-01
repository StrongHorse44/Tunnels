package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.MetroLine

/**
 * The map in pixels for one width, density and font scale: where the tubes, dots and Central are drawn,
 * which of them labels must avoid, and the placement of station labels and line badges from their measured
 * sizes. The Compose map draws from it and the tests check it, so what the tests check is what the phone draws.
 *
 * The grid is [MetroLayout.WIDTH] units across the width. Vertically it stretches by [yScale] when the font
 * scale makes a label taller than the step between rows allows on this width, so large text gets more room
 * instead of being squeezed; at the default text size on a phone 360 dp or wider it does not stretch.
 */
class MetroScene(val widthPx: Float, val dp: Float, val fontScale: Float = 1f) {
    val unit: Float = widthPx / MetroLayout.WIDTH
    val yScale: Float = verticalStretch(unit / dp, fontScale)
    val heightPx: Float = unit * MetroLayout.HEIGHT * yScale

    fun px(p: GridPoint) = Pt(p.x * unit, p.y * unit * yScale)

    /** A line's centre in pixels with its corners rounded exactly as the draw code's corner path effect rounds them. */
    fun tubeOutline(line: MapLine, samplesPerCorner: Int = 8): List<Pt> =
        RoundedPolyline.sample(line.path.map(::px), cornerRadiusPx, samplesPerCorner)

    /** The radius the draw code passes to its corner path effect. */
    val cornerRadiusPx: Float get() = MetroLayout.CORNER_RADIUS * unit

    val centralBox: Box = px(MetroLayout.central).let { c ->
        Box(c.x - CENTRAL_WIDTH_DP / 2 * dp, c.y - CENTRAL_HEIGHT_DP / 2 * dp, c.x + CENTRAL_WIDTH_DP / 2 * dp, c.y + CENTRAL_HEIGHT_DP / 2 * dp)
    }

    /** Central is a pill: a capsule as long as the box minus its round ends. */
    val centralCapsule: Capsule = centralBox.let { b ->
        val r = b.height / 2
        Capsule(Pt(b.left + r, b.top + r), Pt(b.right - r, b.top + r), r)
    }

    val obstacles: Obstacles = Obstacles(
        capsules = MetroLayout.lines.flatMap { line ->
            tubeOutline(line).zipWithNext { a, b -> Capsule(a, b, TUBE_RADIUS_DP * dp) }
        } + centralCapsule,
        circles = MetroLayout.stations.map { (_, s) -> Circle(px(s.at), STATION_RADIUS_DP * dp) },
    )

    data class Solution(
        /** One per [MetroLayout.stations] entry, same order. */
        val labels: List<LabelPlacement>,
        /** One per [MetroLayout.lines] entry, same order. */
        val badges: List<LabelPlacement>,
        /** The map's height in pixels: the grid's, or more when large text pushes the bottom labels further down. */
        val heightPx: Float,
    ) {
        val allClear: Boolean get() = labels.all { it.clear } && badges.all { it.clear }
    }

    /**
     * Places every station label, then every line badge around the labels. A badge that finds no clear spot
     * gets its own spot reserved and the labels are placed again around it. [labelSizes] holds, per station
     * in [MetroLayout.stations] order, the sizes of its renderings, preferred first; [badgeSizes] one size per line.
     */
    fun solve(labelSizes: List<List<Dim>>, badgeSizes: List<Dim>): Solution {
        require(labelSizes.size == MetroLayout.stations.size) { "one size list per station" }
        require(badgeSizes.size == MetroLayout.lines.size) { "one badge size per line" }
        // Labels may run below the grid (the Explore labels do with large text); the map grows to fit them.
        val bounds = Box(0f, 0f, widthPx, heightPx + BOTTOM_ALLOWANCE_DP * dp)
        val placer = LabelPlacer(bounds, obstacles, step = STEP_DP * dp, margin = MARGIN_DP * dp, dp = dp)
        val labelSpecs = MetroLayout.stations.mapIndexed { i, (l, s) ->
            val sizes = labelSizes[i]
            LabelSpec(
                px(s.at), sidesFor(s.side), sizes, gap = LABEL_GAP_DP * dp, maxSlide = LABEL_SLIDE * unit,
                variantCosts = sizes.indices.map { v -> LabelPlacer.variantCost(v) * if (l.line in YIELDING) YIELD_FACTOR else 1f },
            )
        }
        val badgeSpecs = MetroLayout.lines.mapIndexed { i, l ->
            LabelSpec(px(l.badgeAt), sidesFor(l.badgeSide), listOf(badgeSizes[i]), gap = BADGE_GAP_DP * dp, maxSlide = BADGE_SLIDE * unit, maxLift = BADGE_LIFT * unit)
        }
        var labels = placer.place(labelSpecs)
        var badges = placer.place(badgeSpecs, fixed = labels.map { it.box })
        // A badge with no clear spot gets its own spot reserved and the labels go around it, if that is better overall.
        val reserved = LinkedHashMap<Int, Box>()
        repeat(MetroLayout.lines.size) {
            val stuck = badges.indices.filter { !badges[it].clear && it !in reserved }
            if (stuck.isEmpty()) return@repeat
            stuck.forEach { j -> placer.preferredBox(badgeSpecs[j])?.let { reserved[j] = it } }
            val nextLabels = placer.place(labelSpecs, fixed = reserved.values.toList())
            val nextBadges = placer.place(badgeSpecs, fixed = nextLabels.map { it.box })
            if (placer.total(nextLabels + nextBadges) >= placer.total(labels + badges)) return@repeat
            labels = nextLabels
            badges = nextBadges
        }
        val bottom = (labels + badges).maxOf { it.box.bottom } + BOTTOM_PADDING_DP * dp
        return Solution(labels, badges, maxOf(heightPx, bottom))
    }

    companion object {
        /** Line heights of a station label's title and status, and its vertical padding: MetroMap's text styles use these. */
        const val TITLE_LINE_SP = 16f
        const val STATUS_LINE_SP = 13f
        const val LABEL_PADDING_DP = 4f
        /** How much two labels on neighbouring rows may overlap before the map stretches; sliding absorbs this much. */
        const val ROW_TOLERANCE_DP = 6f

        /**
         * How much to stretch the grid vertically so that a one-line label ([TITLE_LINE_SP] + [STATUS_LINE_SP] at
         * [fontScale]) fits the step between rows ([MetroLayout.ROW] grid units of [unitDp] each) to within
         * [ROW_TOLERANCE_DP]. Never shrinks.
         */
        fun verticalStretch(unitDp: Float, fontScale: Float): Float {
            val label = (TITLE_LINE_SP + STATUS_LINE_SP) * fontScale + LABEL_PADDING_DP
            val step = MetroLayout.ROW * unitDp
            return maxOf(1f, (label + MARGIN_DP - ROW_TOLERANCE_DP) / step)
        }

        /** Half the glass tube's width (the draw code strokes it 20 dp wide). */
        const val TUBE_RADIUS_DP = 10f
        /** A live station's dot is 9 dp; the extra dp keeps labels off its rim. */
        const val STATION_RADIUS_DP = 10f
        const val CENTRAL_WIDTH_DP = 64f
        const val CENTRAL_HEIGHT_DP = 28f
        /** From a station's centre to the edge of its label. */
        const val LABEL_GAP_DP = 14f
        /** From a terminus to the edge of the line badge. */
        const val BADGE_GAP_DP = 16f
        const val STEP_DP = 2f
        const val MARGIN_DP = 3f
        /** How far a label may slide along its side, in grid units. */
        const val LABEL_SLIDE = 0.5f
        /** How far a badge may slide along its side and lift away from its terminus, in grid units. */
        const val BADGE_SLIDE = 1.2f
        const val BADGE_LIFT = 2.4f
        /** Room below the grid that labels may use before anything is squeezed. */
        const val BOTTOM_ALLOWANCE_DP = 160f
        const val BOTTOM_PADDING_DP = 4f

        /**
         * Lines whose labels give way first: their statuses are information (the last file unzipped, "ready"),
         * while the security lines' counts are what the map is for.
         */
        val YIELDING = setOf(MetroLine.FILES, MetroLine.EXPLORE)
        const val YIELD_FACTOR = 0.5f

        /** A station label tries its own side first, then the opposite one; beside a vertical tube, never above or below it. */
        fun sidesFor(preferred: Side): List<Side> = when (preferred) {
            Side.RIGHT -> listOf(Side.RIGHT, Side.LEFT)
            Side.LEFT -> listOf(Side.LEFT, Side.RIGHT)
            Side.ABOVE -> listOf(Side.ABOVE, Side.BELOW)
            Side.BELOW -> listOf(Side.BELOW, Side.ABOVE)
        }
    }
}
