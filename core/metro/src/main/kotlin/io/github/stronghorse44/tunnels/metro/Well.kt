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
     * Beads fan across each ring's upper arc (degrees, 0 = east, negative = up) so the lower arc stays free for
     * labels. The inner rings use narrower fans so their beads never line up with the ring outside them.
     */
    private val ARCS = mapOf(
        Stratum.SURFACE to (-160.0 to 140.0),
        Stratum.TOPSOIL to (-165.0 to 150.0),
        Stratum.BEDROCK to (-150.0 to 120.0),
        Stratum.CORE to (-125.0 to 70.0),
    )

    fun ringOf(stratum: Stratum): Ring? = rings.firstOrNull { it.stratum == stratum }

    /** Bead positions for [tunnels], in catalog order within each stratum. Explore tunnels get none. */
    fun beads(tunnels: List<TunnelInfo>): List<Bead> = rings.flatMap { ring ->
        val on = tunnels.filter { it.stratum == ring.stratum }
        val br = ring.r - BEAD_INSET
        val (start, span) = ARCS.getValue(ring.stratum!!)
        on.mapIndexed { i, t ->
            val deg = if (on.size == 1) start + span / 2 else start + i * span / (on.size - 1)
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
