package io.github.stronghorse44.tunnels.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeicVerdictTest {
    @Test fun aRefusalIsKeptAndNeverLifted() {
        val v = HeicVerdict()
        assertNull(v.refusal)
        v.record(DecodeStatus.CRASHED)
        assertEquals(DecodeStatus.CRASHED, v.refusal)
        v.record(DecodeStatus.OK)
        v.record(DecodeStatus.UNSUPPORTED)
        assertEquals("the first refusal stays", DecodeStatus.CRASHED, v.refusal)
    }

    @Test fun everyNonOkProbeRefuses() {
        for (s in listOf(DecodeStatus.UNSUPPORTED, DecodeStatus.CRASHED, DecodeStatus.TIMEOUT, DecodeStatus.BAD_REPLY, DecodeStatus.INTERNAL)) {
            assertEquals("$s", s, HeicVerdict().apply { record(s) }.refusal)
        }
    }

    @Test fun okCancelledBusyAndUnavailableSayNothing() {
        val v = HeicVerdict()
        for (s in listOf(DecodeStatus.OK, DecodeStatus.CANCELLED, DecodeStatus.BUSY, DecodeStatus.UNAVAILABLE)) v.record(s)
        assertNull(v.refusal)
    }
}
