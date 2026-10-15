package io.github.stronghorse44.tunnels.decoder

import io.github.stronghorse44.tunnels.decoder.ReplyCheck.FrameResult
import io.github.stronghorse44.tunnels.decoder.ReplyCheck.PdfOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake service's replies: the one well-formed reply is read, every malformed one is refused before a byte is read. */
class ReplyCheckTest {
    private val limits = FrameLimits.IMAGE
    private val region = FrameLimits.REGION_BYTES
    private val good = WireReply(status = 0, width = 400, height = 300, stride = 1600, colorSpace = 0, flags = 1, pageCount = 0)

    private fun refused(reply: WireReply, regionBytes: Int = region) {
        assertEquals("$reply", FrameResult.Failed(DecodeStatus.BAD_REPLY), ReplyCheck.frame(reply, limits, regionBytes))
    }

    @Test fun theWellFormedReplyIsAccepted() {
        val frame = ReplyCheck.frame(good, limits, region) as FrameResult.Frame
        assertEquals(400, frame.width)
        assertEquals(300, frame.height)
        assertEquals(1600, frame.stride)
        assertEquals(480_000, frame.byteCount)
        assertEquals(FrameColor.SRGB, frame.color)
        assertTrue(frame.hasAlpha)
        val p3 = ReplyCheck.frame(good.copy(colorSpace = 1, flags = 0), limits, region) as FrameResult.Frame
        assertEquals(FrameColor.DISPLAY_P3, p3.color)
        assertEquals(false, p3.hasAlpha)
    }

    @Test fun theLargestFrameWithinTheCapsIsAccepted() {
        assertTrue(ReplyCheck.frame(WireReply(0, 4000, 4000, 16_000, 0, 0, 0), limits, region) is FrameResult.Frame)
        assertTrue(ReplyCheck.frame(WireReply(0, 16_384, 976, 65_536, 0, 0, 0), limits, region) is FrameResult.Frame)
    }

    @Test fun unknownAndHostOnlyStatusesAreBadReplies() {
        for (s in listOf(7, -1, 99, 108, Int.MAX_VALUE, Int.MIN_VALUE) + (100..107)) refused(good.copy(status = s))
    }

    @Test fun aNonOkWireStatusComesBackAsIs() {
        assertEquals(FrameResult.Failed(DecodeStatus.DAMAGED), ReplyCheck.frame(WireReply(2), limits, region))
        assertEquals(FrameResult.Failed(DecodeStatus.TOO_LARGE), ReplyCheck.frame(good.copy(status = 3), limits, region))
    }

    @Test fun sidesOutOfRangeAreRefused() {
        for (w in listOf(0, -1, Int.MIN_VALUE, limits.maxSide + 1)) refused(good.copy(width = w, stride = w * 4))
        for (h in listOf(0, -1, Int.MIN_VALUE, limits.maxSide + 1)) refused(good.copy(height = h))
    }

    @Test fun overTheFrameBudgetIsRefused() {
        refused(WireReply(0, 4001, 4000, 16_004, 0, 0, 0))
    }

    @Test fun aStrideThatIsNotWidthTimesFourIsRefused() {
        refused(good.copy(stride = 400 * 4 + 4))
        refused(good.copy(stride = 400 * 4 - 4))
        refused(good.copy(stride = 0))
        refused(good.copy(stride = -1600))
    }

    @Test fun aStrideWhereWidthTimesFourOverflowsIsRefused() {
        val w = 1 shl 30
        // w * 4 wraps to 0 in Int arithmetic; checked in Long it does not match.
        refused(WireReply(0, w, 1, w * 4, 0, 0, 0))
        val wide = ReplyCheck.frame(WireReply(0, w, 1, w * 4, 0, 0, 0), FrameLimits(16_000_000, 16_384), region)
        assertEquals(FrameResult.Failed(DecodeStatus.BAD_REPLY), wide)
    }

    @Test fun aFrameBiggerThanTheRegionIsRefused() {
        refused(WireReply(0, 600, 600, 2400, 0, 0, 0), regionBytes = 1024 * 1024)
        assertTrue(ReplyCheck.frame(WireReply(0, 500, 500, 2000, 0, 0, 0), limits, 1024 * 1024) is FrameResult.Frame)
    }

    @Test fun unknownColourSpacesFlagsAndPageCountsAreRefused() {
        refused(good.copy(colorSpace = 2))
        refused(good.copy(colorSpace = -1))
        refused(good.copy(flags = 2))
        refused(good.copy(flags = 1 or (1 shl 31)))
        refused(good.copy(pageCount = 1))
        refused(good.copy(pageCount = -1))
    }

    @Test fun pdfOpenAcceptsOnlyAPlausiblePageCount() {
        assertEquals(PdfOpenResult.Opened(1), ReplyCheck.pdfOpen(WireReply(0, pageCount = 1), 2000))
        assertEquals(PdfOpenResult.Opened(2000), ReplyCheck.pdfOpen(WireReply(0, pageCount = 2000), 2000))
        assertEquals(PdfOpenResult.TooManyPages(2001), ReplyCheck.pdfOpen(WireReply(0, pageCount = 2001), 2000))
        assertEquals(PdfOpenResult.Failed(DecodeStatus.DAMAGED), ReplyCheck.pdfOpen(WireReply(0, pageCount = 0), 2000))
        assertEquals(PdfOpenResult.Failed(DecodeStatus.DAMAGED), ReplyCheck.pdfOpen(WireReply(0, pageCount = -5), 2000))
        assertEquals(PdfOpenResult.Failed(DecodeStatus.PASSWORD), ReplyCheck.pdfOpen(WireReply(4), 2000))
        assertEquals(PdfOpenResult.Failed(DecodeStatus.BAD_REPLY), ReplyCheck.pdfOpen(WireReply(101, pageCount = 3), 2000))
        for (bad in listOf(WireReply(0, width = 1, pageCount = 3), WireReply(0, height = 1, pageCount = 3), WireReply(0, stride = 4, pageCount = 3),
            WireReply(0, colorSpace = 1, pageCount = 3), WireReply(0, flags = 1, pageCount = 3))) {
            assertEquals("$bad", PdfOpenResult.Failed(DecodeStatus.BAD_REPLY), ReplyCheck.pdfOpen(bad, 2000))
        }
    }

    @Test fun canDecodeAnswersOnlyOkOrUnsupported() {
        assertEquals(DecodeStatus.OK, ReplyCheck.canDecode(0))
        assertEquals(DecodeStatus.UNSUPPORTED, ReplyCheck.canDecode(1))
        for (s in listOf(2, 3, 4, 5, 6, 7, -1, 100, 106)) assertEquals("$s", DecodeStatus.BAD_REPLY, ReplyCheck.canDecode(s))
    }
}
