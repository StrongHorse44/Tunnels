package io.github.stronghorse44.tunnels.decoder

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Output sizes for decoding and rendering, chosen so a frame always fits [FrameLimits]. All arithmetic in Long or Double. */
object FrameSizing {
    /** The largest sample size [sampleSize] returns; ImageDecoder takes an Int. */
    private const val MAX_SAMPLE = 1 shl 30

    /**
     * The smallest power of two `s` such that `ceil(w/s) * ceil(h/s) <= maxPixels` and both `ceil` sides are
     * `<= maxSide`. `ceil` is the larger of the sizes a codec may produce for a sample size, so the decoded bitmap fits
     * whichever way the codec rounds. A side under 1 counts as 1.
     */
    fun sampleSize(width: Int, height: Int, maxPixels: Int, maxSide: Int): Int {
        require(maxPixels >= 1 && maxSide >= 1)
        val w = width.toLong().coerceAtLeast(1)
        val h = height.toLong().coerceAtLeast(1)
        var s = 1L
        while (s < MAX_SAMPLE && !fits(ceilDiv(w, s), ceilDiv(h, s), maxPixels, maxSide)) s *= 2
        return s.toInt()
    }

    /**
     * Bitmap size for rendering a page of [pageWidthPt] x [pageHeightPt] points at [dpi], shrunk (never enlarged) so it
     * stays within [maxPixels] and [maxSide]. A side under 1 point counts as 1.
     */
    fun renderSize(pageWidthPt: Int, pageHeightPt: Int, dpi: Int, maxPixels: Int, maxSide: Int): Pair<Int, Int> {
        require(maxPixels >= 1 && maxSide >= 1)
        val d = dpi.coerceAtLeast(1)
        // In Double from the start: points times dpi overflows Int for a page claiming a huge size.
        val w = pageWidthPt.coerceAtLeast(1).toDouble() * d / 72.0
        val h = pageHeightPt.coerceAtLeast(1).toDouble() * d / 72.0
        val shrink = min(1.0, min(sqrt(maxPixels / (w * h)), maxSide / max(w, h)))
        val outW = floor(w * shrink).roundToInt().coerceIn(1, maxSide)
        val outH = floor(h * shrink).roundToInt().coerceIn(1, maxSide)
        // A side rounded up to 1 can still push a thin strip over the pixel cap when maxSide > maxPixels; trim the long side.
        return if (outW.toLong() * outH <= maxPixels) outW to outH
        else if (outW >= outH) (maxPixels / outH).coerceAtLeast(1) to outH
        else outW to (maxPixels / outW).coerceAtLeast(1)
    }

    private fun fits(w: Long, h: Long, maxPixels: Int, maxSide: Int) = w <= maxSide && h <= maxSide && w * h <= maxPixels

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}
