package io.github.stronghorse44.tunnels.decoder

/**
 * The seven ints of a DecodeReply as they came off the wire. Nothing in it is trusted until [ReplyCheck] has looked
 * at every field.
 */
data class WireReply(
    val status: Int,
    val width: Int = 0,
    val height: Int = 0,
    val stride: Int = 0,
    val colorSpace: Int = 0,
    val flags: Int = 0,
    val pageCount: Int = 0,
)

/** The colour space of a frame: the only two the host builds bitmaps in. */
enum class FrameColor(val wire: Int) { SRGB(0), DISPLAY_P3(1) }

/** Checks every value the service returns before the host reads a byte of the region or allocates anything. */
object ReplyCheck {
    /** Flag bit 1: the frame has transparent pixels. Every other bit is unknown and refused. */
    const val FLAG_HAS_ALPHA = 1

    sealed interface FrameResult {
        /** A frame the host may read: [byteCount] bytes from the start of the region, rows packed. */
        data class Frame(val width: Int, val height: Int, val color: FrameColor, val hasAlpha: Boolean) : FrameResult {
            val stride: Int get() = width * FrameLimits.BYTES_PER_PIXEL
            val byteCount: Int get() = stride * height
        }

        data class Failed(val status: DecodeStatus) : FrameResult
    }

    sealed interface PdfOpenResult {
        data class Opened(val pageCount: Int) : PdfOpenResult
        data class TooManyPages(val pageCount: Int) : PdfOpenResult
        data class Failed(val status: DecodeStatus) : PdfOpenResult
    }

    /** An image or page reply. A non-OK wire status comes back as is; anything out of shape is [DecodeStatus.BAD_REPLY]. */
    fun frame(reply: WireReply, limits: FrameLimits, regionBytes: Int): FrameResult {
        val status = DecodeStatus.fromWire(reply.status)
        if (status != DecodeStatus.OK) return FrameResult.Failed(status)
        val w = reply.width.toLong()
        val h = reply.height.toLong()
        val ok = w in 1..limits.maxSide.toLong() &&
            h in 1..limits.maxSide.toLong() &&
            w * h <= limits.maxPixels.toLong() &&
            reply.stride.toLong() == w * FrameLimits.BYTES_PER_PIXEL &&
            reply.stride.toLong() * h <= regionBytes.toLong() &&
            (reply.colorSpace == FrameColor.SRGB.wire || reply.colorSpace == FrameColor.DISPLAY_P3.wire) &&
            reply.flags and FLAG_HAS_ALPHA.inv() == 0 &&
            reply.pageCount == 0
        if (!ok) return FrameResult.Failed(DecodeStatus.BAD_REPLY)
        val color = if (reply.colorSpace == FrameColor.DISPLAY_P3.wire) FrameColor.DISPLAY_P3 else FrameColor.SRGB
        return FrameResult.Frame(reply.width, reply.height, color, reply.flags and FLAG_HAS_ALPHA != 0)
    }

    /** An openPdf reply: OK carries only the page count. */
    fun pdfOpen(reply: WireReply, maxPages: Int): PdfOpenResult {
        val status = DecodeStatus.fromWire(reply.status)
        if (status != DecodeStatus.OK) return PdfOpenResult.Failed(status)
        val othersZero = reply.width == 0 && reply.height == 0 && reply.stride == 0 && reply.colorSpace == 0 && reply.flags == 0
        return when {
            !othersZero -> PdfOpenResult.Failed(DecodeStatus.BAD_REPLY)
            reply.pageCount <= 0 -> PdfOpenResult.Failed(DecodeStatus.DAMAGED)
            reply.pageCount > maxPages -> PdfOpenResult.TooManyPages(reply.pageCount)
            else -> PdfOpenResult.Opened(reply.pageCount)
        }
    }

    /** A canDecode answer: OK or UNSUPPORTED; anything else is [DecodeStatus.BAD_REPLY]. */
    fun canDecode(wire: Int): DecodeStatus = when (val s = DecodeStatus.fromWire(wire)) {
        DecodeStatus.OK, DecodeStatus.UNSUPPORTED -> s
        else -> DecodeStatus.BAD_REPLY
    }
}
