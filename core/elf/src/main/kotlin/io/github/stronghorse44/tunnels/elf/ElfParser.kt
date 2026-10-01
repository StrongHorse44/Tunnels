package io.github.stronghorse44.tunnels.elf

/**
 * Reads the exploit-mitigation facts of an ELF image held in memory: ELF32 and ELF64, little and big
 * endian. Every read is bounds-checked against the array, so a truncated or hostile file yields a
 * report with [HardeningReport.parseError] rather than an exception. Nothing but headers, the dynamic
 * section and imported symbol names is looked at.
 */
object ElfParser {
    const val MAX_PROGRAM_HEADERS = 512
    const val MAX_SECTION_HEADERS = 4096
    const val MAX_DYNAMIC_ENTRIES = 4096
    /** Symbol tables beyond this are read only up to here; the canary and fortify imports come early anyway. */
    const val MAX_SYMBOLS = 500_000
    private const val MAX_SYMBOL_NAME = 512

    const val ELF_MAGIC_ERROR = "not an ELF file"

    private const val ET_DYN = 3
    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val PT_GNU_STACK = 0x6474e551L
    private const val PT_GNU_RELRO = 0x6474e552L
    private const val PF_X = 1L
    private const val SHT_SYMTAB = 2L
    private const val SHT_STRTAB = 3L
    private const val SHT_DYNSYM = 11L
    private const val DT_NULL = 0L
    private const val DT_HASH = 4L
    private const val DT_STRTAB = 5L
    private const val DT_SYMTAB = 6L
    private const val DT_STRSZ = 10L
    private const val DT_SYMENT = 11L
    private const val DT_BIND_NOW = 24L
    private const val DT_FLAGS = 30L
    private const val DT_GNU_HASH = 0x6ffffef5L
    private const val DT_FLAGS_1 = 0x6ffffffbL
    private const val DF_BIND_NOW = 0x8L
    private const val DF_1_NOW = 0x1L

    private const val CANARY_SYMBOL = "__stack_chk_fail"

    fun parse(bytes: ByteArray): HardeningReport = try {
        Reader(bytes).parse()
    } catch (e: ElfFormatException) {
        HardeningReport.failed(e.message ?: "malformed")
    } catch (e: RuntimeException) {
        HardeningReport.failed("unreadable (${e.javaClass.simpleName})")
    }

    /** e_machine to a short architecture name. */
    fun archName(machine: Int): String = when (machine) {
        3 -> "x86"
        40 -> "arm"
        62 -> "x86_64"
        183 -> "arm64"
        243 -> "riscv64"
        else -> "other"
    }

    private class ElfFormatException(message: String) : RuntimeException(message)

    private class Segment(val offset: Long, val vaddr: Long, val fileSize: Long)

    private class Section(val type: Long, val offset: Long, val size: Long, val link: Int, val entSize: Long)

    private class Reader(private val b: ByteArray) {
        private val size = b.size
        private var is64 = false
        private var bigEndian = false

        private fun fail(message: String): Nothing = throw ElfFormatException(message)

        private fun need(offset: Long, length: Int) {
            if (offset < 0 || length < 0 || offset > size - length) fail("offset $offset is outside the file")
        }

        private fun u8(offset: Long): Int {
            need(offset, 1)
            return b[offset.toInt()].toInt() and 0xFF
        }

        private fun u16(offset: Long): Int {
            need(offset, 2)
            val i = offset.toInt()
            val lo = b[i].toInt() and 0xFF
            val hi = b[i + 1].toInt() and 0xFF
            return if (bigEndian) (lo shl 8) or hi else (hi shl 8) or lo
        }

        private fun u32(offset: Long): Long = unsigned(offset, 4)

        private fun u64(offset: Long): Long {
            val v = unsigned(offset, 8)
            if (v < 0) fail("value at $offset is too large")
            return v
        }

        private fun unsigned(offset: Long, length: Int): Long {
            need(offset, length)
            val i = offset.toInt()
            var v = 0L
            for (k in 0 until length) {
                val byte = (b[i + k].toInt() and 0xFF).toLong()
                v = if (bigEndian) (v shl 8) or byte else v or (byte shl (8 * k))
            }
            return v
        }

        private fun word(offset: Long): Long = if (is64) u64(offset) else u32(offset)

