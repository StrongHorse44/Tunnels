package io.github.stronghorse44.tunnels.metro

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in pixels. */
data class Pt(val x: Float, val y: Float)

/** A width and height in pixels. */
data class Dim(val width: Float, val height: Float)

/** An axis-aligned rectangle in pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun inflate(by: Float) = Box(left - by, top - by, right + by, bottom + by)

    fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun overlapArea(o: Box): Float = max(0f, min(right, o.right) - max(left, o.left)) * max(0f, min(bottom, o.bottom) - max(top, o.top))

    fun containsBox(o: Box): Boolean = o.left >= left && o.right <= right && o.top >= top && o.bottom <= bottom

    /** Distance from [p] to the nearest point of this box; 0 inside. */
    fun distanceTo(p: Pt): Float = hypot(max(max(left - p.x, 0f), p.x - right), max(max(top - p.y, 0f), p.y - bottom))

    companion object {
        fun at(left: Float, top: Float, size: Dim) = Box(left, top, left + size.width, top + size.height)
    }
}

/** A straight piece of tube: the segment from [a] to [b] stroked [radius] wide on each side. */
data class Capsule(val a: Pt, val b: Pt, val radius: Float)

/** A round obstacle such as a station dot. */
data class Circle(val center: Pt, val radius: Float)

/** Everything a label must keep clear of besides the other labels. */
data class Obstacles(
    val capsules: List<Capsule> = emptyList(),
    val circles: List<Circle> = emptyList(),
    val boxes: List<Box> = emptyList(),
)

/**
 * One label to place next to [anchor]: [gap] pixels from it on one of [sides] (preferred first), in one of
 * its [variants] (renderings, preferred first: say one-line status, wrapped status, short status, title only),
 * slid along that side by up to [maxSlide] and pushed away from the anchor by up to [maxLift].
 */
data class LabelSpec(
    val anchor: Pt,
    val sides: List<Side>,
    val variants: List<Dim>,
    val gap: Float,
    val maxSlide: Float,
    val maxLift: Float = 0f,
    /** What each variant costs; defaults to [LabelPlacer.VARIANT_COSTS]. */
    val variantCosts: List<Float> = variants.indices.map { LabelPlacer.variantCost(it) },
)

/**
 * Where a label went. [clear] is false only when no candidate avoided every other label and obstacle;
 * the placement is then the least overlapping one.
 */
data class LabelPlacement(
    val variant: Int,
    val side: Side,
    val box: Box,
    val slide: Float,
    val lift: Float,
    val clear: Boolean,
    val cost: Float,
)

/**
 * Label placement for the metro map, in pixels. A greedy pass places labels one by one, widest first; each
 * takes the cheapest candidate (variant, side, slide, lift) that stays inside the bounds and keeps [margin]
 * pixels from every label placed before it and from every obstacle. When a label is left with its last
 * resort (its last variant, or no clear spot at all), the labels in its way are made to fall back one
 * variant (wrap their status, say) and the pass runs again, as long as that lowers the total cost.
 * Deterministic: the same input gives the same output.
 */
