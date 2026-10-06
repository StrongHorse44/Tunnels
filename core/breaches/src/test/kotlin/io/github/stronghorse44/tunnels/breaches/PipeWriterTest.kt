package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PipeWriterTest {
    @Test
    fun aReaderThatReadsGetsTheBytesAndTheCopyIsZeroed() {
        val data = "hello".toByteArray()
        val copy = data.copyOf()
        val out = ByteArrayOutputStream()
        val aborts = AtomicInteger()
        PipeWriter.start(copy, out, abort = { aborts.incrementAndGet() }, timeoutMs = 5_000).join(5_000)
        assertArrayEquals(data, out.toByteArray())
        assertTrue(copy.all { it == 0.toByte() })
        assertEquals(0, aborts.get())
    }

    @Test
    fun aReaderThatNeverReadsIsCutOffAndTheCopyZeroed() {
        val copy = "secret".toByteArray()
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        // A write end whose reader never drains: write blocks until the write end is closed (the abort).
        val out = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                blocked.countDown()
                closed.await(10, TimeUnit.SECONDS)
                throw IOException("closed")
            }
        }
        val writer = PipeWriter.start(copy, out, abort = { closed.countDown() }, timeoutMs = 100)
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        writer.join(5_000)
        assertFalse(writer.isAlive)
        assertEquals(0L, closed.count)
        assertTrue("the copy is zeroed after the cut-off", copy.all { it == 0.toByte() })
    }
}
