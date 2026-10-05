package io.github.stronghorse44.tunnels.backups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

/** The wrapper that keeps a header read from touching the payload, on its own. */
class CappedTest {
    private fun counting(n: Int, seen: IntArray): java.io.InputStream = object : ByteArrayInputStream(ByteArray(n) { 1 }) {
        override fun read(): Int = super.read().also { if (it >= 0) seen[0]++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) seen[0] += it }
        override fun skip(n: Long): Long = super.skip(n).also { seen[0] += it.toInt() }
    }

    @Test
    fun singleByteReadsStopAtTheLimit() {
        val seen = IntArray(1)
        val c = Capped(counting(1000, seen), 10)
        var n = 0
        while (c.read() >= 0) n++
        assertEquals(10, n)
        assertEquals("the underlying stream gave only 10", 10, seen[0])
        assertEquals(-1, c.read())
    }

    @Test
    fun bulkReadsAreShortenedAndThenEnd() {
        val seen = IntArray(1)
        val c = Capped(counting(1000, seen), 202)
        val buf = ByteArray(500)
        assertEquals(202, c.read(buf, 0, 500))
        assertEquals(-1, c.read(buf, 0, 500))
        assertEquals(202, seen[0])
    }

    @Test
    fun skipCountsAgainstTheLimitToo() {
        val seen = IntArray(1)
        val c = Capped(counting(1000, seen), 20)
        assertEquals(15, c.skip(15))
        assertEquals(5, c.skip(100))
        assertEquals(0, c.skip(100))
        assertEquals(-1, c.read())
        assertEquals(20, seen[0])
    }

    @Test
    fun aZeroLengthReadAndMarkAreHarmless() {
        val c = Capped(ByteArrayInputStream(ByteArray(5)), 3)
        assertEquals(0, c.read(ByteArray(4), 0, 0))
        assertFalse(c.markSupported())
        assertEquals(3, c.available())
    }

    @Test
    fun aShortStreamEndsEarlyWithoutError() {
        val c = Capped(ByteArrayInputStream(ByteArray(4)), 202)
        assertEquals(4, c.read(ByteArray(10), 0, 10))
        assertEquals(-1, c.read())
    }
}
