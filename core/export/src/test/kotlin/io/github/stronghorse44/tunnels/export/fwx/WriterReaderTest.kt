// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/WriterReaderTest.kt at f78f08e (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/** Writer-side rules (section 9.4 end), streaming, a random round trip and legacy detection (section 11). */
class WriterReaderTest {
    private val pass = T.PASSPHRASE.toCharArray()

    private fun writer(out: ByteArrayOutputStream = ByteArrayOutputStream(), chunkSize: Int = 4096) =
        T.fixedWriter(out, "tunnels", 1, T.CREATED_MS, pass, 600_000, chunkSize, T.SALT16, T.NONCE)

    private fun expectCode(code: FwxError, block: () -> Unit) {
        try {
            block()
            fail("no failure, expected $code")
        } catch (e: FwxException) {
            assertEquals(code, e.code)
        }
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("no IllegalStateException")
        } catch (e: IllegalStateException) {
            // spec section 8: any call on a poisoned writer
        }
    }

    @Test
    fun shortOrLongSourcePoisonsTheWriter() {
        val w = writer()
        expectCode(FwxError.MALFORMED_PAYLOAD) { w.entry("a", 10, ByteArrayInputStream(ByteArray(9))) }
        expectIllegalState { w.entry("b", 1, ByteArrayInputStream(ByteArray(1))) }
        expectIllegalState { w.finish() }

        val w2 = writer()
        expectCode(FwxError.MALFORMED_PAYLOAD) { w2.entry("a", 10, ByteArrayInputStream(ByteArray(11))) }
        expectIllegalState { w2.finish() }
    }

    @Test
    fun failingSourcePoisonsTheWriter() {
        val w = writer()
        val failure = java.io.IOException("source failed")
        val broken = object : InputStream() {
            override fun read(): Int = throw failure
        }
        try {
            w.entry("a", 1, broken)
            fail("accepted")
        } catch (e: java.io.IOException) {
            assertTrue(e === failure)
        }
        expectIllegalState { w.finish() }
    }

    /** Spec section 8: the destination's IOException comes out as it is, unwrapped, and poisons the writer. */
    @Test
    fun destinationFailureIsThePlainIOException() {
        val failure = java.io.IOException("disk full")
        val out = object : java.io.OutputStream() {
            var written = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > 115 + 4112) throw failure
                written += len
            }
        }
        val w = T.fixedWriter(out, "tunnels", 1, T.CREATED_MS, pass, 600_000, 4096, T.SALT16, T.NONCE)
        try {
            w.entry("big", 20_000L, T.PatternStream(20_000L))
            fail("accepted")
        } catch (e: java.io.IOException) {
            assertTrue(e === failure)
        }
        expectIllegalState { w.entry("next", ByteArray(1)) }
        expectIllegalState { w.finish() }
    }

    @Test
    fun finishedWriterRefusesMoreCalls() {
        val w = writer()
        w.finish()
        expectIllegalState { w.entry("a", ByteArray(1)) }
        expectIllegalState { w.finish() }
    }

    @Test
    fun writerRefusesShortPassphrase() {
        expectCode(FwxError.BAD_PASSPHRASE) {
            FwxWriter(ByteArrayOutputStream(), "tunnels", 1, T.CREATED_MS, "elevenchars".toCharArray())
        }
        val out = ByteArrayOutputStream()
        expectCode(FwxError.BAD_PASSPHRASE) {
            FwxWriter(out, "tunnels", 1, T.CREATED_MS, "abc\uD800defghijklmn".toCharArray())
        }
        assertEquals("nothing written before the passphrase is accepted", 0, out.size())
    }

    @Test
    fun writerRefusesBadNames() {
        for (name in listOf("../x", "/x", "x/", "a//b", "a\\b", ".", "..", "a/./b", "", "a".repeat(256), "café", "a b", "a\u0000")) {
            expectCode(FwxError.MALFORMED_PAYLOAD) { writer().entry(name, ByteArray(0)) }
        }
        val w = writer()
        w.entry("Photo.jpg", ByteArray(1))
        expectCode(FwxError.MALFORMED_PAYLOAD) { w.entry("photo.JPG", ByteArray(1)) }
        val w2 = writer()
        w2.entry("a", ByteArray(1))
        expectCode(FwxError.MALFORMED_PAYLOAD) { w2.entry("a", ByteArray(1)) }
        // Folder clashes (spec section 4.2), in either order and ignoring case.
        for ((first, second) in listOf("a" to "a/b", "a/b" to "a", "A" to "a/b", "a/b" to "A", "x/y/z" to "X/Y", "x/y" to "x/y/z/w")) {
            val wc = writer()
            wc.entry(first, ByteArray(1))
            expectCode(FwxError.MALFORMED_PAYLOAD) { wc.entry(second, ByteArray(1)) }
        }
        val wok = writer()
        for (name in listOf("ab", "a/b", "a/bc", "a/b.d", "abc/b", "a.b")) wok.entry(name, ByteArray(1))
        wok.finish()
        // Valid edge cases.
        val w3 = writer()
        w3.entry("a".repeat(255), ByteArray(0))
        w3.entry("A-b_c.d/e.f/..g", ByteArray(0))
        w3.finish()
    }

    @Test
    fun writerRefusesBadHeaderValues() {
        val out = ByteArrayOutputStream()
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "Tunnels", 1, 0, pass) }
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "tunnels", 0, 0, pass) }
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "tunnels", 1L shl 32, 0, pass) }
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "tunnels", 1, -1, pass) }
        expectCode(FwxError.KDF_PARAMS) { FwxWriter(out, "tunnels", 1, 0, pass, iterations = 599_999) }
        expectCode(FwxError.KDF_PARAMS) { FwxWriter(out, "tunnels", 1, 0, pass, iterations = 10_000_001) }
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "tunnels", 1, 0, pass, chunkSize = 3000) }
        expectCode(FwxError.MALFORMED_HEADER) { FwxWriter(out, "tunnels", 1, 0, pass, chunkSize = 1 shl 23) }
        assertEquals(0, out.size())
    }

    @Test
    fun neverAnEmptyFinalChunk() {
        // L = 8192 and L = 4096 + 1: the held full chunk is sealed final, or non-final only when a byte follows.
        for (dataLength in listOf(8175, 4096 - 11 - 1 - 5, 4096 - 11 - 1 - 5 + 1)) {
            val out = ByteArrayOutputStream()
            val w = writer(out)
            w.entry("x", ByteArray(dataLength))
            w.finish()
            val l = 11 + 1 + dataLength + 5
            val n = (l + 4095) / 4096
            assertEquals(115 + l + 16 * n, out.size())
            assertNull(T.openAll(out.toByteArray()))
        }
    }

    @Test
    fun randomSaltAndNonceDifferEachTime() {
        val a = ByteArrayOutputStream().also { FwxWriter(it, "tunnels", 1, 0, pass).finish() }.toByteArray()
        val b = ByteArrayOutputStream().also { FwxWriter(it, "tunnels", 1, 0, pass).finish() }.toByteArray()
        assertEquals(1L shl 20, Fwx.u32(a, 79))
        assertTrue(!a.copyOfRange(56, 79).contentEquals(b.copyOfRange(56, 79)))
        assertNull(T.openAll(a))
    }

    /** One entry of 1 MiB + 3 bytes through 4 KiB chunks (257 chunks), streamed on both sides. */
    @Test
    fun streamsAnEntryLargerThanManyChunks() {
        val size = (1L shl 20) + 3
        val out = ByteArrayOutputStream()
        val w = writer(out)
        w.entry("small", ByteArray(5) { 7 })
        w.entry("big", size, T.PatternStream(size))
        w.entry("after", ByteArray(3) { 9 })
        w.finish()
        val file = out.toByteArray()

        val counter = T.CountingStream(ByteArrayInputStream(file))
        val r = FwxReader(counter, pass, "tunnels", 1L..1L)
        assertArrayEquals(ByteArray(5) { 7 }, r.next()!!.stream.readBytes())
        val big = r.next()!!
        assertEquals(size, big.length)
        val expected = MessageDigest.getInstance("SHA-256")
        T.PatternStream(size).use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; expected.update(b, 0, n) } }
        val actual = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1000)
        var total = 0L
        while (true) {
            // The reader holds at most one chunk ahead of what the caller has read, plus one lookahead block.
            assertTrue(counter.count <= 115 + total + 11 + 5 + 16 + 3 * (4096 + 16))
            assertTrue(big.stream.available() <= 4096)
            val n = big.stream.read(buf)
            if (n < 0) break
            actual.update(buf, 0, n)
            total += n
        }
        assertEquals(size, total)
        assertArrayEquals(expected.digest(), actual.digest())
        assertArrayEquals(ByteArray(3) { 9 }, r.next()!!.stream.readBytes())
        assertNull(r.next())
        assertEquals(file.size.toLong(), counter.count)
    }

    @Test
    fun skipStillVerifies() {
        val file = CasesTest.apply(T.resource("V1.fwx"), "flip:9000:0")
        val r = FwxReader(ByteArrayInputStream(file), pass, "tunnels", null)
        r.next()!!.stream.skip(100)
        r.next()!!.stream.skip(100)
        try {
            r.next()!!.stream.skip(20000)
            fail("skipped over a damaged chunk")
        } catch (e: FwxException) {
            assertEquals(FwxError.DAMAGED, e.code)
        }
    }

    @Test
    fun nextBeforeDrainingIsAnError() {
        val r = FwxReader(ByteArrayInputStream(T.resource("V1.fwx")), pass, "tunnels", null)
        r.next()
        try {
            r.next()
            fail("allowed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    /** Random entries, sizes around chunk boundaries and three chunk sizes; every byte must come back. */
    @Test
    fun randomRoundTrip() {
        val random = Random(20261003)
        repeat(10) { round ->
            val chunkSize = listOf(4096, 8192, 65536)[round % 3]
            val entries = (0 until random.nextInt(0, 8)).map { i ->
                val size = when (random.nextInt(4)) {
                    0 -> 0
                    1 -> random.nextInt(1, 64)
                    2 -> chunkSize * random.nextInt(1, 4) + random.nextInt(-20, 20)
                    else -> random.nextInt(0, 3 * chunkSize)
                }.coerceAtLeast(0)
                "dir$i/entry-$round.$i.bin" to random.nextBytes(size)
            }
            val out = ByteArrayOutputStream()
            val w = FwxWriter(out, "southbound", 3, random.nextLong(0, Long.MAX_VALUE), pass, chunkSize = chunkSize)
            for ((name, data) in entries) {
                if (random.nextBoolean()) w.entry(name, data) else w.entry(name, data.size.toLong(), ByteArrayInputStream(data))
            }
            w.finish()
            val l = entries.sumOf { 11 + it.first.length + it.second.size } + 5
            val n = (l + chunkSize - 1) / chunkSize
            assertEquals(76 + "southbound".length + 32 + l + 16 * n, out.size())

            val r = FwxReader(ByteArrayInputStream(out.toByteArray()), pass, "southbound", 1L..3L)
            for ((name, data) in entries) {
                val e = r.next()!!
                assertEquals(name, e.name)
                assertArrayEquals(data, e.stream.readBytes())
            }
            assertNull(r.next())
        }
    }

    @Test
    fun writerRefusesNamesOverOneMiBInTotal() {
        val w = writer()
        for (i in 0 until 4112) w.entry(String.format("%05d", i) + "n".repeat(250), ByteArray(0))
        expectCode(FwxError.MALFORMED_PAYLOAD) { w.entry("99999" + "n".repeat(250), ByteArray(0)) }
        // Exactly 1 MiB is allowed: 4112 * 255 + 16 = 1048576.
        val w2 = writer()
        for (i in 0 until 4112) w2.entry(String.format("%05d", i) + "n".repeat(250), ByteArray(0))
        w2.entry("x".repeat(16), ByteArray(0))
        expectCode(FwxError.MALFORMED_PAYLOAD) { w2.entry("y", ByteArray(0)) }
    }

    /** The digest-based set keeps the folder rule: case-insensitive, either order, whole segments. */
    @Test
    fun nameSetRules() {
        fun refused(vararg names: String): Boolean {
            val set = FwxNameSet()
            return names.any { set.add(it) != null }
        }
        assertTrue(refused("a", "a/b"))
        assertTrue(refused("a/b", "a"))
        assertTrue(refused("A", "a/b"))
        assertTrue(refused("a/b", "A"))
        assertTrue(refused("a/b/c", "A/B"))
        assertTrue(refused("a", "A"))
        assertTrue(!refused("ab", "a/b"))
        assertTrue(!refused("a/b", "a/c", "a/bc", "b"))
        val set = FwxNameSet()
        assertNull(set.add("a/b"))
        assertTrue(set.add("A") != null)
        assertNull(set.add("c"))
    }

    /** An input IOException fails the reader for good: later calls are IllegalStateException. */
    @Test
    fun readerIOExceptionIsSticky() {
        val file = T.resource("V1.fwx")
        val failure = java.io.IOException("storage went away")
        val input = object : InputStream() {
            var at = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= 9000) throw failure
                val n = minOf(len, 9000 - at, file.size - at)
                System.arraycopy(file, at, b, off, n)
                at += n
                return n
            }
        }
        val r = FwxReader(input, pass, "tunnels", null)
        r.next()!!.stream.readBytes()
        r.next()!!.stream.readBytes()
        val big = r.next()!!.stream
        try {
            big.readBytes()
            fail("read past a failing input")
        } catch (e: java.io.IOException) {
            assertTrue(e === failure)
        }
        // The same entry stream must not retry the input; the reader stays failed.
        expectIllegalState { big.read(ByteArray(10)) }
        expectIllegalState { r.next() }
    }

    @Test
    fun legacyMagicIsDetected() {
        val legacy = T.resource("legacy-TSNAPE1.bin")
        assertEquals(Fwx.Format.LEGACY, Fwx.detect(legacy.copyOf(8)))
        assertEquals(Fwx.Format.LEGACY, Fwx.detect("TSNAPE1".toByteArray()))
        assertEquals(Fwx.Format.FWX, Fwx.detect(T.resource("V1.fwx").copyOf(8)))
        assertEquals(Fwx.Format.UNKNOWN, Fwx.detect("TSNAPE".toByteArray()))
        assertEquals(Fwx.Format.UNKNOWN, Fwx.detect(T.resource("V1.fwx").copyOf(7)))
        assertEquals(Fwx.Format.UNKNOWN, Fwx.detect("PK\u0003\u0004zip!".toByteArray()))
        expectCode(FwxError.LEGACY) { Fwx.readHeader(ByteArrayInputStream(legacy)) }
        expectCode(FwxError.LEGACY) { Fwx.readHeader(ByteArrayInputStream("TSNAPE1".toByteArray())) }
        expectCode(FwxError.NOT_AN_EXPORT) { Fwx.readHeader(ByteArrayInputStream("TSNAPE".toByteArray())) }
        assertEquals(FwxError.LEGACY, T.openAll(legacy))
        assertEquals(FwxError.NOT_AN_EXPORT, T.openAll("{\"not\":\"an export\"}".toByteArray()))
        // Valid FWX magic but under 12 bytes is damage, not a foreign file.
        assertEquals(FwxError.DAMAGED, T.openAll(T.resource("V1.fwx").copyOf(11)))
    }
}