        fun parse(): HardeningReport {
            if (size < 16 || u8(0) != 0x7F || u8(1) != 'E'.code || u8(2) != 'L'.code || u8(3) != 'F'.code) fail(ELF_MAGIC_ERROR)
            is64 = when (u8(4)) {
                1 -> false
                2 -> true
                else -> fail("unknown ELF class")
            }
            bigEndian = when (u8(5)) {
                1 -> false
                2 -> true
                else -> fail("unknown byte order")
            }
            val type = u16(16)
            val arch = archName(u16(18))
            val phoff = word(if (is64) 32 else 28)
            val shoff = word(if (is64) 40 else 32)
            val phentsize = u16(if (is64) 54 else 42)
            val phnum = u16(if (is64) 56 else 44)
            val shentsize = u16(if (is64) 58 else 46)
            val shnum = u16(if (is64) 60 else 48)

            // Program headers: the loader's view, always present in anything that runs.
            if (phnum > MAX_PROGRAM_HEADERS) fail("too many program headers ($phnum)")
            val minPhent = if (is64) 56 else 32
            if (phnum > 0 && phentsize < minPhent) fail("program header entry too small")
            var stackFlags: Long? = null
            var relro = false
            var dynamic: Segment? = null
            val loads = ArrayList<Segment>()
            for (i in 0 until phnum) {
                val off = phoff + i.toLong() * phentsize
                val pType = u32(off)
                val seg: Segment
                val flags: Long
                if (is64) {
                    flags = u32(off + 4)
                    seg = Segment(u64(off + 8), u64(off + 16), u64(off + 32))
                } else {
                    seg = Segment(u32(off + 4), u32(off + 8), u32(off + 16))
                    flags = u32(off + 24)
                }
                when (pType) {
                    PT_LOAD -> loads += seg
                    PT_GNU_STACK -> stackFlags = flags
                    PT_GNU_RELRO -> relro = true
                    PT_DYNAMIC -> dynamic = seg
                }
            }
            // ET_DYN covers both shared objects and PIE executables (some apps ship executables named *.so);
            // ET_EXEC is the only fixed-address form.
            val pie = type == ET_DYN
            val nx = stackFlags != null && (stackFlags and PF_X) == 0L

            // Section headers: optional, often stripped. Unreadable ones count as absent.
            val sections = readSections(shoff, shentsize, shnum)
            val stripped = sections.none { it.type == SHT_SYMTAB }

            // Dynamic section: binding flags and the fallback route to the symbol table.
            var bindNow = false
            var dtSymtab = -1L
            var dtStrtab = -1L
            var dtStrsz = -1L
            var dtSyment = -1L
            var dtHash = -1L
            var dtGnuHash = -1L
            dynamic?.let { seg ->
                val entrySize = if (is64) 16 else 8
                val count = minOf(seg.fileSize / entrySize, MAX_DYNAMIC_ENTRIES.toLong()).toInt()
                for (i in 0 until count) {
                    val off = seg.offset + i.toLong() * entrySize
                    val tag = word(off)
                    val value = word(off + entrySize / 2)
                    when (tag) {
                        DT_NULL -> break
                        DT_BIND_NOW -> bindNow = true
                        DT_FLAGS -> if ((value and DF_BIND_NOW) != 0L) bindNow = true
                        DT_FLAGS_1 -> if ((value and DF_1_NOW) != 0L) bindNow = true
                        DT_SYMTAB -> dtSymtab = value
                        DT_STRTAB -> dtStrtab = value
                        DT_STRSZ -> dtStrsz = value
                        DT_SYMENT -> dtSyment = value
                        DT_HASH -> dtHash = value
                        DT_GNU_HASH -> dtGnuHash = value
                    }
                }
            }

            // Symbols: prefer section headers, fall back to the dynamic section through PT_LOAD mapping.
            val symbolSize = (if (is64) 24 else 16).toLong()
            var canary = false
            var fortify = false
            val dynsym = sections.firstOrNull { it.type == SHT_DYNSYM }
            val dynstr = dynsym?.let { sections.getOrNull(it.link) }?.takeIf { it.type == SHT_STRTAB }
            val table: SymbolTable? = if (dynsym != null && dynstr != null) {
                val entSize = if (dynsym.entSize > 0) dynsym.entSize else symbolSize
                SymbolTable(dynsym.offset, dynsym.size / entSize, entSize, dynstr.offset, dynstr.size)
            } else if (dtSymtab >= 0 && dtStrtab >= 0) {
                val symOff = fileOffset(loads, dtSymtab)
                val strOff = fileOffset(loads, dtStrtab)
                if (symOff != null && strOff != null) {
                    val entSize = if (dtSyment > 0) dtSyment else symbolSize
                    val count = symbolCount(loads, dtHash, dtGnuHash) ?: ((strOff - symOff) / entSize).coerceAtLeast(0)
                    val strSize = if (dtStrsz > 0) dtStrsz else size - strOff
                    SymbolTable(symOff, count, entSize, strOff, strSize)
                } else null
            } else null
            if (table != null) {
                try {
                    val count = minOf(table.count, MAX_SYMBOLS.toLong())
                    val nameOffset = 0L
                    val shndxOffset = if (is64) 6L else 14L
                    val strEnd = minOf(table.strOffset + table.strSize, size.toLong())
                    for (i in 1 until count) {
                        val off = table.offset + i * table.entSize
                        val shndx = u16(off + shndxOffset)
                        if (shndx != 0) continue // defined here, not imported
                        val name = u32(off + nameOffset)
                        if (name == 0L) continue
                        val at = table.strOffset + name
                        if (at + 1 >= strEnd || u8(at) != '_'.code || u8(at + 1) != '_'.code) continue
                        val symbol = cString(at, strEnd) ?: continue
                        if (symbol == CANARY_SYMBOL) canary = true
                        else if (symbol.length > 6 && symbol.endsWith("_chk")) fortify = true
                        if (canary && fortify) break
                    }
                } catch (_: ElfFormatException) {
                    // A corrupt symbol table hides imports but does not make the headers wrong.
                }
            }

            return HardeningReport(arch, is64, pie, nx, relro, bindNow, canary, fortify, stripped)
        }

