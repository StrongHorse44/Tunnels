package io.github.stronghorse44.tunnels.syspackages

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.syspkg.SysPkgKeys
import io.github.stronghorse44.tunnels.syspkg.SysPkgRules
import io.github.stronghorse44.tunnels.syspkg.SysPkgStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the real system packages of the emulator through the provider, as the engine would. */
@RunWith(AndroidJUnit4::class)
class SystemPackagesSmokeTest {
    private val packageKeys = setOf(
        SysPkgKeys.LABEL, SysPkgKeys.ENABLED, SysPkgKeys.UPDATED, SysPkgKeys.VERSION, SysPkgKeys.KNOWN, SysPkgKeys.CATEGORY,
        SysPkgKeys.NAMESPACE, SysPkgKeys.PRIVILEGED, SysPkgKeys.HAS_LAUNCHER, SysPkgKeys.PURPOSE, SysPkgKeys.DISABLE_RISK,
    )
    private val requiredPackageKeys = packageKeys - SysPkgKeys.PURPOSE - SysPkgKeys.DISABLE_RISK
    private val summaryKeys = setOf(SysPkgKeys.TOTAL, SysPkgKeys.DISABLED, SysPkgKeys.UNKNOWN, SysPkgKeys.SKIPPED)
    private val enabledValues = setOf(
        SysPkgKeys.ENABLED_VALUE, SysPkgKeys.DISABLED_VALUE, SysPkgKeys.DISABLED_USER_VALUE,
        SysPkgKeys.DISABLED_UNTIL_USED_VALUE, SysPkgKeys.UNINSTALLED_USER_VALUE,
    )

    @Test
    fun scansSystemPackages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = SysPackagesTunnels().create(context).single()
        assertEquals(SysPkgKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertEquals(SysPkgRules.all.size, module.rules.size)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.all { it.tunnelId == SysPkgKeys.TUNNEL_ID })
        assertTrue(obs.none { it.value.isEmpty() })
        val errors = obs.filter { it.key == SystemPackagesTunnel.KEY_ERROR }
        assertTrue("no per-package failures: $errors", errors.isEmpty())

        val bySubject = obs.groupBy { it.subject }
        val summary = bySubject[SysPkgKeys.SUMMARY] ?: error("summary subject missing")
        assertTrue(summary.all { it.key in summaryKeys })
        val packages = bySubject - SysPkgKeys.SUMMARY
        assertTrue("some system packages", packages.isNotEmpty())
        assertFalse("this app is not a system package", context.packageName in packages)
        assertEquals(packages.size.toString(), SysPkgKeys.value(summary, SysPkgKeys.TOTAL))

        var disabled = 0
        var unknown = 0
        for ((pkg, list) in packages) {
            val keys = list.map { it.key }
            assertEquals("$pkg has duplicate keys", keys.size, keys.toSet().size)
            assertTrue("$pkg has foreign keys $keys", keys.all { it in packageKeys })
            assertTrue("$pkg misses keys $keys", keys.containsAll(requiredPackageKeys))
            val enabled = SysPkgKeys.value(list, SysPkgKeys.ENABLED)
            assertTrue("$pkg enabled=$enabled", enabled in enabledValues)
            if (SysPkgKeys.isDisabled(enabled)) disabled++
            val known = SysPkgKeys.isKnown(list)
            if (!known) unknown++
            assertEquals("$pkg purpose present iff known", known, SysPkgKeys.PURPOSE in keys)
            assertEquals("$pkg risk present iff known", known, SysPkgKeys.DISABLE_RISK in keys)
            assertTrue(pkg, SysPkgKeys.value(list, SysPkgKeys.NAMESPACE) in setOf("aosp", "grapheneos", "google", "other"))
            assertTrue(pkg, SysPkgKeys.value(list, SysPkgKeys.PRIVILEGED) in setOf("true", "false"))
            assertTrue(pkg, SysPkgKeys.value(list, SysPkgKeys.HAS_LAUNCHER) in setOf("true", "false"))
            assertTrue(pkg, Regex(".+ \\(-?\\d+\\)").matches(SysPkgKeys.value(list, SysPkgKeys.VERSION)!!))
            if (pkg.startsWith("com.android.")) assertEquals(pkg, "aosp", SysPkgKeys.value(list, SysPkgKeys.NAMESPACE))
        }
        assertEquals(disabled.toString(), SysPkgKeys.value(summary, SysPkgKeys.DISABLED))
        assertEquals(unknown.toString(), SysPkgKeys.value(summary, SysPkgKeys.UNKNOWN))

        val framework = packages["android"] ?: error("the android package should always be a system package")
        assertEquals("true", SysPkgKeys.value(framework, SysPkgKeys.KNOWN))
        assertEquals("enabled", SysPkgKeys.value(framework, SysPkgKeys.ENABLED))
        val settings = packages["com.android.settings"] ?: error("Settings should always be present")
        assertEquals("true", SysPkgKeys.value(settings, SysPkgKeys.KNOWN))
        assertEquals("ui", SysPkgKeys.value(settings, SysPkgKeys.CATEGORY))
        assertEquals("true", SysPkgKeys.value(settings, SysPkgKeys.PRIVILEGED))
        assertEquals("true", SysPkgKeys.value(settings, SysPkgKeys.HAS_LAUNCHER))
        assertNotNull(SysPkgKeys.value(settings, SysPkgKeys.PURPOSE))

        val stats = SysPkgStats.from(obs)
        assertEquals(packages.size, stats.total)
        assertEquals(disabled, stats.disabled.size)
        assertEquals(unknown, stats.unknown.size)

        // A second scan must describe the same world.
        val again = module.scan(ScanProgress.NONE)
        assertEquals(obs.sortedWith(compareBy({ it.subject }, { it.key })), again.sortedWith(compareBy({ it.subject }, { it.key })))

        val knownDraft = FindingDraft(module.id, "com.android.settings", SysPkgRules.ENABLED_STATE_CHANGED, Severity.NOTICE, "x", sticky = true)
        val knownActions = module.actionsFor(knownDraft)
        assertTrue(knownActions.first() is FindingAction.OpenAppDetails)
        val whatIsThis = knownActions.filterIsInstance<FindingAction.Perform>().single()
        assertEquals("What is this?", whatIsThis.label)
        assertTrue(whatIsThis.run().contains("Settings app"))

        val unknownDraft = FindingDraft(module.id, "com.vendor.unknown", SysPkgRules.UNKNOWN_SYSTEM_PACKAGE, Severity.INFO, "x")
        assertEquals(listOf<FindingAction>(FindingAction.OpenAppDetails("com.vendor.unknown")), module.actionsFor(unknownDraft))

        val summaryDraft = FindingDraft(module.id, SysPkgKeys.SUMMARY, SysPkgRules.OS_UPDATE_DETECTED, Severity.INFO, "x", sticky = true)
        assertTrue(module.actionsFor(summaryDraft).single() is FindingAction.OpenSettings)
    }
}
