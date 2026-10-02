package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class WellTest {
    private val all = listOf(Well.rim) + Well.rings + Well.oculus

    @Test
    fun ringsNestStrictlyInsideEachOther() {
        all.zipWithNext().forEach { (outer, inner) ->
            val gap = outer.r - (hypot(inner.cx - outer.cx, inner.cy - outer.cy) + inner.r)
            assertTrue("${inner.stratum} sits inside ${outer.stratum} with a visible floor", gap > 8f / 260f)
        }
        all.forEach { assertTrue(it.cx - it.r >= 0f && it.cx + it.r <= 1f && it.cy - it.r >= 0f && it.cy + it.r <= 1f) }
        assertEquals(Well.STRATA, Well.rings.map { it.stratum })
    }

    @Test
    fun everyNonExploreTunnelHasOneBeadOnItsOwnFloor() {
        val beads = Well.beads(TunnelCatalog.all)
        assertEquals(TunnelCatalog.all.filter { it.stratum != Stratum.EXPLORE }.map { it.id }.toSet(), beads.map { it.tunnel.id }.toSet())
        assertEquals(beads.size, beads.map { it.tunnel.id }.toSet().size)
        beads.forEach { b ->
            assertEquals(b.tunnel.stratum, b.ring.stratum)
            assertEquals("${b.tunnel.id} lies on its stratum's floor", b.tunnel.stratum, Well.stratumAt(b.x, b.y))
        }
    }

    @Test
    fun beadsNeverTouch() {
        val beads = Well.beads(TunnelCatalog.all)
        for (i in beads.indices) for (j in i + 1 until beads.size) {
            val d = hypot(beads[i].x - beads[j].x, beads[i].y - beads[j].y)
            assertTrue("${beads[i].tunnel.id} / ${beads[j].tunnel.id}", d > 4 * Well.BEAD_RADIUS)
        }
    }

    /** A bead's angle on its ring in degrees, 0 = east, 90 = bottom, in -180..180. */
    private fun angle(b: Well.Bead) = Math.toDegrees(atan2((b.y - b.ring.cy).toDouble(), (b.x - b.ring.cx).toDouble()))

    @Test
    fun beadsKeepClearOfTheLabelAtTheBottom() {
        Well.beads(TunnelCatalog.all).forEach { b ->
            assertTrue("${b.tunnel.id} sits in its ring's label gap", abs(angle(b) - 90.0) >= Well.LABEL_GAP / 2)
        }
    }

    @Test
    fun beadsSpreadAroundTheirRingAndDoNotLineUpWithTheRingOutside() {
        val beads = Well.beads(TunnelCatalog.all)
        val byRing = Well.rings.map { r -> beads.filter { it.ring == r }.map(::angle) }
        byRing.filter { it.size > 1 }.forEach { a ->
            // Even slots: neighbours on a ring are the same angle apart, the whole ring minus the gap divided evenly.
            val gaps = a.sorted().let { s -> s.zipWithNext { x, y -> y - x } + (s.first() + 360 - s.last()) }
            val step = (360.0 - Well.LABEL_GAP) / a.size
            assertTrue("$a are crowded", gaps.all { it >= step - 0.01 })
        }
        byRing.zipWithNext().forEach { (outer, inner) ->
            for (o in outer) for (i in inner) {
                val d = abs(((o - i) % 360 + 540) % 360 - 180)
                assertTrue("beads at $o° and $i° line up across rings", d >= 6.0)
            }
        }
    }

    @Test
    fun hitFindsTheNearestBeadOnlyWithinSlop() {
        val beads = Well.beads(TunnelCatalog.all)
        val target = beads.first { it.tunnel.id == "apk_excavation" }
        assertEquals(target, Well.hit(beads, target.x + 0.004f, target.y - 0.004f, 0.03f))
        assertNull(Well.hit(beads, Well.oculus.cx, Well.oculus.cy, 0.03f))
    }

    @Test
    fun theOculusBelongsToNoStratum() {
        assertNull(Well.stratumAt(Well.oculus.cx, Well.oculus.cy))
        assertNull(Well.stratumAt(0.01f, 0.01f))
    }
}
