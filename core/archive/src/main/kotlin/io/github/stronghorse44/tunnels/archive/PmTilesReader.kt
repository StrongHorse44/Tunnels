package io.github.stronghorse44.tunnels.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * PMTiles v3 (single-file map tile archive) presented as an archive: `metadata.json` plus one
 * `z/x/y.ext` entry per addressed tile. Gzip tiles and metadata are decompressed on extraction;
 * brotli and zstd tiles are written as stored, with a `.br` / `.zst` suffix.
 */
internal class PmTilesReader(private val file: File) : ArchiveReader {
    override val format = ArchiveFormat.PMTILES

    private val raf = RandomAccessFile(file, "r")
    private val header: Header
    private val entries: List<ArchiveEntry>

    /** Parallel to [entries]: absolute file offset and stored length of each entry's bytes. */
    private val offsets: LongArray
    private val lengths: LongArray
    private val metadataIndex: Int

    init {
        try {
            header = translating(false, null) { readHeader() }
            val tiles = translating(false, null) { collectTiles() }
            val list = ArrayList<ArchiveEntry>(tiles.size + 1)
            val spans = ArrayList<Pair<Long, Long>>(tiles.size + 1)
            fun add(path: String, offset: Long, length: Long, compression: Int) {
                val size = if (compression == COMPRESSION_NONE) length else -1
                list += ArchiveEntry(list.size, path, false, false, size, length, false)
                spans += offset to length
            }
            if (header.metadataLength > 0) add(METADATA, header.metadataOffset, header.metadataLength, header.internalCompression)
            metadataIndex = if (header.metadataLength > 0) 0 else -1
            val ext = extension(header.tileType) + storedSuffix(header.tileCompression)
            for (t in tiles) {
                val (z, x, y) = zxy(t.tileId)
                add("$z/$x/$y$ext", header.tileDataOffset + t.offset, t.length, header.tileCompression)
            }
            offsets = LongArray(spans.size) { spans[it].first }
            lengths = LongArray(spans.size) { spans[it].second }
            entries = list
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    override fun entries(): List<ArchiveEntry> = entries

    override fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits,
        progress: ExtractProgress,
        isCancelled: () -> Boolean,
    ): ExtractResult {
        val chosen = entries.filter { selection == null || it.index in selection }
        val run = Extraction(limits, file.length(), chosen.sumOf { it.size.coerceAtLeast(0) }.takeIf { it > 0 } ?: -1, progress, isCancelled)
        translating(false, null) {
            for (e in chosen) {
                run.beginEntry()
                val segments = SafePath.segments(e.path) ?: continue
                val stored = read(offsets[e.index], lengths[e.index])
                val compression = if (e.index == metadataIndex) header.internalCompression else header.tileCompression
                decoded(stored, compression).use { run.file(sink, segments, e.path, lengths[e.index], it) }
            }
        }
        return run.result()
    }

    override fun close() = raf.close()

    private fun readHeader(): Header {
        if (raf.length() < HEADER_SIZE) throw ArchiveException(ArchiveError.Corrupt("PMTiles header is truncated"))
        val b = read(0, HEADER_SIZE.toLong())
        val version = b[7].toInt() and 0xff
        if (version != 3) throw ArchiveException(ArchiveError.Unsupported("PMTiles version $version (only v3)"))
        val h = Header(
            rootOffset = le64(b, 8),
            rootLength = le64(b, 16),
            metadataOffset = le64(b, 24),
            metadataLength = le64(b, 32),
            leafOffset = le64(b, 40),
            leafLength = le64(b, 48),
            tileDataOffset = le64(b, 56),
            tileDataLength = le64(b, 64),
            internalCompression = b[97].toInt() and 0xff,
            tileCompression = b[98].toInt() and 0xff,
            tileType = b[99].toInt() and 0xff,
        )
        checkRange(h.rootOffset, h.rootLength, "root directory")
        checkRange(h.metadataOffset, h.metadataLength, "metadata")
        checkRange(h.leafOffset, h.leafLength, "leaf directories")
        checkRange(h.tileDataOffset, h.tileDataLength, "tile data")
        return h
    }

    /** Walks the root and leaf directories and expands run lengths into individual tiles. */
    private fun collectTiles(): List<Tile> {
        val out = ArrayList<Tile>()
        fun walk(offset: Long, length: Long, depth: Int) {
            if (depth > MAX_DEPTH) throw ArchiveException(ArchiveError.Corrupt("PMTiles directories nest too deeply"))
            for (d in parseDirectory(decompress(read(offset, length), header.internalCompression, MAX_DIRECTORY_BYTES))) {
                if (d.runLength == 0L) {
                    checkWithin(d.offset, d.length, header.leafLength, "leaf directory")
                    walk(header.leafOffset + d.offset, d.length, depth + 1)
                } else {
                    checkWithin(d.offset, d.length, header.tileDataLength, "tile")
                    if (out.size + d.runLength > MAX_TILES) {
                        throw ArchiveException(ArchiveError.LimitExceeded("more than $MAX_TILES tiles; Tunnels lists up to $MAX_TILES"))
                    }
                    for (k in 0 until d.runLength) out += Tile(d.tileId + k, d.offset, d.length)
                }
            }
        }
        walk(header.rootOffset, header.rootLength, 0)
        return out
    }

    private fun read(offset: Long, length: Long): ByteArray {
        if (length > Int.MAX_VALUE - 8) throw ArchiveException(ArchiveError.LimitExceeded("PMTiles section is too large"))
        val buf = ByteArray(length.toInt())
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    private fun checkRange(offset: Long, length: Long, what: String) {
        if (offset < 0 || length < 0 || offset > raf.length() - length) {
            throw ArchiveException(ArchiveError.Corrupt("PMTiles $what points outside the file"))
        }
    }

    private fun checkWithin(offset: Long, length: Long, sectionLength: Long, what: String) {
        if (offset < 0 || length < 0 || offset > sectionLength - length) {
            throw ArchiveException(ArchiveError.Corrupt("PMTiles $what points outside its section"))
        }
    }

    private class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val leafLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
    )

    private class Tile(val tileId: Long, val offset: Long, val length: Long)

    internal class DirEntry(val tileId: Long, val runLength: Long, val offset: Long, val length: Long)

    companion object {
        const val METADATA = "metadata.json"
        private const val HEADER_SIZE = 127
        private const val MAX_DEPTH = 4
        private const val MAX_TILES = 200_000L
        private const val MAX_DIRECTORY_BYTES = 64 shl 20

        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_GZIP = 2
        private const val COMPRESSION_BROTLI = 3
        private const val COMPRESSION_ZSTD = 4

        private fun le64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xff)
            return v
        }

