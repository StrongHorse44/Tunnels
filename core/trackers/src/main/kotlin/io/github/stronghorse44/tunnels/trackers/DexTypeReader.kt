package io.github.stronghorse44.tunnels.trackers

/** Outcome of reading one dex file's type table. */
data class DexScan(
    /** False when the bytes are not a dex file we can read; [reason] says why. */
    val valid: Boolean,
    val reason: String? = null,
    val version: Int = 0,
    /** Number of type descriptors visited. */
    val types: Int = 0,
    /** True when the file declared more types than [DexTypeReader.MAX_TYPES]; the rest were not read. */
    val capped: Boolean = false,
    /** Tracker id to number of matching class descriptors. */
    val hits: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun invalid(reason: String) = DexScan(valid = false, reason = reason)
    }
}

/**
 * Streams the type descriptors of a dex file (versions 035 to 041, little-endian) straight out of
 * the byte array: for each type_id the string_id is looked up and the MUTF-8 bytes are handed to
 * the matcher in place. Nothing beyond the header and the two index tables is interpreted, and all
 * offsets are bounds-checked, so a truncated or hostile file yields an invalid [DexScan] instead of
 * an exception.
 */
object DexTypeReader {
    const val HEADER_SIZE = 0x70
    const val MAX_TYPES = 200_000
    /** Longest descriptor we will look at; real ones are a few hundred bytes at most. */
    const val MAX_DESCRIPTOR = 1024
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val REVERSE_ENDIAN_CONSTANT = 0x78563412

    private val MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())

    /** Receives one descriptor as a byte range; must not keep a reference to the array range. */
    fun interface DescriptorVisitor {
        fun visit(bytes: ByteArray, start: Int, end: Int)
    }

    /** Parses [dex] and counts descriptors that belong to a tracker in [matcher]. */
    fun scan(dex: ByteArray, matcher: TrackerMatcher = TrackerMatcher.DEFAULT): DexScan {
        val hits = HashMap<String, Int>()
        return forEachDescriptor(dex) { bytes, start, end ->
            matcher.match(bytes, start, end)?.let { id -> hits[id] = (hits[id] ?: 0) + 1 }
        }.copy(hits = hits)
    }

    /** Descriptors as strings; for tests and small files only, since it allocates one String per type. */
    fun descriptors(dex: ByteArray, limit: Int = 10_000): List<String> {
        val out = ArrayList<String>()
        forEachDescriptor(dex) { bytes, start, end ->
            if (out.size < limit) out += String(bytes, start, end - start, Charsets.ISO_8859_1)
        }
        return out
    }

    /** Walks every type descriptor. Returns an invalid [DexScan] (no visits) when the header is unusable. */
    fun forEachDescriptor(dex: ByteArray, visitor: DescriptorVisitor): DexScan {
        if (dex.size < HEADER_SIZE) return DexScan.invalid("file shorter than a dex header")
        for (i in MAGIC.indices) if (dex[i] != MAGIC[i]) return DexScan.invalid("not a dex file")
        val version = dexVersion(dex) ?: return DexScan.invalid("unknown dex version")
        if (version !in 35..41) return DexScan.invalid("unsupported dex version $version")
        when (u32(dex, 0x28)) {
            ENDIAN_CONSTANT -> Unit
            REVERSE_ENDIAN_CONSTANT -> return DexScan.invalid("big-endian dex not supported")
            else -> return DexScan.invalid("bad endian tag")
        }
        val headerSize = u32(dex, 0x24)
        if (headerSize < HEADER_SIZE) return DexScan.invalid("bad header size")
        val stringCount = u32(dex, 0x38)
        val stringOff = u32(dex, 0x3C)
        val typeCount = u32(dex, 0x40)
        val typeOff = u32(dex, 0x44)
        if (!tableFits(dex.size, stringOff, stringCount)) return DexScan.invalid("string table out of bounds")
        if (!tableFits(dex.size, typeOff, typeCount)) return DexScan.invalid("type table out of bounds")

        val capped = typeCount > MAX_TYPES
        val n = if (capped) MAX_TYPES else typeCount
        var visited = 0
        for (i in 0 until n) {
            val descriptorIdx = u32(dex, typeOff + 4 * i)
            if (descriptorIdx < 0 || descriptorIdx >= stringCount) continue
            var p = u32(dex, stringOff + 4 * descriptorIdx)
            if (p < 0 || p >= dex.size) continue
            // uleb128 utf16_size: skip it, the data is NUL-terminated.
            var lebBytes = 0
            while (p < dex.size && lebBytes < 5) {
                lebBytes++
                if (dex[p++].toInt() and 0x80 == 0) break
            }
            val start = p
            val limit = minOf(dex.size, start + MAX_DESCRIPTOR)
            while (p < limit && dex[p] != 0.toByte()) p++
            if (p > start) {
                visitor.visit(dex, start, p)
                visited++
            }
        }
        return DexScan(valid = true, version = version, types = visited, capped = capped)
    }

    /** "dex\n035\0" -> 35, or null when the version bytes are not three digits followed by NUL. */
    private fun dexVersion(dex: ByteArray): Int? {
        if (dex[7] != 0.toByte()) return null
        var v = 0
        for (i in 4..6) {
            val d = dex[i].toInt() - '0'.code
            if (d !in 0..9) return null
            v = v * 10 + d
        }
        return v
    }

    private fun tableFits(fileSize: Int, off: Int, count: Int): Boolean {
        if (count == 0) return true
        if (off < HEADER_SIZE || count < 0) return false
        val end = off.toLong() + 4L * count
        return end <= fileSize
    }

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
