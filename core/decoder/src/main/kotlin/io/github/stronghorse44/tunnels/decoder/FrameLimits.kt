package io.github.stronghorse44.tunnels.decoder

/** The largest frame the host accepts from the service: at most [maxPixels] pixels and [maxSide] pixels per side. */
data class FrameLimits(val maxPixels: Int, val maxSide: Int) {
    init {
        require(maxPixels >= 1 && maxSide >= 1)
        require(maxPixels.toLong() * BYTES_PER_PIXEL <= REGION_BYTES) { "a frame within these limits must fit the region" }
    }

    companion object {
        /** A decoded photo: 16 MP (a 48 MP photo is sampled down to 12 MP), and a long strip is sampled, not refused. */
        val IMAGE = FrameLimits(16_000_000, 16_384)

        /** A rendered PDF page, on the same budget. */
        val PAGE = IMAGE

        /** ARGB_8888: four bytes per pixel, rows packed. */
        const val BYTES_PER_PIXEL = 4

        /** The host-created shared memory region the service writes one frame into. Fixed size; never resized. */
        const val REGION_BYTES = 64 * 1024 * 1024

        /** The service refuses a source whose header claims more pixels than this, before allocating any. */
        const val MAX_SOURCE_PIXELS = 1L shl 28

        /** ... or a side longer than this. */
        const val MAX_SOURCE_SIDE = 65_535

        /** The host refuses to send a file larger than this (Convert's own input cap, checked a second time). */
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

        /** PDF pages are rendered at this resolution. */
        const val PDF_DPI = 150

        /** A PDF with more pages than this is refused. */
        const val MAX_PDF_PAGES = 2_000
    }
}

/** How long the host waits for each step before it ends the job (milliseconds). Tests pass shorter ones. */
data class DecoderTimeouts(
    val bindMs: Long = 10_000,
    /** protocolVersion and selfCheck. */
    val quickCallMs: Long = 5_000,
    /** canDecode: it decodes the built-in HEIC sample, which may wait on a media service the sealed process is refused. */
    val probeCallMs: Long = 30_000,
    /** decodeImage, openPdf and renderPdfPage, each. */
    val workCallMs: Long = 30_000,
    val closeMs: Long = 5_000,
    /** How long a second caller waits for the one job at a time before it gets BUSY. */
    val busyWaitMs: Long = 60_000,
    /** The whole of selfTest(), Device checks' probe. */
    val selfTestMs: Long = 15_000,
) {
    companion object {
        val DEFAULT = DecoderTimeouts()
    }
}
