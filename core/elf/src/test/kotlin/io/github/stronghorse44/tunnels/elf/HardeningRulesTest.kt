package io.github.stronghorse44.tunnels.elf

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningRulesTest {
    private val t = HardeningKeys.TUNNEL_ID

    private val full = "pie,nx,relro,bindnow,canary,fortify"
    private val noNx = "pie,!nx,relro,bindnow,canary,fortify"
    private val noPieNoNx = "!pie,!nx,relro,!bindnow,!canary,!fortify"
    private val lazy = "pie,nx,relro,!bindnow,canary,fortify"
    private val noCanary = "pie,nx,relro,bindnow,!canary,!fortify"

    /** Observations the tunnel would emit for one app, counts derived from the lib flags like the scanner does. */
    private fun app(
        pkg: String,
        system: Boolean = false,
        libs: Map<String, String> = emptyMap(),
        abis: String? = null,
        truncated: Boolean = false,
        weakOverride: Int? = null,
    ): List<Observation> = buildList {
        add(Observation(t, pkg, HardeningKeys.LABEL, pkg.substringAfterLast('.')))
        add(Observation(t, pkg, HardeningKeys.SYSTEM, system.toString()))
        val reports = libs.mapNotNull { (path, flags) -> HardeningReport.fromFlags(flags)?.let { path to it } }
        val abiList = abis ?: libs.keys.map { it.substringBefore('/') }.toSortedSet().joinToString(",").ifEmpty { HardeningKeys.NONE }
        add(Observation(t, pkg, HardeningKeys.LIBS, libs.size.toString()))
        add(Observation(t, pkg, HardeningKeys.ABIS, abiList))
        val bits = when {
            abiList == HardeningKeys.NONE -> HardeningKeys.NONE
            abiList.split(',').any { it in NativeLibs.ABIS_64 } -> "true"
            else -> "false"
        }
        add(Observation(t, pkg, HardeningKeys.BITS_64, bits))
        if (libs.isNotEmpty()) {
            add(Observation(t, pkg, HardeningKeys.WEAK, (weakOverride ?: reports.count { it.second.weak }).toString()))
            add(Observation(t, pkg, HardeningKeys.PARTIAL_RELRO, reports.count { !it.second.bindNow }.toString()))
            add(Observation(t, pkg, HardeningKeys.NO_CANARY, reports.count { !it.second.canary }.toString()))
        }
        if (truncated) add(Observation(t, pkg, HardeningKeys.TRUNCATED, "true"))
        for ((path, flags) in libs) add(Observation(t, pkg, HardeningKeys.LIB_PREFIX + path, flags))
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return HardeningRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun weakHardeningNamesLibsAndMissingMitigations() {
        val weak = app("com.weak", libs = mapOf(
            "arm64-v8a/liba.so" to full,
            "arm64-v8a/libb.so" to noNx,
            "arm64-v8a/libc.so" to noPieNoNx,
            "arm64-v8a/libd.so" to noNx,
            "arm64-v8a/libe.so" to noNx,
            "arm64-v8a/libjunk.so" to HardeningKeys.UNPARSED_VALUE,
        ))
        val fine = app("com.fine", libs = mapOf("arm64-v8a/liba.so" to full, "arm64-v8a/libb.so" to lazy))
        val none = app("com.none")
        val drafts = evaluate(weak + fine + none).of(HardeningRules.WEAK_HARDENING)
        assertEquals(listOf("com.weak"), drafts.map { it.subject })
        val d = drafts.single()
        assertEquals(Severity.WARN, d.severity)
        assertFalse(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("4 of 5 native libraries lack basic exploit mitigations: libb.so (no NX stack), libc.so (no PIE, no NX stack), libd.so (no NX stack) and 1 more."))
    }

    @Test
    fun partialRelroAndNoCanaryAreInformational() {
        val a = app("com.a", libs = mapOf("arm64-v8a/liba.so" to lazy, "arm64-v8a/libb.so" to full, "arm64-v8a/libc.so" to noCanary))
        val drafts = evaluate(a)
        val relro = drafts.of(HardeningRules.PARTIAL_RELRO).single()
        assertEquals(Severity.NOTICE, relro.severity)
        assertTrue(relro.evidence, relro.evidence.startsWith("1 of 3 native libraries use lazy binding (partial RELRO): liba.so."))
        val canary = drafts.of(HardeningRules.NO_CANARY).single()
        assertEquals(Severity.INFO, canary.severity)
        assertTrue(canary.evidence, canary.evidence.startsWith("1 of 3 native libraries have no stack canary: libc.so."))
        assertTrue(drafts.of(HardeningRules.WEAK_HARDENING).isEmpty())
        assertTrue(evaluate(app("com.b", libs = mapOf("arm64-v8a/x.so" to full))).isEmpty())
    }

    @Test
    fun legacy32BitOnlyWhenNo64BitAbiShips() {
        val old = app("com.old", libs = mapOf("armeabi-v7a/libold.so" to full))
        val both = app("com.both", libs = mapOf("armeabi-v7a/lib.so" to full, "arm64-v8a/lib.so" to full))
        val none = app("com.none")
        val drafts = evaluate(old + both + none).of(HardeningRules.LEGACY_32BIT)
        assertEquals(listOf("com.old"), drafts.map { it.subject })
        assertEquals(Severity.WARN, drafts.single().severity)
        assertTrue(drafts.single().evidence.contains("armeabi-v7a"))
        assertTrue(drafts.single().evidence.contains("Pixel 10"))
    }

    @Test
    fun regressionIsStickyAndOnlyOnIncrease() {
        val before = app("com.a", libs = mapOf("arm64-v8a/liba.so" to full, "arm64-v8a/libb.so" to full))
        val worse = app("com.a", libs = mapOf("arm64-v8a/liba.so" to noNx, "arm64-v8a/libb.so" to full))
        val d = evaluate(worse, before).of(HardeningRules.HARDENING_REGRESSED).single()
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.sticky)
        assertEquals("An update raised the number of native libraries lacking basic mitigations from 0 to 1.", d.evidence)
        assertTrue("first scan never regresses", evaluate(worse).of(HardeningRules.HARDENING_REGRESSED).isEmpty())
        assertTrue("same state", evaluate(worse, worse).of(HardeningRules.HARDENING_REGRESSED).isEmpty())
        assertTrue("improvement", evaluate(before, worse).of(HardeningRules.HARDENING_REGRESSED).isEmpty())
        val fresh = app("com.new", libs = mapOf("arm64-v8a/x.so" to noNx))
        assertTrue("new app has no before", evaluate(worse + fresh, worse).of(HardeningRules.HARDENING_REGRESSED).isEmpty())
    }

    @Test
    fun keysAndStats() {
        assertEquals("lib:arm64-v8a/libfoo.so", HardeningKeys.libKey("arm64-v8a", "libfoo.so"))
        assertEquals("libfoo.so", HardeningKeys.libName("lib:arm64-v8a/libfoo.so"))
        assertEquals("arm64-v8a", HardeningKeys.libAbi("lib:arm64-v8a/libfoo.so"))
        assertEquals("", HardeningKeys.libAbi("lib:libfoo.so"))
        assertTrue(HardeningKeys.isLibKey("lib:x/y.so") && !HardeningKeys.isLibKey(HardeningKeys.LIBS))
        assertEquals("a, b, c and 2 more", HardeningKeys.nameList(listOf("a", "b", "c", "d", "e")))
        assertEquals("a, b", HardeningKeys.nameList(listOf("a", "b")))

        val obs = app("com.bad", libs = mapOf("arm64-v8a/a.so" to noPieNoNx, "arm64-v8a/b.so" to noNx, "arm64-v8a/junk.so" to HardeningKeys.UNPARSED_VALUE)) +
            app("com.old", system = true, libs = mapOf("armeabi-v7a/a.so" to full)) +
            app("com.lazy", libs = mapOf("arm64-v8a/a.so" to lazy)) +
            app("com.fine", libs = mapOf("arm64-v8a/a.so" to full)) +
            app("com.none")
        val stats = HardeningStats.from(obs)
        assertEquals(5, stats.apps)
        assertEquals(4, stats.appsWithNative)
        assertEquals(6, stats.libsSeen)
        assertEquals(5, stats.libsParsed)
        assertEquals(1, stats.weakApps)
        assertEquals(2, stats.weakLibs)
        assertEquals(1, stats.legacy32Apps)
        assertEquals(listOf("com.bad", "com.old", "com.lazy"), stats.weakest.map { it.packageName })
        val bad = stats.weakest.first()
        assertEquals("bad", bad.label)
        assertEquals(2, bad.weak)
        assertEquals(2, bad.parsed)
        assertTrue(stats.weakest[1].system && stats.weakest[1].legacy32)
        assertEquals(HardeningStats.EMPTY, HardeningStats.from(emptyList()))
        assertEquals(2, HardeningStats.from(obs, top = 2).weakest.size)

        val libs = HardeningKeys.libs(obs.filter { it.subject == "com.bad" })
        assertEquals(2, libs.size)
        assertTrue(libs.all { it.second.is64Bit })
    }
}