        private class SymbolTable(val offset: Long, val count: Long, val entSize: Long, val strOffset: Long, val strSize: Long)

        private fun readSections(shoff: Long, shentsize: Int, shnum: Int): List<Section> {
            if (shnum == 0 || shoff <= 0 || shnum > MAX_SECTION_HEADERS) return emptyList()
            val minShent = if (is64) 64 else 40
            if (shentsize < minShent) return emptyList()
            return try {
                List(shnum) { i ->
                    val off = shoff + i.toLong() * shentsize
                    if (is64) Section(u32(off + 4), u64(off + 24), u64(off + 32), u32(off + 40).toInt(), u64(off + 56))
                    else Section(u32(off + 4), u32(off + 16), u32(off + 20), u32(off + 24).toInt(), u32(off + 36))
                }
            } catch (_: ElfFormatException) {
                emptyList()
            }
        }

        /** Maps a virtual address to a file offset through the PT_LOAD segments. */
        private fun fileOffset(loads: List<Segment>, vaddr: Long): Long? {
            for (seg in loads) {
                if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.fileSize) return seg.offset + (vaddr - seg.vaddr)
            }
            return null
        }

        /** Number of dynamic symbols from DT_HASH (nchain) or by walking DT_GNU_HASH; null when neither works. */
        private fun symbolCount(loads: List<Segment>, dtHash: Long, dtGnuHash: Long): Long? {
            if (dtHash >= 0) {
                val off = fileOffset(loads, dtHash) ?: return null
                return try { u32(off + 4) } catch (_: ElfFormatException) { null }
            }
            if (dtGnuHash >= 0) {
                val off = fileOffset(loads, dtGnuHash) ?: return null
                return try {
                    val nbuckets = u32(off)
                    val symOffset = u32(off + 4)
                    val bloomSize = u32(off + 8)
                    if (nbuckets > MAX_SYMBOLS || bloomSize > MAX_SYMBOLS) return null
                    val wordSize = if (is64) 8 else 4
                    val buckets = off + 16 + bloomSize * wordSize
                    val chains = buckets + nbuckets * 4
                    var max = symOffset
                    for (bucket in 0 until nbuckets) {
                        var index = u32(buckets + bucket * 4)
                        if (index < symOffset) continue
                        var steps = 0
                        while (steps++ < MAX_SYMBOLS) {
                            val chain = u32(chains + (index - symOffset) * 4)
                            index++
                            if ((chain and 1L) != 0L) break
                        }
                        if (index > max) max = index
                    }
                    max
                } catch (_: ElfFormatException) {
                    null
                }
            }
            return null
        }

        private fun cString(at: Long, end: Long): String? {
            var i = at
            val sb = StringBuilder()
            while (i < end && sb.length < MAX_SYMBOL_NAME) {
                val c = u8(i)
                if (c == 0) return sb.toString()
                sb.append(c.toChar())
                i++
            }
            return null
        }
    }
}
