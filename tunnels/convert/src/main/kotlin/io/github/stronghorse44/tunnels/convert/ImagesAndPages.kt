package io.github.stronghorse44.tunnels.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Photos and pictures: decoded with the platform decoder (which applies EXIF rotation), never enlarged. */
internal object Images {
    /** Pixel budget for a decoded image; a 48 MP photo is sampled down to 12 MP. */
    private const val MAX_PIXELS = 16_000_000L

    fun decode(file: File): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = PageGeometry.sampleSize(info.size.width, info.size.height, MAX_PIXELS)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    } catch (e: IOException) {
        throw ConvertException(ConvertError.Damaged("The image couldn't be decoded: ${e.message ?: "unreadable data"}."))
    }

    /** One A4 page with the image centred on it. */
    fun toPdf(file: File, out: OutputStream) {
        val bitmap = decode(file)
        val pdf = PdfDocument()
        try {
            val at = PageGeometry.placeImage(bitmap.width, bitmap.height)
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(at.pageWidth, at.pageHeight, 1).create())
            page.canvas.drawBitmap(bitmap, null, RectF(at.left, at.top, at.left + at.width, at.top + at.height), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            pdf.writeTo(out)
        } finally {
            pdf.close()
            bitmap.recycle()
        }
    }

    fun toImage(file: File, target: OutputFormat, out: OutputStream) {
        val bitmap = decode(file)
        try {
            write(bitmap, target, out)
        } finally {
            bitmap.recycle()
        }
    }

    /** JPEG has no transparency: transparent pixels are put on white, not left to turn black. */
    fun write(bitmap: Bitmap, target: OutputFormat, out: OutputStream) {
        val ok = when (target) {
            OutputFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            OutputFormat.JPEG -> {
                if (bitmap.hasAlpha()) {
                    val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                        flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    } finally {
                        flat.recycle()
                    }
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
            else -> throw ConvertException(ConvertError.Unsupported("${target.label} isn't an image format."))
        }
        if (!ok) throw IOException("The image couldn't be encoded.")
    }

    private const val JPEG_QUALITY = 92
}

/** PDF pages rendered to images with the platform PDF renderer, at 150 dpi on a white page. */
internal object PdfPages {
    const val MAX_PAGES = 2_000

    fun <T> open(file: File, block: (PdfRenderer) -> T): T {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close()
            throw ConvertException(ConvertError.Unsupported("This PDF is password-protected. Remove the password in a PDF app first."))
        } catch (e: IOException) {
            pfd.close()
            throw ConvertException(ConvertError.Damaged("The PDF couldn't be read: ${e.message ?: "damaged file"}."))
        }
        // Closing the renderer closes the descriptor.
        return renderer.use(block)
    }

    fun pageCount(file: File): Int = open(file) { it.pageCount }

    /** Renders every page, asking [openPage] for each page's output (index from 0, page count). Returns the page count. */
    fun render(
        file: File,
        target: OutputFormat,
        openPage: (Int, Int) -> OutputStream,
        isCancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Int = open(file) { renderer ->
        val count = renderer.pageCount
        if (count > MAX_PAGES) throw ConvertException(ConvertError.LimitExceeded("The PDF has $count pages; Tunnels renders at most $MAX_PAGES."))
        for (i in 0 until count) {
            if (isCancelled()) throw CancellationException("Cancelled")
            renderer.openPage(i).use { page ->
                val (w, h) = PageGeometry.renderSize(page.width, page.height)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.setHasAlpha(false)
                    openPage(i, count).use { out -> Images.write(bitmap, target, out) }
                } finally {
                    bitmap.recycle()
                }
            }
            progress(i + 1, count)
        }
        count
    }
}
