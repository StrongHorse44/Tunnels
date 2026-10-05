package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.export.fwx.FwxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HeaderReaderTest {
    private fun read(bytes: ByteArray) = HeaderReader.read { ByteArrayInputStream(bytes) }

    @Test
    fun readsAppSchemaAndTime() {
        val r = read(Fixtures.header("prikey", schema = 3, createdMs = 1_700_000_000_123L)) as HeaderRead.Bundle
        assertEquals(HeaderRead.Bundle("prikey", 3, 1_700_000_000_123L), r)
    }

    @Test
    fun readsAHeaderTheRealWriterMade() {
        val out = ByteArrayOutputStream()
        val pass = "correct horse battery staple".toCharArray()
        val w = FwxWriter(out, "mardigras", 2, 1_760_000_000_000L, pass)
        w.entry("manifest.json", 2, ByteArrayInputStream("{}".toByteArray()))
        w.finish()
        assertEquals(HeaderRead.Bundle("mardigras", 2, 1_760_000_000_000L), read(out.toByteArray()))
    }

    @Test
    fun neverReadsPastTheHeaderAndItsMac() {
        val big = Fixtures.header("lumen", createdMs = 5, bodyBytes = 3_000_000)
        var total = 0
        val stream = object : ByteArrayInputStream(big) {
            override fun read(): Int = super.read().also { if (it >= 0) total++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) total += it }
        }
        assertTrue(HeaderReader.read { stream } is HeaderRead.Bundle)
        // header_len for "lumen" is 42 + 5 + 18 + 16 = 81, then the 32-byte MAC.
        assertEquals(81 + 32, total)
        assertTrue(total <= HeaderReader.MAX_BYTES)
    }

    @Test
    fun theLongestPossibleHeaderStaysUnder202() {
        // a = 32 reads at most 42 + 32 + 18 + 16 + 32 bytes; the codec bound is 170 + 32.
        val id = "a" + "b".repeat(31)
        val bytes = Fixtures.header(id, createdMs = 1, bodyBytes = 10_000)
        var total = 0
        val s = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) total += it }
            override fun read(): Int = super.read().also { if (it >= 0) total++ }
        }
        assertTrue(HeaderReader.read { s } is HeaderRead.Bundle)
        assertTrue(total <= HeaderReader.MAX_BYTES)
    }

    @Test
    fun legacyTunnelsExportIsRecognised() {
        assertEquals(HeaderRead.Legacy, read(Fixtures.legacy()))
    }

    @Test
    fun otherFilesAndDamageAreUnreadableNotErrors() {
        assertEquals(HeaderRead.Unreadable("NOT_AN_EXPORT"), read(ByteArray(500) { 0x41 }))
        assertEquals(HeaderRead.Unreadable("NOT_AN_EXPORT"), read(ByteArray(0)))
        assertEquals(HeaderRead.Unreadable("DAMAGED"), read(Fixtures.header("prikey", createdMs = 1).copyOf(40)))
        assertEquals(HeaderRead.Unreadable("KDF_PARAMS"), read(Fixtures.header("prikey", createdMs = 1, iterations = 1000)))
    }

    @Test
    fun anIoFailureIsUnreadable() {
        val r = HeaderReader.read { throw java.io.IOException("denied") }
        assertEquals(HeaderRead.Unreadable("IO"), r)
    }
}