        private fun extension(tileType: Int) = when (tileType) {
            1 -> ".mvt"
            2 -> ".png"
            3 -> ".jpg"
            4 -> ".webp"
            5 -> ".avif"
            else -> ".bin"
        }

        /** Compressions Tunnels can't decode are kept as stored and labelled. */
        private fun storedSuffix(compression: Int) = when (compression) {
            COMPRESSION_BROTLI -> ".br"
            COMPRESSION_ZSTD -> ".zst"
            else -> ""
        }

        private fun decoded(bytes: ByteArray, compression: Int): InputStream {
            val raw = ByteArrayInputStream(bytes)
            return if (compression == COMPRESSION_GZIP) ArchiveFormat.gzip(raw) else raw
        }

        private fun decompress(bytes: ByteArray, compression: Int, max: Int): ByteArray = when (compression) {
            COMPRESSION_NONE, 0 -> bytes
            COMPRESSION_GZIP -> {
                val out = ByteArrayOutputStream()
                ArchiveFormat.gzip(ByteArrayInputStream(bytes)).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        if (out.size() > max) throw ArchiveException(ArchiveError.LimitExceeded("PMTiles directory expands past ${max shr 20} MB"))
                    }
                }
                out.toByteArray()
            }
            else -> throw ArchiveException(ArchiveError.Unsupported("PMTiles with brotli or zstd directories"))
        }

        /** Decodes one directory: count, delta tile IDs, run lengths, lengths, offsets (0 = contiguous). */
        internal fun parseDirectory(bytes: ByteArray): List<DirEntry> {
            var pos = 0
            fun varint(): Long {
                var v = 0L
                var shift = 0
                while (true) {
                    if (pos >= bytes.size) throw ArchiveException(ArchiveError.Corrupt("PMTiles directory is truncated"))
                    if (shift > 63) throw ArchiveException(ArchiveError.Corrupt("PMTiles directory has a bad varint"))
                    val b = bytes[pos++].toInt() and 0xff
                    v = v or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return v
                    shift += 7
                }
            }
            val n = varint()
            // Every entry takes at least four bytes, so a larger count is a lie.
            if (n < 0 || n > bytes.size / 4 + 1) throw ArchiveException(ArchiveError.Corrupt("PMTiles directory entry count is implausible"))
            val count = n.toInt()
            val ids = LongArray(count)
            var last = 0L
            for (i in 0 until count) { last += varint(); ids[i] = last }
            val runs = LongArray(count) { varint() }
            val lens = LongArray(count) { varint() }
            val offs = LongArray(count)
            for (i in 0 until count) {
                val v = varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return List(count) { DirEntry(ids[it], runs[it], offs[it], lens[it]) }
        }

        /** Hilbert tile ID to (z, x, y), per the PMTiles v3 spec. */
        internal fun zxy(tileId: Long): Triple<Int, Long, Long> {
            var acc = 0L
            var z = 0
            while (true) {
                val count = 1L shl (2 * z)
                if (tileId - acc < count) return onLevel(z, tileId - acc)
                acc += count
                z++
                if (z > 31) throw ArchiveException(ArchiveError.Corrupt("PMTiles tile ID $tileId is out of range"))
            }
        }

        private fun onLevel(z: Int, pos: Long): Triple<Int, Long, Long> {
            val n = 1L shl z
            var t = pos
            var x = 0L
            var y = 0L
            var s = 1L
            while (s < n) {
                val rx = 1L and (t / 2)
                val ry = 1L and (t xor rx)
                if (ry == 0L) {
                    if (rx == 1L) { x = s - 1 - x; y = s - 1 - y }
                    val tmp = x; x = y; y = tmp
                }
                x += s * rx
                y += s * ry
                t /= 4
                s *= 2
            }
            return Triple(z, x, y)
        }
    }
}
