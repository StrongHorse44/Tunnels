package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceCheckTest {
    @Test fun thePixelCapIsTwoToTheTwentyEighth() {
        assertEquals(1L shl 28, FrameLimits.MAX_SOURCE_PIXELS)
        assertNull(SourceCheck.header(16_384, 16_384))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(16_385, 16_384))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(16_384, 16_385))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(20_000, 20_000))
        assertNull(SourceCheck.header(65_535, 4_096))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(65_535, 4_097))
    }

    @Test fun theSideCapIs65535() {
        assertNull(SourceCheck.header(65_535, 1))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(65_536, 1))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(1, 65_536))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(70_000, 10))
        assertEquals(DecodeStatus.TOO_LARGE, SourceCheck.header(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun anEmptySizeIsDamaged() {
        for ((w, h) in listOf(0 to 10, 10 to 0, -1 to 10, 10 to -1, Int.MIN_VALUE to 5)) assertEquals("$w x $h", DecodeStatus.DAMAGED, SourceCheck.header(w, h))
        assertNull(SourceCheck.header(1, 1))
    }
}
