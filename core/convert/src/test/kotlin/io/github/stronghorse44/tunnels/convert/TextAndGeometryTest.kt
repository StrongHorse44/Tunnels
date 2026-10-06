package io.github.stronghorse44.tunnels.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndGeometryTest {
    @Test fun decodesUtf8Utf16AndFallsBackTo1252() {
        assertEquals("café", TextFiles.decode("café".toByteArray(Charsets.UTF_8)))
        assertEquals("hi", TextFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'i'.code.toByte())))
        assertEquals("hi", TextFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 'i'.code.toByte(), 0)))
        assertEquals("café", TextFiles.decode(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())))
    }

    @Test fun textLinesAndFormFeeds() {
        val doc = TextFiles.read("one\r\ntwo\n\nthree\u000Cfour\n\n".toByteArray())
        assertTrue(doc.monospace)
        assertEquals(listOf("one", "two", "", "three", "four"), doc.paragraphs.map { it.text })
        assertEquals(2, doc.sections().size)
    }

    @Test fun writesText() {
        val doc = Document(
            listOf(
                Paragraph(listOf(Run("Title")), heading = 1),
                Paragraph(listOf(Run("item")), bullet = "•", level = 1),
                PageBreak,
                Paragraph(listOf(Run("a\tb"))),
            ),
        )
        assertEquals("Title\n\n  • item\n\u000Ca\tb\n", TextFiles.write(doc))
    }

    @Test fun rtfToTextRoundTrip() {
        val doc = RtfReader.read("{\\rtf1 {\\b Hello} world\\par Bye\\par}".toByteArray())
        assertEquals("Hello world\nBye\n", TextFiles.write(doc))
    }

    @Test fun paginateFillsPagesWithWholeLines() {
        // 10 lines of 100 each, page 350 tall: 3 + 3 + 3 + 1.
        val bottoms = IntArray(10) { (it + 1) * 100 }
        assertEquals(listOf(0 until 3, 3 until 6, 6 until 9, 9 until 10), PageGeometry.paginate(bottoms, 350))
    }

    @Test fun paginateGivesTallLinesTheirOwnPage() {
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), PageGeometry.paginate(intArrayOf(50, 1050, 1100), 300))
        assertEquals(listOf(IntRange.EMPTY), PageGeometry.paginate(IntArray(0), 300))
    }

    @Test fun placeImageKeepsAspectAndOrientation() {
        val portrait = PageGeometry.placeImage(3000, 4000)
        assertEquals(PageGeometry.A4_WIDTH, portrait.pageWidth)
        assertEquals(3000f / 4000f, portrait.width / portrait.height, 0.001f)
        assertTrue(portrait.left >= PageGeometry.IMAGE_MARGIN - 0.01f && portrait.top >= PageGeometry.IMAGE_MARGIN - 0.01f)
        val landscape = PageGeometry.placeImage(4000, 1000)
        assertEquals(PageGeometry.A4_HEIGHT, landscape.pageWidth)
        assertEquals(PageGeometry.A4_HEIGHT - 2f * PageGeometry.IMAGE_MARGIN, landscape.width, 0.01f)
        val icon = PageGeometry.placeImage(64, 64)
        assertEquals(32f, icon.width, 0.01f)
    }

    @Test fun renderSizeCapsPixels() {
        assertEquals(1239 to 1754, PageGeometry.renderSize(595, 842))
        val (w, h) = PageGeometry.renderSize(14400, 14400, 150, 16_000_000L)
        assertTrue(w.toLong() * h <= 16_000_000L)
        assertEquals(1, PageGeometry.sampleSize(4000, 3000))
        assertEquals(2, PageGeometry.sampleSize(8000, 6000, 24_000_000L))
    }

    @Test fun headingSizes() {
        assertEquals(12f, PageGeometry.fontSize(0, null))
        assertEquals(9f, PageGeometry.fontSize(0, 9f))
        assertEquals(22f, PageGeometry.fontSize(1, 11f))
        assertEquals(30f, PageGeometry.fontSize(1, 30f))
    }
}
