package io.github.stronghorse44.tunnels.decoder

import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable

/**
 * The sealed decoder (audit M6). Runs only in an isolated process (`android:isolatedProcess="true"`): its own throwaway
 * uid with none of Tunnels' permissions, files, Keystore keys or network. It decodes images and renders PDF pages from
 * a read-only descriptor the host passes, and writes raw pixels into the host's shared memory region. It answers with
 * ints only, never text, never logs, and never calls the host: the host hands it no binder.
 *
 * The host starts a fresh process for every job and kills it by unbinding when the job ends.
 */
class IsolatedDecoderService : Service() {
    private val binder by lazy { Decoder(applicationInfo?.dataDir) }

    /** Outside an isolated process this service refuses to work: the host then sees a null binding (UNAVAILABLE). */
    override fun onBind(intent: Intent?): IBinder? = if (Process.isIsolated()) binder else null

    override fun onDestroy() {
        if (Process.isIsolated()) binder.closePdf()
        super.onDestroy()
    }

    private class Decoder(private val dataDir: String?) : IIsolatedDecoder.Stub() {
        private val lock = Any()
        private var renderer: PdfRenderer? = null

        override fun protocolVersion(): Int = PROTOCOL_VERSION

        override fun selfCheck(): Int {
            var bits = 0
            if (Process.isIsolated()) bits = bits or SelfCheckBits.ISOLATED
            // Both mean "cannot see": the data directory cannot be listed and its databases folder cannot be found.
            val dir = dataDir
            val hidden = dir != null && runCatching { File(dir).list() == null && !File(dir, "databases").exists() }.getOrDefault(false)
            if (hidden) bits = bits or SelfCheckBits.NO_APP_DATA
            return bits
        }

        override fun canDecode(kind: Int): Int {
            val k = ImageKind.fromWire(kind) ?: return DecodeStatus.BAD_REQUEST.wire
            if (k != ImageKind.HEIC) return DecodeStatus.OK.wire
            return try {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(HeicSample.bytes()))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val ok = bitmap.width == HeicSample.WIDTH && bitmap.height == HeicSample.HEIGHT
                bitmap.recycle()
                if (ok) DecodeStatus.OK.wire else DecodeStatus.UNSUPPORTED.wire
            } catch (_: Throwable) {
                DecodeStatus.UNSUPPORTED.wire
            }
        }

        override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply {
            try {
                val k = ImageKind.fromWire(kind)
                if (source == null || frame == null || k == null || maxPixels < 1 || maxSide < 1) return reply(DecodeStatus.BAD_REQUEST)
                synchronized(lock) {
                    val decoded = try {
                        decode(source, k, maxPixels, maxSide)
                    } catch (e: Refused) {
                        return reply(e.status)
                    } catch (_: ImageDecoder.DecodeException) {
                        return reply(DecodeStatus.DAMAGED)
                    } catch (_: IOException) {
                        return reply(DecodeStatus.DAMAGED)
                    }
                    return writeFrame(decoded, frame, maxPixels, maxSide, keepAlpha = true)
                }
            } catch (e: Refused) {
                return reply(e.status)
            } catch (_: OutOfMemoryError) {
                return reply(DecodeStatus.TOO_LARGE)
            } catch (_: Throwable) {
                return reply(DecodeStatus.INTERNAL)
            } finally {
                runCatching { source?.close() }
                runCatching { frame?.memory?.close() }
            }
        }

