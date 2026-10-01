package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DnsProbeTest {
    private fun response(query: ByteArray, rcode: Int, answers: List<ByteArray> = emptyList(), flipId: Boolean = false, qr: Boolean = true): ByteArray {
        val out = ArrayList<Byte>()
        out.add(if (flipId) (query[0].toInt() xor 0x55).toByte() else query[0])
        out.add(query[1])
        val flags = (if (qr) 0x8000 else 0) or 0x0180 or (rcode and 0xF)
        out.add((flags shr 8).toByte())
        out.add((flags and 0xFF).toByte())
        out.addAll(listOf(0, 1, (answers.size shr 8).toByte(), answers.size.toByte(), 0, 0, 0, 0))
        // Copy the question section verbatim.
        for (i in 12 until query.size) out.add(query[i])
        answers.forEach { rr -> rr.forEach { out.add(it) } }
        return out.toByteArray()
    }

    /** An RR whose name is a compression pointer to the question (0xC00C). */
    private fun rr(type: Int, rdata: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xC0.toByte()); out.add(0x0C)
        out.add((type shr 8).toByte()); out.add(type.toByte())
        out.add(0); out.add(1)
        out.addAll(listOf(0, 0, 0, 60))
        out.add((rdata.size shr 8).toByte()); out.add(rdata.size.toByte())
        rdata.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun probeDomainCannotBeRegisteredByAnyone() {
        // RFC 2606 / RFC 6761: IANA-reserved, no wildcard, resolved normally by resolvers. A registrable name would
        // let its owner answer every probe with an A record and flag every user's router as hijacking.
        assertEquals("example.com", DnsProbe.PROBE_DOMAIN)
        val q = DnsProbe.buildQuery(DnsProbe.probeName(Random(9)), 1)
        assertEquals(12 + 1 + 12 + 1 + 7 + 1 + 3 + 1 + 4, q.size)
    }

    @Test
    fun probeNamesAreRandomAndUnderTheProbeDomain() {
        val a = DnsProbe.probeName(Random(1))
        val b = DnsProbe.probeName(Random(2))
        assertNotEquals(a, b)
        assertTrue(a.endsWith("." + DnsProbe.PROBE_DOMAIN))
        assertEquals(12, a.substringBefore('.').length)
        assertTrue(a.substringBefore('.').all { it in 'a'..'z' || it in '0'..'9' })
    }

    @Test
    fun buildsAStandardQuery() {
        val q = DnsProbe.buildQuery("abc.example", 0x1234)
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(0x01, q[2].toInt()) // RD
        assertEquals(0x00, q[3].toInt())
        assertEquals(1, q[5].toInt()) // QDCOUNT
        // 12 header + (1+3) + (1+7) + 1 root + 4 (type/class)
        assertEquals(12 + 4 + 8 + 1 + 4, q.size)
        assertEquals(3, q[12].toInt())
        assertEquals('a'.code, q[13].toInt())
        assertEquals(0, q[q.size - 5].toInt())
        assertEquals(1, q[q.size - 3].toInt()) // type A
        assertEquals(1, q[q.size - 1].toInt()) // class IN
    }

    @Test
    fun classifiesReplies() {
        val id = 0xBEEF
        val q = DnsProbe.buildQuery(DnsProbe.probeName(Random(3)), id)
        assertEquals(DnsVerdict.NXDOMAIN, DnsProbe.classify(id, response(q, 3)))
        assertEquals(DnsVerdict.SERVFAIL, DnsProbe.classify(id, response(q, 2)))
        assertEquals(DnsVerdict.REFUSED, DnsProbe.classify(id, response(q, 5)))
        assertEquals(DnsVerdict.EMPTY, DnsProbe.classify(id, response(q, 0)))
        val hijacked = response(q, 0, listOf(rr(DnsProbe.TYPE_A, byteArrayOf(10, 0, 0, 1))))
        assertEquals(DnsVerdict.ANSWERED, DnsProbe.classify(id, hijacked))
        val cnameOnly = response(q, 0, listOf(rr(5, byteArrayOf(1, 'x'.code.toByte(), 0))))
        assertEquals(DnsVerdict.EMPTY, DnsProbe.classify(id, cnameOnly))
        val cnameThenA = response(q, 0, listOf(rr(5, byteArrayOf(1, 'x'.code.toByte(), 0)), rr(1, byteArrayOf(1, 2, 3, 4))))
        assertEquals(DnsVerdict.ANSWERED, DnsProbe.classify(id, cnameThenA))

        assertEquals(DnsVerdict.MISMATCH, DnsProbe.classify(id, response(q, 3, flipId = true)))
        assertEquals(DnsVerdict.MISMATCH, DnsProbe.classify(id, response(q, 3, qr = false)))
        assertEquals(DnsVerdict.MALFORMED, DnsProbe.classify(id, byteArrayOf(1, 2, 3)))
        assertEquals(DnsVerdict.MALFORMED, DnsProbe.classify(id, hijacked, length = hijacked.size - 3))
        assertEquals(DnsVerdict.MALFORMED, DnsProbe.classify(id, hijacked, length = hijacked.size + 10))

        assertEquals(LanKeys.TRUE, DnsVerdict.ANSWERED.hijackValue)
        assertEquals(LanKeys.FALSE, DnsVerdict.NXDOMAIN.hijackValue)
        assertEquals(LanKeys.FALSE, DnsVerdict.EMPTY.hijackValue)
        assertEquals(LanKeys.UNKNOWN, DnsVerdict.SERVFAIL.hijackValue)
        assertEquals(LanKeys.UNKNOWN, DnsVerdict.MALFORMED.hijackValue)
    }

    @Test
    fun truncatedAnswerIsMalformedNotACrash() {
        val id = 7
        val q = DnsProbe.buildQuery("a.b", id)
        val full = response(q, 0, listOf(rr(1, byteArrayOf(1, 2, 3, 4))))
        for (len in 12 until full.size) {
            val v = DnsProbe.classify(id, full, len)
            assertTrue("len $len gave $v", v == DnsVerdict.MALFORMED || v == DnsVerdict.EMPTY)
        }
    }
}
