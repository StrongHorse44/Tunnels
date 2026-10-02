package io.github.stronghorse44.tunnels.archive

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class PmTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun hilbertIdsMatchSpec() {
        assertEquals(Triple(0, 0L, 0L), PmTilesReader.zxy(0))
        assertEquals(Triple(1, 0L, 0L), PmTilesReader.zxy(1))
        assertEquals(Triple(1, 0L, 1L), PmTilesReader.zxy(2))
        assertEquals(Triple(1, 1L, 1L), PmTilesReader.zxy(3))
        assertEquals(Triple(1, 1L, 0L), PmTilesReader.zxy(4))
        assertEquals(Triple(2, 0L, 0L), PmTilesReader.zxy(5))
        // Every tile on a level appears exactly once.
        for (z in 0..6) {
            val base = ((1L shl (2 * z)) - 1) / 3
            val seen = (0 until (1L shl (2 * z))).map { PmTilesReader.zxy(base + it) }.toSet()
            assertEquals(1 shl (2 * z), seen.size)
            assertTrue(seen.all { (zz, x, y) -> zz == z && x in 0 until (1L shl z) && y in 0 until (1L shl z) })
        }
    }

    @Test
    fun listsAndExtractsTilesThroughLeafDirectory() {
        // Tile 0 alone; tiles 1-4 share one blob (run length 4), reached via a leaf directory.
        val tileA = "tile-a".toByteArray()
        val tileB = "tile-b".toByteArray()
        val tileData = gzip(tileA) + gzip(tileB)
        val leaf = gzip(directory(listOf(Dir(1, 4, gzip(tileA).size.toLong(), gzip(tileB).size.toLong()))))
        val root = gzip(directory(listOf(Dir(0, 1, 0, gzip(tileA).size.toLong()), Dir(1, 0, 0, leaf.size.toLong()))))
        val f = write("map.pmtiles", root, gzip("""{"name":"test"}""".toByteArray()), leaf, tileData, tileType = 1)

        assertEquals(ArchiveFormat.PMTILES, ArchiveFormat.detect(f))
        Archives.open(f, f.name).use { r ->
            assertEquals(
                listOf("metadata.json", "0/0/0.mvt", "1/0/0.mvt", "1/0/1.mvt", "1/1/1.mvt", "1/1/0.mvt"),
                r.entries().map { it.path },
            )
            val out = tmp.newFolder()
            val res = r.extract(null, DirectorySink(out))
            assertEquals(6, res.files)
            assertEquals("""{"name":"test"}""", File(out, "metadata.json").readText())
            assertEquals("tile-a", File(out, "0/0/0.mvt").readText())
            assertEquals("tile-b", File(out, "1/1/0.mvt").readText())
        }
    }

    @Test
    fun rejectsOutOfBoundsAndOtherVersions() {
        val root = gzip(directory(listOf(Dir(0, 1, 0, 1_000))))
        val bad = write("bad.pmtiles", root, ByteArray(0), ByteArray(0), "x".toByteArray(), tileType = 2)
        expectError<ArchiveError.Corrupt> { Archives.open(bad, bad.name) }

        val v2 = write("v2.pmtiles", root, ByteArray(0), ByteArray(0), ByteArray(0), tileType = 2, version = 2)
        expectError<ArchiveError.Unsupported> { Archives.open(v2, v2.name) }

        val bogusCount = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x7f)
        expectError<ArchiveError.Corrupt> { PmTilesReader.parseDirectory(bogusCount) }
    }

    @Test
    fun refusesHugeRunLengths() {
        val root = gzip(directory(listOf(Dir(0, 10_000_000, 0, 1))))
        val f = write("ocean.pmtiles", root, ByteArray(0), ByteArray(0), "x".toByteArray(), tileType = 2)
        expectError<ArchiveError.LimitExceeded> { Archives.open(f, f.name) }
    }

    private class Dir(val tileId: Long, val runLength: Long, val offset: Long, val length: Long)

    private fun directory(entries: List<Dir>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(e.tileId - last); last = e.tileId }
        for (e in entries) varint(e.runLength)
        for (e in entries) varint(e.length)
        for (e in entries) varint(e.offset + 1)
        return out.toByteArray()
    }

    private fun write(
        name: String,
        root: ByteArray,
        metadata: ByteArray,
        leaves: ByteArray,
        tiles: ByteArray,
        tileType: Int,
        version: Int = 3,
    ): File {
        val header = ByteArray(127)
        "PMTiles".toByteArray().copyInto(header)
        header[7] = version.toByte()
        var at = 127L
        fun section(slot: Int, bytes: ByteArray) {
            le64(header, slot, at)
            le64(header, slot + 8, bytes.size.toLong())
            at += bytes.size
        }
        section(8, root)
        section(24, metadata)
        section(40, leaves)
        section(56, tiles)
        header[97] = 2 // internal: gzip
        header[98] = 2 // tiles: gzip
        header[99] = tileType.toByte()
        return tmp.newFile(name).apply { writeBytes(header + root + metadata + leaves + tiles) }
    }

    private fun le64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GzipCompressorOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private inline fun <reified T : ArchiveError> expectError(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: ArchiveException) {
            assertTrue("got ${e.error}", e.error is T)
        }
    }
}
