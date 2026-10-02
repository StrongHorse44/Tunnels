package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.Stratum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthTest {
    private fun lum(d: Float) = Depth.water(d).let { Depth.luminance(it.top) + Depth.luminance(it.mid) + Depth.luminance(it.bottom) }

    @Test
    fun everyStepDownIsDarker() {
        var last = Double.MAX_VALUE
        var d = 0f
        while (d <= Depth.MAX) {
            val l = lum(d)
            assertTrue("depth $d darker than the step above", l < last)
            last = l
            d += 0.25f
        }
    }

    @Test
    fun deeperStrataOpenDarkerTunnelsButNeverAsDarkAsAFinding() {
        val order = listOf(Stratum.SURFACE, Stratum.TOPSOIL, Stratum.BEDROCK, Stratum.CORE).map(Depth::ofTunnel)
        assertEquals(order.sorted(), order)
        assertTrue(order.all { it >= Depth.TUNNEL && it < Depth.DETAIL })
        assertEquals(Depth.ofTunnel(Stratum.SURFACE), Depth.ofTunnel(Stratum.EXPLORE))
        assertEquals(Depth.TUNNEL, Depth.ofTunnel(null))
    }

    @Test
    fun depthIsClampedAndStopsAreExact() {
        assertEquals(Depth.water(Depth.HOME), Depth.water(-2f))
        assertEquals(Depth.water(Depth.MAX), Depth.water(9f))
        assertEquals(0xFF8B74FF.toInt(), Depth.water(0f).top)
        assertEquals(0xFF020108.toInt(), Depth.water(3f).bottom)
        assertEquals(1f, Depth.surfaceLight(Depth.HOME))
        assertEquals(0f, Depth.surfaceLight(Depth.MAX))
    }

    @Test
    fun lerpHitsBothEndsAndTheMiddle() {
        assertEquals(0xFF000000.toInt(), Depth.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), Depth.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), Depth.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }
}
