package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameSizingTest {
    private val img = FrameLimits.IMAGE

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

    private fun sampledWithin(w: Int, h: Int, limits: FrameLimits) {
        val s = FrameSizing.sampleSize(w, h, limits.maxPixels, limits.maxSide).toLong()
        assertTrue("power of two: $s", s >= 1 && s and (s - 1) == 0L)
        val sw = ceilDiv(w.toLong().coerceAtLeast(1), s)
        val sh = ceilDiv(h.toLong().coerceAtLeast(1), s)
        assertTrue("$w x $h / $s = $sw x $sh", sw <= limits.maxSide && sh <= limits.maxSide && sw * sh <= limits.maxPixels)
        if (s > 1) {
            // The smallest such power of two: half of it would not fit.
            val hw = ceilDiv(w.toLong().coerceAtLeast(1), s / 2)
            val hh = ceilDiv(h.toLong().coerceAtLeast(1), s / 2)
            assertTrue("$w x $h: ${s / 2} would have fitted", hw > limits.maxSide || hh > limits.maxSide || hw * hh > limits.maxPixels)
        }
    }

    @Test fun todaysSampleSizesHold() {
        assertEquals(1, FrameSizing.sampleSize(4000, 3000, img.maxPixels, img.maxSide))
        assertEquals(2, FrameSizing.sampleSize(8000, 6000, 24_000_000, img.maxSide))
        assertEquals(2, FrameSizing.sampleSize(8000, 6000, img.maxPixels, img.maxSide))
    }

    @Test fun aLongStripIsSampledToTheSideCap() {
        val s = FrameSizing.sampleSize(100_000, 10, img.maxPixels, img.maxSide)
        assertTrue(ceilDiv(100_000, s.toLong()) <= 16_384)
        assertEquals(8, s)
    }

    @Test fun hugeSidesDoNotOverflow() {
        sampledWithin(Int.MAX_VALUE, Int.MAX_VALUE, img)
        sampledWithin(Int.MAX_VALUE, 1, img)
        sampledWithin(0, -5, img)
        assertEquals(1, FrameSizing.sampleSize(0, 0, img.maxPixels, img.maxSide))
    }

    @Test fun theOutputNeverExceedsEitherCap() {
        val r = Random(6)
        repeat(20_000) {
            val w = if (r.nextBoolean()) r.nextInt(1, 70_000) else r.nextInt(1, Int.MAX_VALUE)
            val h = if (r.nextBoolean()) r.nextInt(1, 70_000) else r.nextInt(1, Int.MAX_VALUE)
            sampledWithin(w, h, img)
            sampledWithin(w, h, FrameLimits(1_000, 50))
        }
    }

    @Test fun renderSizeKeepsTodaysA4Size() {
        assertEquals(1239 to 1754, FrameSizing.renderSize(595, 842, 150, img.maxPixels, img.maxSide))
    }

    @Test fun renderSizeStaysWithinBothCaps() {
        val (w, h) = FrameSizing.renderSize(14_400, 14_400, 150, img.maxPixels, img.maxSide)
        assertTrue("$w x $h", w.toLong() * h <= 16_000_000L && w <= 16_384 && h <= 16_384)
        val (sw, sh) = FrameSizing.renderSize(14_400, 1, 150, img.maxPixels, img.maxSide)
        assertTrue("$sw x $sh", sw <= 16_384 && sh >= 1)
        // Shrunk as a whole to the side cap, not clipped: the strip keeps its aspect ratio (30000 x 2.08 px to 16384 x 1).
        assertEquals(16_384 to 1, sw to sh)
    }

    @Test fun renderSizeOfZeroOrNegativeIsAtLeastOne() {
        for ((pw, ph) in listOf(0 to 0, -5 to 10, 10 to -5, Int.MIN_VALUE to Int.MIN_VALUE)) {
            val (w, h) = FrameSizing.renderSize(pw, ph, 150, img.maxPixels, img.maxSide)
            assertTrue("$pw x $ph -> $w x $h", w >= 1 && h >= 1)
        }
        val (w, h) = FrameSizing.renderSize(100, 100, 0, img.maxPixels, img.maxSide)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test fun renderSizeNeverExceedsTheCapsForAnyPage() {
        val r = Random(16)
        repeat(20_000) {
            val pw = r.nextInt(-10, Int.MAX_VALUE)
            val ph = if (r.nextBoolean()) r.nextInt(-10, 20_000) else r.nextInt(-10, Int.MAX_VALUE)
            for (limits in listOf(img, FrameLimits(1_000, 50), FrameLimits(10, 100))) {
                val (w, h) = FrameSizing.renderSize(pw, ph, 150, limits.maxPixels, limits.maxSide)
                assertTrue("$pw x $ph -> $w x $h", w in 1..limits.maxSide && h in 1..limits.maxSide && w.toLong() * h <= limits.maxPixels)
            }
        }
    }

    @Test fun everyFrameWithinTheLimitsFitsTheRegion() {
        assertTrue(img.maxPixels.toLong() * FrameLimits.BYTES_PER_PIXEL <= FrameLimits.REGION_BYTES)
    }
}
