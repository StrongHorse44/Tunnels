package io.github.stronghorse44.tunnels.hardening

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.elf.HardeningKeys
import io.github.stronghorse44.tunnels.elf.HardeningReport
import io.github.stronghorse44.tunnels.elf.HardeningRules
import io.github.stronghorse44.tunnels.elf.HardeningStats
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the real package list of the emulator through the provider, as the engine would. */
@RunWith(AndroidJUnit4::class)
class HardeningSmokeTest {
    private val keyPattern = Regex(
        "app:label|app:system|hardening:(libs|abis|64bit|weak|partialRelro|noCanary|truncated|skipped|unparsed)|lib:[^/]+/[^/]+\\.so|scan:error",
    )
    private val flagsPattern = Regex("!?pie,!?nx,!?relro,!?bindnow,!?canary,!?fortify|${HardeningKeys.UNPARSED_VALUE}")

    @Test
    fun scansInstalledApps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = HardeningTunnels().create(context).single()
        assertEquals(HardeningKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertEquals(HardeningRules.all.size, module.rules.size)

        val pm = context.packageManager
        fun stamps() = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).associate { it.packageName to it.lastUpdateTime }
        val before = stamps()
        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("some apps were scanned", obs.isNotEmpty())
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.all { it.tunnelId == HardeningKeys.TUNNEL_ID })
        assertTrue(obs.map { it.key }.filterNot(keyPattern::matches).isEmpty())
        assertTrue(obs.none { it.value.isEmpty() })

        val bySubject = obs.groupBy { it.subject }
        val errors = obs.filter { it.key == HardeningKeys.ERROR }
        assertTrue("no per-app failures: $errors", errors.isEmpty())
        assertNotNull("this app is scanned too", bySubject[context.packageName])
        assertNotNull("the android package should always be visible", bySubject["android"])
        assertEquals("true", HardeningKeys.value(bySubject.getValue("android"), HardeningKeys.SYSTEM))

        var libsSeen = 0
        for ((pkg, list) in bySubject) {
            val keys = list.map { it.key }
            assertEquals(pkg, keys.size, keys.toSet().size)
            assertTrue(pkg, HardeningKeys.value(list, HardeningKeys.SYSTEM) in setOf("true", "false"))
            val libs = HardeningKeys.count(list, HardeningKeys.LIBS)
            assertTrue(pkg, libs >= 0)
            libsSeen += libs
            val abis = HardeningKeys.value(list, HardeningKeys.ABIS)!!
            val bits = HardeningKeys.value(list, HardeningKeys.BITS_64)!!
            if (libs == 0) {
                assertEquals(pkg, HardeningKeys.NONE, abis)
                assertEquals(pkg, HardeningKeys.NONE, bits)
            } else {
                assertTrue(pkg, bits in setOf("true", "false"))
                assertTrue(pkg, abis != HardeningKeys.NONE)
            }
            val libObs = list.filter { HardeningKeys.isLibKey(it.key) }
            assertTrue("$pkg lib entries within cap", libObs.size <= io.github.stronghorse44.tunnels.elf.NativeLibs.MAX_LIBS)
            assertTrue("$pkg lib flags well formed", libObs.all { flagsPattern.matches(it.value) })
            val parsed = libObs.mapNotNull { HardeningReport.fromFlags(it.value) }
            if (libObs.isNotEmpty()) {
                assertEquals("$pkg weak count", parsed.count { it.weak }, HardeningKeys.count(list, HardeningKeys.WEAK))
                assertEquals("$pkg lazy count", parsed.count { !it.bindNow }, HardeningKeys.count(list, HardeningKeys.PARTIAL_RELRO))
                assertEquals("$pkg canary count", parsed.count { !it.canary }, HardeningKeys.count(list, HardeningKeys.NO_CANARY))
                assertEquals("$pkg unparsed count", libObs.size - parsed.size, HardeningKeys.count(list, HardeningKeys.UNPARSED))
            }
            if (HardeningKeys.value(list, HardeningKeys.TRUNCATED) != null) {
                assertTrue(pkg, libs > io.github.stronghorse44.tunnels.elf.NativeLibs.MAX_LIBS)
            }
        }
        // A stock image ships native code in the framework, WebView or Google apps; at least one lib must parse cleanly.
        assertTrue("some native libraries exist on the image", libsSeen > 0)
        val cleanLibs = obs.filter { HardeningKeys.isLibKey(it.key) && HardeningReport.fromFlags(it.value) != null }
        assertTrue("some library parsed as ELF", cleanLibs.isNotEmpty())

        val stats = HardeningStats.from(obs)
        assertEquals(bySubject.size, stats.apps)
        assertEquals(cleanLibs.size, stats.libsParsed)
        assertTrue(stats.weakest.size <= 3)

        // Second scan hits the cache and must describe the same world, for every app that did not change in
        // between (Play apps on a google_apis image may self-update while the test runs).
        val again = module.scan(ScanProgress.NONE)
        val after = stamps()
        val stable = before.filterKeys { pkg -> after[pkg] == before[pkg] }.keys
        assertTrue("most apps unchanged between scans", stable.size > bySubject.size / 2)
        assertTrue("this app is unchanged", context.packageName in stable)
        val againBySubject = again.groupBy { it.subject }
        val byKey = compareBy<Observation> { it.key }
        for (pkg in stable) {
            val first = bySubject[pkg] ?: continue
            assertEquals(pkg, first.sortedWith(byKey), againBySubject[pkg]?.sortedWith(byKey))
        }

        val draft = FindingDraft(module.id, "android", HardeningRules.WEAK_HARDENING, Severity.WARN, "x")
        val actions = module.actionsFor(draft)
        assertTrue(actions.first() is FindingAction.OpenAppDetails)
        assertTrue("system apps get no uninstall", actions.none { it is FindingAction.RequestUninstall })
        val userDraft = FindingDraft(module.id, context.packageName, HardeningRules.WEAK_HARDENING, Severity.WARN, "x")
        assertTrue(module.actionsFor(userDraft).any { it is FindingAction.RequestUninstall })
    }
}
