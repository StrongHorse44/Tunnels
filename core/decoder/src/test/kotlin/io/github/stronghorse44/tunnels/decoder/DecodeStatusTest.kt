package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeStatusTest {
    @Test fun wireStatusesRoundTrip() {
        val wire = DecodeStatus.entries.filter { it.fromService }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), wire.map { it.wire })
        wire.forEach { assertEquals(it, DecodeStatus.fromWire(it.wire)) }
    }

    @Test fun noHostOnlyStatusIsAcceptedFromTheWire() {
        val hostOnly = DecodeStatus.entries.filterNot { it.fromService }
        assertEquals((100..107).toList(), hostOnly.map { it.wire })
        hostOnly.forEach { assertEquals(DecodeStatus.BAD_REPLY, DecodeStatus.fromWire(it.wire)) }
        listOf(-1, 7, 8, 99, 108, Int.MAX_VALUE, Int.MIN_VALUE).forEach { assertEquals(DecodeStatus.BAD_REPLY, DecodeStatus.fromWire(it)) }
    }

    @Test fun wireValuesAreUnique() {
        assertEquals(DecodeStatus.entries.size, DecodeStatus.entries.map { it.wire }.toSet().size)
        assertEquals(ImageKind.entries.size, ImageKind.entries.map { it.wire }.toSet().size)
    }

    @Test fun imageKindsAndTheirMimeTypes() {
        assertEquals(listOf(1, 2, 3, 4), ImageKind.entries.map { it.wire })
        assertEquals(ImageKind.WEBP, ImageKind.fromWire(3))
        assertNull(ImageKind.fromWire(0))
        assertNull(ImageKind.fromWire(5))
        assertTrue(ImageKind.PNG.matches("image/png"))
        assertFalse(ImageKind.JPEG.matches("image/png"))
        assertFalse(ImageKind.PNG.matches(null))
        assertTrue(ImageKind.HEIC.matches("image/heif"))
        assertTrue(ImageKind.HEIC.matches("image/heic"))
        assertFalse(ImageKind.HEIC.matches("image/avif"))
    }

    @Test fun sealedMeansExactlyBothBits() {
        assertTrue(SelfCheckBits.sealed(3))
        for (bits in listOf(0, 1, 2, 7, -1, 3 or 8)) assertFalse("$bits", SelfCheckBits.sealed(bits))
        assertTrue(SelfCheckBits.isolated(1))
        assertFalse(SelfCheckBits.isolated(2))
        assertFalse(SelfCheckBits.isolated(-1))
        assertTrue(SelfCheckBits.noAppData(2))
        assertFalse(SelfCheckBits.noAppData(1))
        assertEquals(1, PROTOCOL_VERSION)
    }
}
