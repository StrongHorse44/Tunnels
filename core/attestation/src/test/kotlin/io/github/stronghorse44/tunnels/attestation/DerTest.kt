package io.github.stronghorse44.tunnels.attestation

import io.github.stronghorse44.tunnels.attestation.DerBuilder.bool
import io.github.stronghorse44.tunnels.attestation.DerBuilder.ctx
import io.github.stronghorse44.tunnels.attestation.DerBuilder.enum
import io.github.stronghorse44.tunnels.attestation.DerBuilder.int
import io.github.stronghorse44.tunnels.attestation.DerBuilder.nul
import io.github.stronghorse44.tunnels.attestation.DerBuilder.octet
import io.github.stronghorse44.tunnels.attestation.DerBuilder.seq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class DerTest {
    @Test
    fun readsPrimitives() {
        val v = Der.readAll(DerBuilder.concat(int(0), int(127), int(128), int(-1), int(202505), enum(2), bool(true), bool(false), nul(), octet("hi")))
        assertEquals(10, v.size)
        assertEquals(0, v[0].asInt())
        assertEquals(127, v[1].asInt())
        assertEquals(128, v[2].asInt())
        assertEquals(-1, v[3].asInt())
        assertEquals(202505, v[4].asInt())
        assertTrue(v[5].isEnumerated)
        assertEquals(2, v[5].asInt())
        assertTrue(v[6].asBoolean())
        assertFalse(v[7].asBoolean())
        assertTrue(v[8].isNull)
        assertEquals(0, v[8].length)
        assertArrayEquals("hi".toByteArray(), v[9].asOctetString())
    }

    @Test
    fun readsNestedSequencesAndHighTagNumbers() {
        val bytes = seq(ctx(704, seq(octet(DerBuilder.bytes(1, 32)), bool(true), enum(1))), ctx(705, int(150000)), ctx(31, int(1)), ctx(0, nul()))
        val top = Der.read(bytes)
        assertTrue(top.isSequence)
        assertEquals(bytes.size, top.end)
        val kids = top.children()
        assertEquals(listOf(704, 705, 31, 0), kids.map { it.tagNumber })
        assertTrue(kids.all { it.isContextSpecific && it.constructed })
        val rot = kids[0].explicit()
        assertTrue(rot.isSequence)
        assertEquals(3, rot.children().size)
        assertEquals(150000, kids[1].explicit().asInt())
        assertEquals(1, kids[2].explicit().asInt())
        assertTrue(kids[3].explicit().isNull)
    }

    @Test
    fun longFormLengths() {
        val big = octet(DerBuilder.bytes(3, 300))
        assertEquals(0x82, big[1].toInt() and 0xFF)
        val v = Der.read(big)
        assertEquals(300, v.length)
        assertArrayEquals(DerBuilder.bytes(3, 300), v.asOctetString())
        val outer = seq(big, int(1))
        assertEquals(2, Der.read(outer).children().size)
    }

    @Test
    fun truncatedInputIsAClearError() {
        val whole = seq(ctx(705, int(150000)), octet(DerBuilder.bytes(2, 40)))
        for (cut in 1 until whole.size) {
            val e = assertThrows(DerException::class.java) {
                val top = Der.read(whole.copyOf(cut))
                top.children().forEach { if (it.constructed) it.children() }
            }
            assertTrue("cut at $cut: ${e.message}", e.message!!.contains("truncated") || e.message!!.contains("runs past"))
        }
    }

    @Test
    fun rejectsIndefiniteAndOversizedLengths() {
        assertThrows(DerException::class.java) { Der.read(byteArrayOf(0x30, 0x80.toByte(), 0x00, 0x00)) }
        assertThrows(DerException::class.java) { Der.read(byteArrayOf(0x04, 0x85.toByte(), 1, 1, 1, 1, 1)) }
        assertThrows(DerException::class.java) { Der.read(byteArrayOf(0x04, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) }
        assertThrows(DerException::class.java) { Der.read(ByteArray(0)) }
    }

    @Test
    fun typeMismatchesAreErrors() {
        assertThrows(DerException::class.java) { Der.read(int(1)).asBoolean() }
        assertThrows(DerException::class.java) { Der.read(bool(true)).asInt() }
        assertThrows(DerException::class.java) { Der.read(int(1)).children() }
        assertThrows(DerException::class.java) { Der.read(seq(int(1), int(2)).let { ctx(1, it) }).explicit().explicit() }
        assertThrows(DerException::class.java) { Der.read(ctx(1, DerBuilder.concat(int(1), int(2)))).explicit() }
        assertThrows(DerException::class.java) { Der.read(int(BigInteger.ONE.shiftLeft(70))).asLong() }
        assertThrows(DerException::class.java) { Der.read(int(Long.MAX_VALUE)).asInt() }
    }

    @Test
    fun trailingBytesInsideASequenceAreNotSilentlyDropped() {
        val bad = DerBuilder.tlv(Der.CLASS_UNIVERSAL, true, Der.TAG_SEQUENCE, DerBuilder.concat(int(1), byteArrayOf(0x02)))
        assertThrows(DerException::class.java) { Der.read(bad).children() }
    }
}
