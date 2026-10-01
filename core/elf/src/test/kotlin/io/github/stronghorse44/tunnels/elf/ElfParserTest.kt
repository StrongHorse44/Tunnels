package io.github.stronghorse44.tunnels.elf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ElfParserTest {
    private fun parse(builder: ElfBuilder) = ElfParser.parse(builder.build())

    private fun assertClean(r: HardeningReport) = assertNull("parse error: ${r.parseError}", r.parseError)

    @Test
    fun pie64WithEveryMitigation() {
        val r = parse(ElfBuilder())
        assertClean(r)
        assertEquals("arm64", r.arch)
        assertTrue(r.is64Bit)
        assertTrue(r.pie)
        assertTrue(r.nx)
        assertTrue(r.relro)
        assertTrue(r.bindNow)
        assertTrue(r.fullRelro)
        assertTrue(r.canary)
        assertTrue(r.fortify)
        assertTrue("no .symtab means stripped", r.stripped)
        assertFalse(r.weak)
        assertEquals("pie,nx,relro,bindnow,canary,fortify", r.flags)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun elf32WithExecutableStack() {
        val r = parse(ElfBuilder(is64 = false, machine = ElfBuilder.EM_ARM, gnuStack = ElfBuilder.PF_R or ElfBuilder.PF_W or ElfBuilder.PF_X))
        assertClean(r)
        assertEquals("arm", r.arch)
        assertFalse(r.is64Bit)
        assertTrue(r.pie)
        assertFalse("PF_X on PT_GNU_STACK", r.nx)
        assertTrue(r.weak)
        assertEquals("pie,!nx,relro,bindnow,canary,fortify", r.flags)
        assertEquals(listOf("NX stack"), r.missing)
    }

    @Test
    fun missingGnuStackMeansExecutable() {
        val r = parse(ElfBuilder(is64 = false, machine = ElfBuilder.EM_386, gnuStack = null))
        assertClean(r)
        assertEquals("x86", r.arch)
        assertFalse(r.nx)
    }

    @Test
    fun bindNowVariants() {
        val none = parse(ElfBuilder(bindNow = ElfBuilder.BindNow.NONE))
        assertClean(none)
        assertTrue(none.relro)
        assertFalse(none.bindNow)
        assertFalse(none.fullRelro)
        assertEquals("pie,nx,relro,!bindnow,canary,fortify", none.flags)
        assertEquals(listOf("BIND_NOW"), none.missing)
        for (variant in listOf(ElfBuilder.BindNow.DT_BIND_NOW, ElfBuilder.BindNow.FLAGS, ElfBuilder.BindNow.FLAGS_1)) {
            val r = parse(ElfBuilder(bindNow = variant))
            assertClean(r)
            assertTrue(variant.name, r.bindNow)
        }
        val noRelro = parse(ElfBuilder(relro = false))
        assertFalse(noRelro.relro)
        assertTrue(noRelro.bindNow)
        assertFalse(noRelro.fullRelro)
    }

    @Test
    fun pieFollowsFileType() {
        val exec = parse(ElfBuilder(type = ElfBuilder.ET_EXEC, interp = true))
        assertClean(exec)
        assertFalse(exec.pie)
        assertTrue(exec.weak)
        val pieExecutable = parse(ElfBuilder(type = ElfBuilder.ET_DYN, interp = true))
        assertClean(pieExecutable)
        assertTrue("ET_DYN with an interpreter is a PIE executable", pieExecutable.pie)
    }

    @Test
    fun architectures() {
        assertEquals("x86_64", parse(ElfBuilder(machine = ElfBuilder.EM_X86_64)).arch)
        assertEquals("riscv64", parse(ElfBuilder(machine = ElfBuilder.EM_RISCV)).arch)
        assertEquals("other", parse(ElfBuilder(machine = ElfBuilder.EM_PPC)).arch)
    }

    @Test
    fun symbolsOnlyCountWhenImported() {
        val defined = parse(ElfBuilder(imports = listOf("malloc", "__stack_chk_guard", "__chk"), defined = listOf("__stack_chk_fail", "__memcpy_chk")))
        assertClean(defined)
        assertFalse(defined.canary)
        assertFalse(defined.fortify)
        assertEquals("pie,nx,relro,bindnow,!canary,!fortify", defined.flags)
        val onlyCanary = parse(ElfBuilder(imports = listOf("__stack_chk_fail")))
        assertTrue(onlyCanary.canary)
        assertFalse(onlyCanary.fortify)
        val onlyFortify = parse(ElfBuilder(imports = listOf("__vsnprintf_chk")))
        assertFalse(onlyFortify.canary)
        assertTrue(onlyFortify.fortify)
        val empty = parse(ElfBuilder(imports = emptyList(), defined = emptyList()))
        assertClean(empty)
        assertFalse(empty.canary)
    }

    @Test
    fun strippedSectionHeadersFallBackToDynamicSection() {
        for (hash in ElfBuilder.Hash.entries) {
            val r = parse(ElfBuilder(sectionHeaders = false, hash = hash))
            assertClean(r)
            assertTrue(hash.name, r.stripped)
            assertTrue(hash.name, r.canary)
            assertTrue(hash.name, r.fortify)
            assertTrue(hash.name, r.bindNow)
        }
        val last = parse(ElfBuilder(sectionHeaders = false, hash = ElfBuilder.Hash.GNU, imports = listOf("malloc", "free", "__stack_chk_fail"), defined = emptyList()))
        assertTrue("the last symbol in a GNU hash chain is still read", last.canary)
    }

    @Test
    fun staticSymtabMeansNotStripped() {
        val r = parse(ElfBuilder(symtab = true))
        assertClean(r)
        assertFalse(r.stripped)
    }

    @Test
    fun bigEndianImages() {
        val arm = parse(ElfBuilder(is64 = false, bigEndian = true, machine = ElfBuilder.EM_ARM, bindNow = ElfBuilder.BindNow.FLAGS))
        assertClean(arm)
        assertEquals("arm", arm.arch)
        assertEquals("pie,nx,relro,bindnow,canary,fortify", arm.flags)
        val ppc64 = parse(ElfBuilder(is64 = true, bigEndian = true, machine = ElfBuilder.EM_PPC, sectionHeaders = false, hash = ElfBuilder.Hash.GNU))
        assertClean(ppc64)
        assertEquals("other", ppc64.arch)
        assertTrue(ppc64.is64Bit)
        assertTrue(ppc64.canary)
        assertTrue(ppc64.fortify)
    }

    @Test
    fun wrongMagic() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(100)
        assertEquals(ElfParser.ELF_MAGIC_ERROR, ElfParser.parse(zip).parseError)
        assertEquals(ElfParser.ELF_MAGIC_ERROR, ElfParser.parse(ByteArray(0)).parseError)
        assertEquals(ElfParser.ELF_MAGIC_ERROR, ElfParser.parse(byteArrayOf(0x7F, 'E'.code.toByte())).parseError)
        val badClass = ElfBuilder().build().also { it[4] = 9 }
        assertNotNull(ElfParser.parse(badClass).parseError)
    }

    @Test
    fun truncatedFilesReportAnErrorAndNeverThrow() {
        val full = ElfBuilder().build()
        // Cut inside the program headers: the loader's view is gone, so the report is an error.
        val cut = full.copyOf(64 + 56 + 10)
        val r = ElfParser.parse(cut)
        assertNotNull(r.parseError)
        assertTrue(r.parseError!!.contains("outside the file"))
        // Every prefix parses without throwing. Cuts inside the dynamic section are honest errors (a missing
        // DT_FLAGS would otherwise read as "no BIND_NOW"); cuts after it leave the report usable.
        var errors = 0
        var clean = 0
        for (len in 0 until full.size) {
            val p = ElfParser.parse(full.copyOf(len))
            if (p.parseError == null) clean++ else errors++
            if (len < 64 + 5 * 56) assertNotNull("prefix $len", p.parseError)
        }
        assertTrue(errors > 64 + 5 * 56)
        assertTrue(clean > 0)
        // Section headers cut off: treated as stripped, mitigations still read through the dynamic section.
        val noSections = full.copyOf(full.size - 64)
        val s = ElfParser.parse(noSections)
        assertNull(s.parseError)
        assertTrue(s.canary && s.fortify && s.bindNow)
    }

    @Test
    fun hostileHeadersStayBounded() {
        val random = Random(7)
        repeat(400) {
            val bytes = ElfBuilder(is64 = it % 2 == 0).build()
            // Scribble over a few header words, then parse: must return, never throw.
            repeat(4) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            ElfParser.parse(bytes)
        }
        val garbage = ByteArray(2000).also { random.nextBytes(it); it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte() }
        ElfParser.parse(garbage)
        val huge = ElfBuilder().build().also { it[56] = 0xFF.toByte(); it[57] = 0xFF.toByte() } // e_phnum = 65535
        assertTrue(ElfParser.parse(huge).parseError!!.contains("program headers"))
    }

    @Test
    fun flagsRoundTrip() {
        val reports = listOf(
            parse(ElfBuilder()),
            parse(ElfBuilder(is64 = false, machine = ElfBuilder.EM_ARM, gnuStack = null, bindNow = ElfBuilder.BindNow.NONE, imports = listOf("malloc"))),
            parse(ElfBuilder(type = ElfBuilder.ET_EXEC, relro = false)),
        )
        for (r in reports) {
            val back = HardeningReport.fromFlags(r.flags, r.arch, r.is64Bit)!!
            assertEquals(r.copy(stripped = true), back)
        }
        assertNull(HardeningReport.fromFlags("pie,nx"))
        assertNull(HardeningReport.fromFlags("pie,nx,relro,bindnow,canary,bogus"))
        assertNull(HardeningReport.fromFlags(HardeningKeys.UNPARSED_VALUE))
        assertNotNull(HardeningReport.failed("x").parseError)
    }
}
