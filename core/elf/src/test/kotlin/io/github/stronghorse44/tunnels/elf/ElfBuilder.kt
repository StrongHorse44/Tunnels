package io.github.stronghorse44.tunnels.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a tiny but structurally complete ELF image: header, program headers (LOAD, DYNAMIC and the
 * optional INTERP, GNU_STACK, GNU_RELRO), .dynsym/.dynstr, a SysV or GNU hash table, the dynamic
 * section, an optional static .symtab and optional section headers. Enough for every path of
 * [ElfParser]; nothing in it is loadable.
 */
class ElfBuilder(
    val is64: Boolean = true,
    val bigEndian: Boolean = false,
    val machine: Int = EM_AARCH64,
    val type: Int = ET_DYN,
    val interp: Boolean = false,
    /** PT_GNU_STACK p_flags, or null to leave the header out. */
    val gnuStack: Int? = PF_R or PF_W,
    val relro: Boolean = true,
    val bindNow: BindNow = BindNow.FLAGS_1,
    /** Undefined (imported) dynamic symbols. */
    val imports: List<String> = listOf("__stack_chk_fail", "__memcpy_chk", "malloc"),
    /** Dynamic symbols defined in this file. */
    val defined: List<String> = listOf("JNI_OnLoad"),
    val sectionHeaders: Boolean = true,
    /** A static .symtab, i.e. not stripped. */
    val symtab: Boolean = false,
    val hash: Hash = Hash.SYSV,
) {
    enum class BindNow { NONE, DT_BIND_NOW, FLAGS, FLAGS_1 }

    enum class Hash { SYSV, GNU, NONE }

    private val headerSize = if (is64) 64 else 52
    private val phentSize = if (is64) 56 else 32
    private val shentSize = if (is64) 64 else 40
    private val symSize = if (is64) 24 else 16
    private val dynSize = if (is64) 16 else 8
    private val wordSize = if (is64) 8 else 4

    private val names = imports + defined
    private val symCount = 1 + names.size
    private val phnum = 2 + (if (interp) 1 else 0) + (if (gnuStack != null) 1 else 0) + (if (relro) 1 else 0)
    private val interpBytes = "/system/bin/linker64\u0000".toByteArray()
    private val dynstrBytes: ByteArray
    private val nameOffsets: Map<String, Int>
    private val shstrtab = "\u0000.dynsym\u0000.dynstr\u0000.dynamic\u0000.shstrtab\u0000.symtab\u0000"
    private val dynCount = 4 + (if (hash != Hash.NONE) 1 else 0) + (if (bindNow != BindNow.NONE) 1 else 0) + 1

    // Layout (file offsets); .dynsym precedes .dynstr so the hash-less fallback can size the table.
    private val phOff = headerSize
    private val interpOff = phOff + phnum * phentSize
    private val dynsymOff = interpOff + (if (interp) interpBytes.size else 0)
    private val dynstrOff = dynsymOff + symCount * symSize
    private val hashOff: Int
    private val hashSize: Int
    private val dynamicOff: Int
    private val symtabOff: Int
    private val shstrOff: Int
    private val shOff: Int
    private val shnum = if (symtab) 6 else 5
    private val total: Int

    init {
        val sb = StringBuilder("\u0000")
        val offsets = HashMap<String, Int>()
        for (n in names) {
            offsets[n] = sb.length
            sb.append(n).append('\u0000')
        }
        dynstrBytes = sb.toString().toByteArray()
        nameOffsets = offsets
        hashOff = dynstrOff + dynstrBytes.size
        hashSize = when (hash) {
            Hash.SYSV -> 4 * (2 + 1 + symCount)
            Hash.GNU -> 16 + wordSize + 4 + 4 * (symCount - 1)
            Hash.NONE -> 0
        }
        dynamicOff = hashOff + hashSize
        symtabOff = dynamicOff + dynCount * dynSize
        shstrOff = symtabOff + (if (symtab) symSize else 0)
        shOff = shstrOff + shstrtab.length
        total = shOff + (if (sectionHeaders) shnum * shentSize else 0)
    }

    fun build(): ByteArray {
        val buf = ByteBuffer.allocate(total).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        fun word(v: Long) { if (is64) buf.putLong(v) else buf.putInt(v.toInt()) }
        fun vaddr(off: Int) = BASE + off

        // ELF header.
        buf.put(0x7F).put('E'.code.toByte()).put('L'.code.toByte()).put('F'.code.toByte())
        buf.put(if (is64) 2 else 1).put(if (bigEndian) 2 else 1).put(1).put(0)
        buf.put(ByteArray(8))
        buf.putShort(type.toShort()).putShort(machine.toShort()).putInt(1)
        word(vaddr(0)) // e_entry
        word(phOff.toLong())
        word(if (sectionHeaders) shOff.toLong() else 0L)
        buf.putInt(0) // e_flags
        buf.putShort(headerSize.toShort()).putShort(phentSize.toShort()).putShort(phnum.toShort())
        buf.putShort(shentSize.toShort()).putShort((if (sectionHeaders) shnum else 0).toShort()).putShort((if (sectionHeaders) 4 else 0).toShort())

        // Program headers.
        fun phdr(pType: Long, flags: Int, off: Int, size: Int) {
            buf.putInt(pType.toInt())
            if (is64) {
                buf.putInt(flags)
                buf.putLong(off.toLong()).putLong(vaddr(off)).putLong(vaddr(off)).putLong(size.toLong()).putLong(size.toLong()).putLong(8)
            } else {
                buf.putInt(off).putInt(vaddr(off).toInt()).putInt(vaddr(off).toInt()).putInt(size).putInt(size).putInt(flags).putInt(4)
            }
        }
        phdr(PT_LOAD, PF_R or PF_X, 0, total)
        if (interp) phdr(PT_INTERP, PF_R, interpOff, interpBytes.size)
        phdr(PT_DYNAMIC, PF_R or PF_W, dynamicOff, dynCount * dynSize)
        if (gnuStack != null) phdr(PT_GNU_STACK, gnuStack, 0, 0)
        if (relro) phdr(PT_GNU_RELRO, PF_R, dynamicOff, dynCount * dynSize)
        check(buf.position() == interpOff)

        if (interp) buf.put(interpBytes)

        // .dynsym: null symbol, then imports (SHN_UNDEF) and defined symbols (section 1).
        check(buf.position() == dynsymOff)
        fun sym(name: Int, shndx: Int) {
            if (is64) buf.putInt(name).put(0x12).put(0).putShort(shndx.toShort()).putLong(0).putLong(0)
            else buf.putInt(name).putInt(0).putInt(0).put(0x12).put(0).putShort(shndx.toShort())
        }
        sym(0, 0)
        for (n in imports) sym(nameOffsets.getValue(n), 0)
        for (n in defined) sym(nameOffsets.getValue(n), 1)

        check(buf.position() == dynstrOff)
        buf.put(dynstrBytes)

        // Hash table: one bucket chaining through every symbol.
        check(buf.position() == hashOff)
        when (hash) {
            Hash.SYSV -> {
                buf.putInt(1).putInt(symCount).putInt(if (symCount > 1) 1 else 0)
                for (i in 0 until symCount) buf.putInt(if (i in 1 until symCount - 1) i + 1 else 0)
            }
            Hash.GNU -> {
                buf.putInt(1).putInt(1).putInt(1).putInt(0) // nbuckets, symoffset, bloom_size, bloom_shift
                word(0) // bloom filter word
                buf.putInt(if (symCount > 1) 1 else 0) // bucket
                for (i in 1 until symCount) buf.putInt(if (i == symCount - 1) 0x11 else 0x10) // chain, last has bit 0
            }
            Hash.NONE -> {}
        }

        // Dynamic section (addresses are virtual).
        check(buf.position() == dynamicOff)
        fun dyn(tag: Long, value: Long) { word(tag); word(value) }
        dyn(DT_SYMTAB, vaddr(dynsymOff))
        dyn(DT_STRTAB, vaddr(dynstrOff))
        dyn(DT_STRSZ, dynstrBytes.size.toLong())
        dyn(DT_SYMENT, symSize.toLong())
        when (hash) {
            Hash.SYSV -> dyn(DT_HASH, vaddr(hashOff))
            Hash.GNU -> dyn(DT_GNU_HASH, vaddr(hashOff))
            Hash.NONE -> {}
        }
        when (bindNow) {
            BindNow.NONE -> {}
            BindNow.DT_BIND_NOW -> dyn(DT_BIND_NOW, 0)
            BindNow.FLAGS -> dyn(DT_FLAGS, DF_BIND_NOW)
            BindNow.FLAGS_1 -> dyn(DT_FLAGS_1, DF_1_NOW)
        }
        dyn(DT_NULL, 0)

        check(buf.position() == symtabOff)
        if (symtab) sym(0, 0)

        check(buf.position() == shstrOff)
        buf.put(shstrtab.toByteArray())

        if (sectionHeaders) {
            check(buf.position() == shOff)
            fun shdr(name: Int, shType: Long, off: Int, size: Int, link: Int, entSize: Int) {
                buf.putInt(name).putInt(shType.toInt())
                if (is64) buf.putLong(0).putLong(if (off == 0) 0 else vaddr(off)).putLong(off.toLong()).putLong(size.toLong()).putInt(link).putInt(0).putLong(1).putLong(entSize.toLong())
                else buf.putInt(0).putInt(if (off == 0) 0 else vaddr(off).toInt()).putInt(off).putInt(size).putInt(link).putInt(0).putInt(1).putInt(entSize)
            }
            shdr(0, 0, 0, 0, 0, 0)
            shdr(shstrtab.indexOf(".dynsym"), SHT_DYNSYM, dynsymOff, symCount * symSize, 2, symSize)
            shdr(shstrtab.indexOf(".dynstr"), SHT_STRTAB, dynstrOff, dynstrBytes.size, 0, 0)
            shdr(shstrtab.indexOf(".dynamic"), SHT_DYNAMIC, dynamicOff, dynCount * dynSize, 2, dynSize)
            shdr(shstrtab.indexOf(".shstrtab"), SHT_STRTAB, shstrOff, shstrtab.length, 0, 0)
            if (symtab) shdr(shstrtab.indexOf(".symtab"), SHT_SYMTAB, symtabOff, symSize, 2, symSize)
        }
        check(buf.position() == total) { "wrote ${buf.position()} of $total" }
        return buf.array()
    }

    companion object {
        const val BASE = 0x10000L
        const val ET_EXEC = 2
        const val ET_DYN = 3
        const val EM_386 = 3
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183
        const val EM_RISCV = 243
        const val EM_PPC = 20
        const val PF_X = 1
        const val PF_W = 2
        const val PF_R = 4
        private const val PT_LOAD = 1L
        private const val PT_DYNAMIC = 2L
        private const val PT_INTERP = 3L
        private const val PT_GNU_STACK = 0x6474e551L
        private const val PT_GNU_RELRO = 0x6474e552L
        private const val SHT_SYMTAB = 2L
        private const val SHT_STRTAB = 3L
        private const val SHT_DYNAMIC = 6L
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
    }
}
