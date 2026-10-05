package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelInfo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The home well: the strata seen from the top of a spiral stair. One ring per stratum, Surface outermost and
 * Core innermost, each ring's centre stepped toward the upper right so the rings read as floors winding down
 * to the oculus. Coordinates are fractions of a square canvas (0..1 on both axes).
 */
object Well {
    data class Ring(val stratum: Stratum?, val cx: Float, val cy: Float, val r: Float) {
        fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= r
    }

    /** A station on its stratum's ring. [x], [y] are the bead's centre. */
    data class Bead(val tunnel: TunnelInfo, val ring: Ring, val x: Float, val y: Float)

    /** Strata drawn as rings, outermost first. Explore is a side shaft and lives off the well. */
    val STRATA = listOf(Stratum.SURFACE, Stratum.TOPSOIL, Stratum.BEDROCK, Stratum.CORE)

    private const val U = 260f
    private fun ring(s: Stratum?, cx: Int, cy: Int, r: Int) = Ring(s, cx / U, cy / U, r / U)

    /** The unlit outer wall around the well. */
    val rim = ring(null, 126, 134, 124)
    val rings = listOf(
        ring(Stratum.SURFACE, 128, 134, 108),
        ring(Stratum.TOPSOIL, 130, 132, 88),
        ring(Stratum.BEDROCK, 132, 130, 68),
        ring(Stratum.CORE, 134, 128, 50),
    )
    /** The light at the bottom: holds the open-findings count on home, the subject on a finding. */
    val oculus = ring(null, 136, 126, 32)

    /** Beads sit this far inside their ring's edge, on the floor between two railings. */
    const val BEAD_INSET = 11f / U
    const val BEAD_RADIUS = 4f / U

    /**
     * Beads spread around each whole ring except a gap of [LABEL_GAP] degrees centred at the bottom, where the
     * ring's label sits. Each bead takes the middle of an equal slot, and each ring is turned by its [TURN]
     * (degrees, clockwise) so its beads stay clear of those on the neighbouring rings.
     */
    const val LABEL_GAP = 56.0
    private val TURN = mapOf(
        Stratum.SURFACE to 0.0,
        Stratum.TOPSOIL to -12.0,
        Stratum.BEDROCK to -25.0,
        Stratum.CORE to 30.0,
    )

    fun ringOf(stratum: Stratum): Ring? = rings.firstOrNull { it.stratum == stratum }

    /** Bead positions for [tunnels], in catalog order within each stratum. Explore tunnels get none. */
    fun beads(tunnels: List<TunnelInfo>): List<Bead> = rings.flatMap { ring ->
        val on = tunnels.filter { it.stratum == ring.stratum }
        val br = ring.r - BEAD_INSET
        val step = (360.0 - LABEL_GAP) / on.size
        // A lone bead sits at the top; otherwise slots start after the label gap (0 = east, 90 = bottom).
        val turn = if (on.size == 1) 0.0 else TURN.getValue(ring.stratum!!)
        on.mapIndexed { i, t ->
            val deg = if (on.size == 1) -90.0 else 90.0 + LABEL_GAP / 2 + step * (i + 0.5) + turn
            val a = Math.toRadians(deg)
            Bead(t, ring, ring.cx + br * cos(a).toFloat(), ring.cy + br * sin(a).toFloat())
        }
    }

    /** The bead nearest ([x], [y]) within [slop] of its centre, or null. */
    fun hit(beads: List<Bead>, x: Float, y: Float, slop: Float): Bead? =
        beads.map { it to hypot(it.x - x, it.y - y) }.filter { it.second <= slop }.minByOrNull { it.second }?.first

    /** The stratum whose floor contains ([x], [y]): the innermost ring holding the point, outside the oculus. */
    fun stratumAt(x: Float, y: Float): Stratum? =
        if (oculus.contains(x, y)) null else rings.lastOrNull { it.contains(x, y) }?.stratum
}
