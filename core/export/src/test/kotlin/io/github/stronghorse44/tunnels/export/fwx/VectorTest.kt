// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/VectorTest.kt at a4e418d (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Section 9.3: every positive vector is byte-exact, and the committed files match what this codec writes. */
class VectorTest {
    private fun jsonField(json: String, key: String): String =
        Regex("\"$key\": \"?([^\",\\n]*)\"?").find(json)!!.groupValues[1]

    @Test
    fun committedFilesMatchTheCodec() {
        // JSON (every intermediate value), .fwx files, crafted files and the legacy fixture.
        for ((name, bytes) in VectorGen.all()) {
            assertArrayEquals("regenerate codec/vectors/fwx-v1/$name", T.resource(name), bytes)
        }
    }

    @Test
    fun shapesMatchTheSpec() {
        val v1 = T.pack(T.vector("V1"))
        assertEquals(10267, v1.size)
        assertEquals(83, Fwx.u16(v1, 10))
        assertEquals(10104, T.streamOf(T.vector("V1")).size)
        assertArrayEquals(T.hex("0000000000"), T.streamOf(T.vector("V2")))
        assertEquals(115 + 5 + 16, T.pack(T.vector("V2")).size)
        // V3: L = 8192, two full chunks, no empty third chunk.
        assertEquals(8192, T.streamOf(T.vector("V3")).size)
        assertEquals(115 + 2 * (4096 + 16), T.pack(T.vector("V3")).size)
        assertArrayEquals(T.resource("V4.fwx"), T.resource("V5.fwx"))
        assertEquals(97 + 32 + 10104 + 48, T.pack(T.vector("V7")).size)
    }

    @Test
    fun v6IsGeneratedFromItsInputs() {
        val v = T.vector("V6")
        val digest = MessageDigest.getInstance("SHA-256")
        val out = object : java.io.OutputStream() {
            var size = 0L
            override fun write(b: Int) { digest.update(b.toByte()); size++ }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); size += len }
        }
        T.pack(v, out)
        val json = T.resourceText("V6.json")
        assertEquals(jsonField(json, "file_sha256"), T.hex(digest.digest()))
        assertEquals(jsonField(json, "file_length").toLong(), out.size)
        assertEquals(76 + 7 + 32 + 3145752 + 4 * 16L, out.size)
        assertEquals("4", jsonField(json, "chunk_count"))
    }

    @Test
    fun readerOpensEveryPositiveVector() {
        for (v in T.VECTORS) {
            val file = if (v.commitFile) T.resource("${v.id}.fwx") else T.pack(v)
            val r = FwxReader(ByteArrayInputStream(file), v.passphrase.toCharArray(), v.appId, 1L..v.schema)
            assertTrue(r.header.verified)
            assertEquals(v.appId, r.header.appId)
            assertEquals(v.schema, r.header.schemaVersion)
            assertEquals(T.CREATED_MS, r.header.createdMs)
            assertEquals(v.iterations, r.header.kdfIterations)
            assertEquals(v.chunkSize, r.header.chunkSize)
            for (spec in v.entries) {
                val e = r.next()!!
                assertEquals(spec.name, e.name)
                assertEquals(spec.length, e.length)
                assertEquals(T.sha256(spec.bytes()), T.sha256(e.stream.readBytes()))
            }
            assertNull(r.next())
            assertNull(r.next())
        }
    }

    @Test
    fun v5PassphraseOpensV4AndViceVersa() {
        assertNull(T.openAll(T.resource("V4.fwx"), T.V5_PASSPHRASE.toCharArray()))
        assertNull(T.openAll(T.resource("V5.fwx"), T.V4_PASSPHRASE.toCharArray()))
    }

    @Test
    fun readHeaderWithoutPassphrase() {
        val file = T.resource("V7.fwx")
        val counter = T.CountingStream(ByteArrayInputStream(file))
        val h = Fwx.readHeader(counter, file.size.toLong())
        assertEquals(false, h.verified)
        assertEquals("lumen", h.appId)
        assertEquals(7L, h.schemaVersion)
        assertEquals(T.CREATED_MS, h.createdMs)
        assertEquals(600_001, h.kdfIterations)
        assertEquals(32, h.saltLength)
        assertEquals(4096, h.chunkSize)
        assertEquals(file.size.toLong(), h.fileLength)
        assertEquals(h.headerLength + 32L, counter.count)
        assertTrue(counter.count <= 202)
    }

    /** Opt-in cross-check: -Dfwx.crosscheck=DIR holds bundles packed by tools/fwx.py and an expect.txt. */
    @Test
    fun opensBundlesPackedByPython() {
        val dir = System.getProperty("fwx.crosscheck") ?: return
        val lines = java.io.File(dir, "expect.txt").readLines().filter { it.isNotBlank() }
        var files = 0
        for ((file, group) in lines.map { it.split('\t') }.groupBy { it[0] }) {
            val r = FwxReader(java.io.File(dir, file).inputStream(), T.PASSPHRASE.toCharArray(), null, null)
            for (row in group.filter { it.size == 3 }) {
                val e = r.next()!!
                assertEquals(row[1], e.name)
                assertEquals(row[2], T.sha256(e.stream.readBytes()))
            }
            assertNull(r.next())
            files++
        }
        assertTrue(files > 0)
    }
}
