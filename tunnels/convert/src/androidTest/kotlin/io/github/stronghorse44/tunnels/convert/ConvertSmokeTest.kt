package io.github.stronghorse44.tunnels.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.decoder.DecodeFailure
import io.github.stronghorse44.tunnels.decoder.DecodeStatus
import io.github.stronghorse44.tunnels.decoder.DecoderClient
import io.github.stronghorse44.tunnels.decoder.FailurePolicy
import io.github.stronghorse44.tunnels.decoder.FrameLimits
import io.github.stronghorse44.tunnels.decoder.FrameSizing
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs each conversion end to end on the device: RTF to PDF, PDF to PNG, image to PDF, PNG to JPEG. Images and pages go
 * through the sealed decoder (an isolated process), as in the app.
 */
@RunWith(AndroidJUnit4::class)
class ConvertSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = context.cacheDir.resolve("convert-smoke").apply {
        deleteRecursively()
        mkdirs()
    }

    // Its own failure policy, so this class's results never depend on another test's counted failures.
    private fun client() = DecoderClient(context, policy = FailurePolicy { System.nanoTime() / 1_000_000 })

    private fun rtf(paragraphs: Int) = buildString {
        append("{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Arial;}}\\pard\\qc{\\b\\fs40 Title}\\par\\pard ")
        repeat(paragraphs) { append("Paragraph $it with {\\i italic} and caf\\'e9 text that wraps across the page width more than once.\\par ") }
        append("\\page After the break\\par}")
    }.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun rtfToPdfToPng() {
        val doc = RtfReader.read(rtf(200))
        val pdf = File(dir, "doc.pdf")
        val pages = pdf.outputStream().use { DocumentPdf.write(doc, it) }
        assertTrue("200 wrapped paragraphs plus a page break take several pages, got $pages", pages >= 4)
        assertEquals(pages, client().openPdf(pdf).use { it.pageCount })

        val images = mutableListOf<File>()
        val rendered = PdfPages.render(client(), pdf, OutputFormat.PNG, openPage = { i, n ->
            File(dir, Conversions.pageName("doc.pdf", i + 1, n, OutputFormat.PNG)).also(images::add).outputStream()
        })
        assertEquals(pages, rendered)
        val first = BitmapFactory.decodeFile(images.first().path)
        val limits = FrameLimits.PAGE
        assertEquals(
            FrameSizing.renderSize(PageGeometry.A4_WIDTH, PageGeometry.A4_HEIGHT, FrameLimits.PDF_DPI, limits.maxPixels, limits.maxSide),
            first.width to first.height,
        )
        // A white page with some dark text on it.
        val pixels = IntArray(first.width * first.height).also { first.getPixels(it, 0, first.width, 0, 0, first.width, first.height) }
        assertEquals(Color.WHITE, first.getPixel(2, 2))
        assertTrue(pixels.any { Color.red(it) < 100 })
    }

    @Test
    fun imageToPdfAndJpeg() {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT); setPixel(10, 10, Color.RED) }
        val png = File(dir, "pic.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        val pdf = File(dir, "pic.pdf")
        pdf.outputStream().use { Images.toPdf(client(), png, InputFormat.PNG, it) }
        assertEquals(1, client().openPdf(pdf).use { it.pageCount })

        val jpeg = ByteArrayOutputStream().also { Images.toImage(client(), png, InputFormat.PNG, OutputFormat.JPEG, it) }.toByteArray()
        val back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(400, back.width)
        // Transparent pixels land on white, not black.
        assertTrue(Color.red(back.getPixel(200, 150)) > 240)
    }

    @Test
    fun plainTextToPdf() {
        val doc = TextFiles.read("line one\n\tindented\n\u000Cpage two".toByteArray())
        val pdf = File(dir, "text.pdf")
        assertEquals(2, pdf.outputStream().use { DocumentPdf.write(doc, it) })
    }

    @Test
    fun damagedWebp() {
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        for (y in 0 until 192) for (x in 0 until 256) bitmap.setPixel(x, y, Color.rgb((x * 7) xor (y * 13) and 255, (x + y) and 255, (x * y) and 255))
        val whole = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }.toByteArray()
        val half = File(dir, "half.webp").apply { writeBytes(whole.copyOf(whole.size / 2)) }
        try {
            Images.decode(client(), half, InputFormat.WEBP)
            fail("a truncated WebP was decoded")
        } catch (e: DecodeFailure) {
            assertEquals(DecodeStatus.DAMAGED, e.status)
        }
    }
}
