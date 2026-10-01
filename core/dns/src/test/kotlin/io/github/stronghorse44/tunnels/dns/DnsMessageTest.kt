package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DnsMessageTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun expectMalformed(data: ByteArray, what: String) {
        try {
            DnsMessage.parse(data)
            fail("expected DnsFormatException for $what")
        } catch (_: DnsFormatException) {
        }
        assertNull(DnsMessage.parseOrNull(data))
    }

    @Test
    fun parsesAStandardQuery() {
        val q = DnsMessage.query(0x1234, "www.Example.COM", DnsMessage.TYPE_AAAA)
        val m = DnsMessage.parse(q)
        assertEquals(0x1234, m.id)
        assertFalse(m.isResponse)
        assertEquals(0, m.opcode)
        assertTrue(m.recursionDesired)
        assertFalse(m.isTruncated)
        assertEquals(0, m.rcode)
        assertEquals(1, m.questions.size)
        assertEquals("www.example.com", m.queryName)
        assertEquals(DnsMessage.TYPE_AAAA, m.questions[0].type)
        assertEquals("AAAA", m.questions[0].typeName)
        assertEquals(1, m.questions[0].clazz)
        assertEquals(0, m.answerCount)
    }

    @Test
    fun parsesAResponseHeader() {
        val r = DnsMessage.query(7, "example.com").also {
            it[2] = 0x81.toByte() // QR + RD
            it[3] = 0x83.toByte() // RA + NXDOMAIN
            it[7] = 0 // answer count 0
            it[9] = 1 // authority count 1 (records are not decoded, only counted)
        }
        val m = DnsMessage.parse(r)
        assertTrue(m.isResponse)
        assertEquals(3, m.rcode)
        assertEquals("NXDOMAIN", m.rcodeName)
        assertEquals(1, m.authorityCount)
        assertEquals("REFUSED", DnsMessage.rcodeName(5))
        assertEquals("RCODE9", DnsMessage.rcodeName(9))
        assertEquals("TYPE999", DnsMessage.typeName(999))
        assertEquals("HTTPS", DnsMessage.typeName(65))
    }

    @Test
    fun decompressesNamesWithPointers() {
        // Header with 2 questions; second name is "mail" + pointer to "example.com" inside the first.
        val first = DnsMessage.query(1, "example.com")
        first[5] = 2 // qdcount = 2
        val second = bytes(4, 'm'.code, 'a'.code, 'i'.code, 'l'.code, 0xC0, 12, 0, 1, 0, 1)
        val m = DnsMessage.parse(first + second)
        assertEquals(listOf("example.com", "mail.example.com"), m.questions.map { it.name })
        assertEquals(DnsMessage.TYPE_A, m.questions[1].type)
    }

    @Test
    fun parsesFromAnOffsetInsideALargerBuffer() {
        val q = DnsMessage.query(9, "a.b.c")
        val buf = ByteArray(5) + q + ByteArray(3)
        assertEquals("a.b.c", DnsMessage.parse(buf, 5, q.size).queryName)
    }

    @Test
    fun nonPrintableLabelBytesAreEscaped() {
        val q = DnsMessage.query(1, "ab")
        q[13] = 0x01
        assertEquals("?b", DnsMessage.parse(q).queryName)
    }

    @Test
    fun rejectsTruncatedAndOversizedInput() {
        expectMalformed(ByteArray(0), "empty")
        expectMalformed(ByteArray(11), "short header")
        val q = DnsMessage.query(1, "example.com")
        expectMalformed(q.copyOf(q.size - 1), "question cut")
        expectMalformed(q.copyOf(q.size - 5), "name cut before terminator")
        expectMalformed(q.copyOf(14), "label cut")
        val bad = q.copyOf().also { it[5] = 17 }
        expectMalformed(bad, "17 questions")
        val count = q.copyOf().also { it[5] = 2 }
        expectMalformed(count, "second question missing")
        try {
            DnsMessage.parse(q, 0, q.size + 1)
            fail("range")
        } catch (_: DnsFormatException) {
        }
    }

    @Test
    fun rejectsBadLabelsAndPointers() {
        val q = DnsMessage.query(1, "example.com")
        expectMalformed(q.copyOf().also { it[12] = 64 }, "label of 64")
        expectMalformed(q.copyOf().also { it[12] = 0x40 }, "label type 1")
        expectMalformed(q.copyOf().also { it[12] = 0x80.toByte() }, "label type 2")
        // Pointer to itself: a loop.
        val loop = ByteArray(12) + bytes(0xC0, 12, 0, 1, 0, 1)
        loop[5] = 1
        expectMalformed(loop, "self pointer")
        // Forward pointer.
        val forward = ByteArray(12) + bytes(0xC0, 16, 0, 1, 0, 1, 0)
        forward[5] = 1
        expectMalformed(forward, "forward pointer")
        // Pointer truncated at the end.
        val cut = ByteArray(12) + bytes(0xC0)
        cut[5] = 1
        expectMalformed(cut, "pointer cut")
    }

    @Test
    fun rejectsNamesOver255Bytes() {
        val labels = List(5) { "a".repeat(63) }
        val data = ByteArray(12).also { it[5] = 1 } + labels.flatMap { listOf(63.toByte()) + it.map { c -> c.code.toByte() } } + bytes(0, 0, 1, 0, 1)
        expectMalformed(data, "name too long")
    }

    @Test
    fun queryBuilderRejectsLongLabels() {
        try {
            DnsMessage.query(1, "a".repeat(64) + ".com")
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
