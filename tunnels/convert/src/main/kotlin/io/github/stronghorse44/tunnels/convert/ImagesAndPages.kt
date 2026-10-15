package io.github.stronghorse44.tunnels.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import io.github.stronghorse44.tunnels.decoder.DecoderClient
import io.github.stronghorse44.tunnels.decoder.ImageKind
import io.github.stronghorse44.tunnels.decoder.PdfJob
import io.github.stronghorse44.tunnels.decoder.TooManyPagesException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Photos and pictures: decoded in the sealed decoder (`tunnels:decoder`, an isolated process; it applies EXIF rotation
 * and samples big photos down), never in this process (audit M6). Encoding and PDF writing stay here, on a bitmap whose
 * size the host has checked, so every output file is well formed.
 */
internal object Images {
    /** The decoder's kind for an image format; the format comes from the file's first bytes. */
    fun kindOf(format: InputFormat): ImageKind = when (format) {
        InputFormat.PNG -> ImageKind.PNG
        InputFormat.JPEG -> ImageKind.JPEG
        InputFormat.WEBP -> ImageKind.WEBP
        InputFormat.HEIC -> ImageKind.HEIC
        else -> throw ConvertException(ConvertError.Unsupported("${format.label} isn't an image format."))
    }

    /** Throws DecodeFailure when the sealed decoder could not produce the picture. */
    fun decode(client: DecoderClient, file: File, format: InputFormat): Bitmap = client.decodeImage(file, kindOf(format))

    /** One A4 page with the image centred on it. */
    fun toPdf(client: DecoderClient, file: File, format: InputFormat, out: OutputStream) {
        val bitmap = decode(client, file, format)
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

    fun toImage(client: DecoderClient, file: File, format: InputFormat, target: OutputFormat, out: OutputStream) {
        val bitmap = decode(client, file, format)
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

/** PDF pages rendered to images in the sealed decoder, at 150 dpi on a white page, one sealed process per PDF. */
internal object PdfPages {
    const val MAX_PAGES = 2_000

    private fun <T> open(client: DecoderClient, file: File, block: (PdfJob) -> T): T {
        val job = try {
            client.openPdf(file, MAX_PAGES)
        } catch (e: TooManyPagesException) {
            throw ConvertException(ConvertError.LimitExceeded("The PDF has ${e.pageCount} pages; Tunnels renders at most $MAX_PAGES."))
        }
        return job.use(block)
    }

    fun pageCount(client: DecoderClient, file: File): Int = open(client, file) { it.pageCount }

    /** Renders every page, asking [openPage] for each page's output (index from 0, page count). Returns the page count. */
    fun render(
        client: DecoderClient,
        file: File,
        target: OutputFormat,
        openPage: (Int, Int) -> OutputStream,
        isCancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Int = open(client, file) { job ->
        val count = job.pageCount
        for (i in 0 until count) {
            if (isCancelled()) throw CancellationException("Cancelled")
            val bitmap = job.renderPage(i)
            try {
                openPage(i, count).use { out -> Images.write(bitmap, target, out) }
            } finally {
                bitmap.recycle()
            }
            progress(i + 1, count)
        }
        count
    }
}
