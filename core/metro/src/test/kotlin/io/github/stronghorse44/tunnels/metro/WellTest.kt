package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