class LabelPlacer(
    private val bounds: Box,
    private val obstacles: Obstacles,
    /** Distance between two candidate positions along a side. */
    private val step: Float,
    /** Clearance kept around every label. */
    private val margin: Float,
    /** Pixels per dp, so costs mean the same at every density. */
    private val dp: Float,
) {
    /**
     * Places [labels] around the boxes in [fixed] (e.g. labels placed by an earlier call). Results follow input order.
     *
     * Tries each uniform floor in turn (everyone at full detail, everyone at least wrapped, everyone at least
     * short; never everyone without a status), refines each by demoting the labels that block a stuck one, and
     * keeps the cheapest. A crowded map so ends up evenly compact rather than with a few wide labels and a few
     * bare titles.
     */
    fun place(labels: List<LabelSpec>, fixed: List<Box> = emptyList()): List<LabelPlacement> {
        if (labels.isEmpty()) return emptyList()
        var best: List<LabelPlacement>? = null
        val maxFloor = labels.maxOf { it.variants.lastIndex - 1 }.coerceAtLeast(0)
        for (uniform in 0..maxFloor) {
            val floor = IntArray(labels.size) { min(uniform, max(0, labels[it].variants.lastIndex - 1)) }
            var solution = greedy(labels, fixed, floor)
            for (round in 0 until MAX_ROUNDS) {
                if (!demoteBlockers(labels, solution, floor)) break
                val next = greedy(labels, fixed, floor)
                if (total(next) >= total(solution)) break
                solution = next
            }
            if (best == null || total(solution) < total(best)) best = solution
            // Nothing given up anywhere: a higher floor cannot do better.
            if (total(best) == 0f) break
        }
        return best!!
    }

    /** Where [spec] goes when nothing is in its way: preferred side and rendering, no slide, no lift. Null if outside the bounds. */
    fun preferredBox(spec: LabelSpec): Box? = boxFor(spec, spec.sides.first(), spec.variants.first(), 0f, 0f)

    /** Total cost of a solution: what every label gave up, plus a heavy price for every overlap. */
    fun total(solution: List<LabelPlacement>): Float = solution.sumOf { (it.cost + if (it.clear) 0f else UNCLEAR_COST).toDouble() }.toFloat()

    /**
     * For every label stuck on its last resort, raises the variant floor of the labels whose boxes sit where its
     * best clear-of-obstacles rendering would go. Floors never reach a label's last variant: dropping a status
     * entirely is only ever a label's own last resort. Returns whether any floor moved.
     */
    private fun demoteBlockers(labels: List<LabelSpec>, solution: List<LabelPlacement>, floor: IntArray): Boolean {
        var moved = false
        solution.forEachIndexed { i, p ->
            val spec = labels[i]
            if (p.clear && p.variant < spec.variants.lastIndex) return@forEachIndexed
            // The cheapest rendering short of the last one that only other labels stand in the way of.
            val wanted = sortedCandidates(spec, 0).firstOrNull { c ->
                c.placement.variant < spec.variants.lastIndex && obstaclePenalty(c.placement.box) == 0f
            } ?: return@forEachIndexed
            val padded = wanted.placement.box.inflate(margin)
            solution.forEachIndexed { j, q ->
                if (j == i || !padded.intersects(q.box)) return@forEachIndexed
                val cap = labels[j].variants.lastIndex - 1
                val raised = min(q.variant + 1, cap)
                if (raised > floor[j]) {
                    floor[j] = raised
                    moved = true
                }
            }
        }
        return moved
    }

    private fun greedy(labels: List<LabelSpec>, fixed: List<Box>, floor: IntArray): List<LabelPlacement> {
        val placed = ArrayList<Box>(fixed)
        val out = arrayOfNulls<LabelPlacement>(labels.size)
        val order = labels.indices.sortedWith(compareByDescending<Int> { labels[it].variants[min(floor[it], labels[it].variants.lastIndex)].width }.thenBy { it })
        for (i in order) {
            val placement = best(labels[i], placed, floor[i])
            out[i] = placement
            placed += placement.box
        }
        return out.map { it!! }
    }

    private class Candidate(val placement: LabelPlacement)

    /** The cheapest clear candidate, or else the least overlapping one. */
    private fun best(spec: LabelSpec, placed: List<Box>, floor: Int): LabelPlacement {
        val candidates = sortedCandidates(spec, floor)
        candidates.firstOrNull { isClear(it.placement.box, placed) }?.let { return it.placement }
        val fallback = candidates.minWithOrNull(
            compareBy<Candidate> { penalty(it.placement.box, placed) }.thenBy { it.placement.cost },
        ) ?: error("label has no candidate inside the bounds")
        return fallback.placement.copy(clear = false)
    }

    /**
     * Every candidate inside the bounds from variant [floor] on, cheapest first. A variant the same size as an
     * earlier one renders the same (a "ready" status has nothing to wrap or shorten): it is tried once and
     * costs what the earliest one costs, so a floor never charges a label for detail it does not have.
     */
    private fun sortedCandidates(spec: LabelSpec, floor: Int): List<Candidate> {
        val slides = offsets(spec.maxSlide, symmetric = true)
        val lifts = offsets(spec.maxLift, symmetric = false)
        val out = ArrayList<Candidate>()
        val first = min(floor, spec.variants.lastIndex)
        for (v in first..spec.variants.lastIndex) {
            val size = spec.variants[v]
            if ((first until v).any { spec.variants[it] == size }) continue
            val canonical = (0..v).first { spec.variants[it] == size }
            spec.sides.forEachIndexed { s, side ->
                for (lift in lifts) for (slide in slides) {
                    val box = boxFor(spec, side, size, slide, lift) ?: continue
                    val cost = spec.variantCosts[canonical] + s * SIDE_COST + abs(slide) / dp * SLIDE_COST + lift / dp * LIFT_COST
                    out += Candidate(LabelPlacement(v, side, box, slide, lift, clear = true, cost = cost))
                }
            }
        }
        // Stable sort: equal costs keep the generation order, so the result is deterministic.
        out.sortBy { it.placement.cost }
        return out
    }

    /** 0, step, -step, 2 step, ... up to [max] (or only upwards when not [symmetric]). */
    private fun offsets(max: Float, symmetric: Boolean): List<Float> {
        if (max <= 0f || step <= 0f) return listOf(0f)
        val n = (max / step).toInt()
        val out = ArrayList<Float>(2 * n + 1)
        out += 0f
        for (k in 1..n) {
            out += k * step
            if (symmetric) out += -k * step
        }
        return out
    }

    /**
     * The label's box on [side]. Labels above or below their anchor are pulled back inside the bounds
     * horizontally (the map's edge stations need that), labels beside it vertically; a box that still does
     * not fit is no candidate.
     */
    private fun boxFor(spec: LabelSpec, side: Side, size: Dim, slide: Float, lift: Float): Box? {
        val a = spec.anchor
        var left: Float
        var top: Float
        when (side) {
            Side.RIGHT -> { left = a.x + spec.gap + lift; top = a.y - size.height / 2 + slide }
            Side.LEFT -> { left = a.x - spec.gap - lift - size.width; top = a.y - size.height / 2 + slide }
            Side.ABOVE -> { left = a.x - size.width / 2 + slide; top = a.y - spec.gap - lift - size.height }
            Side.BELOW -> { left = a.x - size.width / 2 + slide; top = a.y + spec.gap + lift }
        }
        when (side) {
            Side.ABOVE, Side.BELOW -> left = left.coerceIn(bounds.left, max(bounds.left, bounds.right - size.width))
            Side.LEFT, Side.RIGHT -> top = top.coerceIn(bounds.top, max(bounds.top, bounds.bottom - size.height))
        }
        val box = Box.at(left, top, size)
        return box.takeIf { bounds.containsBox(it) }
    }

    /** Whether [box] keeps [margin] from every placed label and every obstacle. Stops at the first conflict. */
    fun isClear(box: Box, placed: List<Box>): Boolean {
        val padded = box.inflate(margin)
        if (placed.any { padded.intersects(it) }) return false
        if (obstacles.boxes.any { padded.intersects(it) }) return false
        if (obstacles.circles.any { box.distanceTo(it.center) < it.radius + margin }) return false
        return obstacles.capsules.none { Geometry.segmentBoxDistance(it.a, it.b, box) < it.radius + margin }
    }

    /** 0 when [box] keeps clear of everything; otherwise how badly it overlaps (area for boxes, depth for round things). */
    fun penalty(box: Box, placed: List<Box>): Float {
        val padded = box.inflate(margin)
        var p = obstaclePenalty(box)
        for (o in placed) p += padded.overlapArea(o)
        return p
    }

    /** The part of [penalty] that comes from the map itself (tubes, dots, Central), not from other labels. */
    private fun obstaclePenalty(box: Box): Float {
        val padded = box.inflate(margin)
        var p = 0f
        for (o in obstacles.boxes) p += padded.overlapArea(o)
        for (c in obstacles.circles) {
            val d = box.distanceTo(c.center)
            if (d < c.radius + margin) p += (c.radius + margin - d) * DEPTH_WEIGHT
        }
        for (c in obstacles.capsules) {
            val d = Geometry.segmentBoxDistance(c.a, c.b, box)
            if (d < c.radius + margin) p += (c.radius + margin - d) * DEPTH_WEIGHT
        }
        return p
    }

    companion object {
        /**
         * What falling back to each variant costs: wrapping the status (1) about as much as sliding 10 dp,
         * shortening it (2) a bit more than the opposite side's worth of sliding, dropping it (3) more than
         * anything else a label can do.
         */
        val VARIANT_COSTS = floatArrayOf(0f, 6f, 14f, 60f)

        fun variantCost(index: Int): Float = VARIANT_COSTS.getOrElse(index) { VARIANT_COSTS.last() + 20f * (index - VARIANT_COSTS.lastIndex) }

        /** Using the second side costs more than wrapping or a long slide. */
        const val SIDE_COST = 25f
        const val SLIDE_COST = 0.6f
        const val LIFT_COST = 0.8f
        /** An overlap outweighs any clean fallback. */
        const val UNCLEAR_COST = 1000f
        private const val DEPTH_WEIGHT = 40f
        private const val MAX_ROUNDS = 6
    }
}

