package io.github.stronghorse44.tunnels.convert

/**
 * Page sizes and placement for the PDF writer, in PDF points (1/72 inch). Render and sample sizes for the sealed decoder
 * are `core:decoder`'s FrameSizing (audit M6).
 */
object PageGeometry {
    const val A4_WIDTH = 595
    const val A4_HEIGHT = 842
    const val TEXT_MARGIN = 56
    const val IMAGE_MARGIN = 24

    /**
     * Splits laid-out lines into pages. [lineBottoms] holds each line's bottom edge measured from the top of
     * the text, in increasing order. Each page takes as many whole lines as fit in [pageHeight]; a line taller
     * than a page gets a page to itself. Returns line index ranges, end exclusive; empty input gives one empty page.
     */
    fun paginate(lineBottoms: IntArray, pageHeight: Int): List<IntRange> {
        require(pageHeight > 0)
        if (lineBottoms.isEmpty()) return listOf(IntRange.EMPTY)
        val pages = mutableListOf<IntRange>()
        var start = 0
        var top = 0
        for (i in lineBottoms.indices) {
            if (lineBottoms[i] - top > pageHeight && i > start) {
                pages.add(start until i)
                start = i
                top = lineBottoms[i - 1]
            }
        }
        pages.add(start until lineBottoms.size)
        return pages
    }

    /** Where an image goes on a page. */
    data class Placement(val pageWidth: Int, val pageHeight: Int, val left: Float, val top: Float, val width: Float, val height: Float)

    /** An A4 page, turned to the image's orientation, with the image scaled to fit inside the margins and centred. Small images are not enlarged past 2x. */
    fun placeImage(imageWidth: Int, imageHeight: Int): Placement {
        require(imageWidth > 0 && imageHeight > 0)
        val landscape = imageWidth > imageHeight
        val pw = if (landscape) A4_HEIGHT else A4_WIDTH
        val ph = if (landscape) A4_WIDTH else A4_HEIGHT
        val boxW = (pw - 2 * IMAGE_MARGIN).toFloat()
        val boxH = (ph - 2 * IMAGE_MARGIN).toFloat()
        // Images are taken at 144 dpi: 2 pixels per point, so a phone photo fills the box and an icon stays small.
        val scale = minOf(boxW / imageWidth, boxH / imageHeight, 0.5f)
        val w = imageWidth * scale
        val h = imageHeight * scale
        return Placement(pw, ph, (pw - w) / 2f, (ph - h) / 2f, w, h)
    }

    /** Body text size and the size for each heading level. */
    fun fontSize(heading: Int, sizePt: Float?): Float = when (heading) {
        1 -> 22f
        2 -> 18f
        3 -> 15f
        4, 5, 6 -> 13f
        else -> sizePt ?: 12f
    }.let { if (heading > 0 && sizePt != null) maxOf(it, sizePt) else it }
}