        /** The header is checked before any pixel is allocated: the kind, the size and the source cap. */
        private fun decode(source: ParcelFileDescriptor, kind: ImageKind, maxPixels: Int, maxSide: Int): Bitmap {
            val src = ImageDecoder.createSource(Callable { AssetFileDescriptor(source, 0, AssetFileDescriptor.UNKNOWN_LENGTH) })
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                if (!kind.matches(info.mimeType)) throw Refused(DecodeStatus.DAMAGED)
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) throw Refused(DecodeStatus.DAMAGED)
                if (w.toLong() * h > FrameLimits.MAX_SOURCE_PIXELS || w > FrameLimits.MAX_SOURCE_SIDE || h > FrameLimits.MAX_SOURCE_SIDE) {
                    throw Refused(DecodeStatus.TOO_LARGE)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = FrameSizing.sampleSize(w, h, maxPixels, maxSide)
                if (sample > 1) decoder.setTargetSampleSize(sample)
                val space = info.colorSpace
                if (space != null && space != SRGB && space != DISPLAY_P3) decoder.setTargetColorSpace(SRGB)
            }
        }

        override fun openPdf(source: ParcelFileDescriptor?): DecodeReply = synchronized(lock) {
            closeRenderer()
            if (source == null) return reply(DecodeStatus.BAD_REQUEST)
            try {
                // The renderer owns the descriptor from here and closes it with itself.
                val opened = PdfRenderer(source)
                renderer = opened
                DecodeReply().apply {
                    status = DecodeStatus.OK.wire
                    pageCount = opened.pageCount
                }
            } catch (_: SecurityException) {
                runCatching { source.close() }
                reply(DecodeStatus.PASSWORD)
            } catch (_: IOException) {
                runCatching { source.close() }
                reply(DecodeStatus.DAMAGED)
            } catch (_: OutOfMemoryError) {
                runCatching { source.close() }
                reply(DecodeStatus.TOO_LARGE)
            } catch (_: Throwable) {
                runCatching { source.close() }
                reply(DecodeStatus.INTERNAL)
            }
        }

        override fun renderPdfPage(pageIndex: Int, dpi: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply {
            try {
                synchronized(lock) {
                    val r = renderer
                    if (r == null || frame == null || maxPixels < 1 || maxSide < 1 || pageIndex !in 0 until r.pageCount) {
                        return reply(DecodeStatus.BAD_REQUEST)
                    }
                    r.openPage(pageIndex).use { page ->
                        if (page.width <= 0 || page.height <= 0) return reply(DecodeStatus.DAMAGED)
                        val (w, h) = FrameSizing.renderSize(page.width, page.height, dpi, maxPixels, maxSide)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return writeFrame(bitmap, frame, maxPixels, maxSide, keepAlpha = false)
                    }
                }
            } catch (e: Refused) {
                return reply(e.status)
            } catch (_: IOException) {
                return reply(DecodeStatus.DAMAGED)
            } catch (_: OutOfMemoryError) {
                return reply(DecodeStatus.TOO_LARGE)
            } catch (_: Throwable) {
                return reply(DecodeStatus.INTERNAL)
            } finally {
                runCatching { frame?.memory?.close() }
            }
        }

        override fun closePdf() {
            synchronized(lock) { closeRenderer() }
        }

        private fun closeRenderer() {
            runCatching { renderer?.close() }
            renderer = null
        }

        /**
         * Copies [decoded] (recycled here) into the region as ARGB_8888, rows packed, in sRGB or Display P3, and replies
         * with its size. A page reply carries no page count and no flags: the host knows both already.
         */
        private fun writeFrame(decoded: Bitmap, frame: FrameRegion, maxPixels: Int, maxSide: Int, keepAlpha: Boolean): DecodeReply {
            var bitmap = decoded
            try {
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw Refused(DecodeStatus.INTERNAL)
                    bitmap.recycle()
                    bitmap = copy
                }
                val space = bitmap.colorSpace
                if (space != SRGB && space != DISPLAY_P3) {
                    // A colour space the host does not build bitmaps in: drawn into sRGB, which converts the colours.
                    val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), SRGB)
                    Canvas(flat).drawBitmap(bitmap, 0f, 0f, null)
                    bitmap.recycle()
                    bitmap = flat
                }
                val w = bitmap.width
                val h = bitmap.height
                if (w > maxSide || h > maxSide || w.toLong() * h > maxPixels) throw Refused(DecodeStatus.TOO_LARGE)
                if (bitmap.rowBytes != w * FrameLimits.BYTES_PER_PIXEL) throw Refused(DecodeStatus.INTERNAL)
                val buf = frame.memory.mapReadWrite()
                try {
                    if (bitmap.byteCount > buf.capacity()) throw Refused(DecodeStatus.TOO_LARGE)
                    buf.position(0)
                    bitmap.copyPixelsToBuffer(buf)
                } finally {
                    SharedMemory.unmap(buf)
                }
                return DecodeReply().apply {
                    status = DecodeStatus.OK.wire
                    width = w
                    height = h
                    stride = w * FrameLimits.BYTES_PER_PIXEL
                    colorSpace = if (bitmap.colorSpace == DISPLAY_P3) FrameColor.DISPLAY_P3.wire else FrameColor.SRGB.wire
                    flags = if (keepAlpha && bitmap.hasAlpha()) ReplyCheck.FLAG_HAS_ALPHA else 0
                }
            } finally {
                bitmap.recycle()
            }
        }

        private fun reply(status: DecodeStatus) = DecodeReply().apply { this.status = status.wire }
    }

    /** A refusal decided by this service's own checks, carried out of the decoder's header listener. */
    private class Refused(val status: DecodeStatus) : RuntimeException(null, null, false, false)

    private companion object {
        val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        val DISPLAY_P3: ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    }
}