/** Small geometry helpers, public for the tests. */
object Geometry {
    fun pointSegmentDistance(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Whether the segment from [a] to [b] touches [box] (Liang-Barsky clipping). */
    fun segmentIntersectsBox(a: Pt, b: Pt, box: Box): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        var t0 = 0f
        var t1 = 1f
        val ps = floatArrayOf(-dx, dx, -dy, dy)
        val qs = floatArrayOf(a.x - box.left, box.right - a.x, a.y - box.top, box.bottom - a.y)
        for (i in 0 until 4) {
            val p = ps[i]
            val q = qs[i]
            if (p == 0f) {
                if (q < 0f) return false
            } else {
                val r = q / p
                if (p < 0f) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return true
    }

    /** Shortest distance between the segment from [a] to [b] and [box]; 0 when they touch. */
    fun segmentBoxDistance(a: Pt, b: Pt, box: Box): Float {
        if (segmentIntersectsBox(a, b, box)) return 0f
        var d = min(box.distanceTo(a), box.distanceTo(b))
        d = min(d, pointSegmentDistance(Pt(box.left, box.top), a, b))
        d = min(d, pointSegmentDistance(Pt(box.right, box.top), a, b))
        d = min(d, pointSegmentDistance(Pt(box.left, box.bottom), a, b))
        d = min(d, pointSegmentDistance(Pt(box.right, box.bottom), a, b))
        return d
    }
}
