package io.github.stronghorse44.tunnels.apk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import io.github.stronghorse44.tunnels.trackers.ApkRules
import io.github.stronghorse44.tunnels.trackers.ExportedCounts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the real package list of the emulator through the provider, as the engine would. */
@RunWith(AndroidJUnit4::class)
class ApkExcavationSmokeTest {
    private val keyPattern = Regex(
        "app:label|app:system|version|sdk:[a-z0-9_]+|cert:sha256|cert:count|cert:lineage|cert:history|installer|targetSdk|minSdk|native:abis|native:libs|size:mb|scan:error|app:debuggable|net:cleartext|exported:open|exported:providers",
    )

    @Test
    fun scansInstalledApps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = ApkTunnels().create(context).single()
        assertEquals(ApkKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertEquals(ApkRules.all.size, module.rules.size)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("some apps were scanned", obs.isNotEmpty())
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.all { it.tunnelId == ApkKeys.TUNNEL_ID })
        assertFalse("this app is excluded", obs.any { it.subject == context.packageName })
        assertTrue(obs.map { it.key }.filterNot(keyPattern::matches).isEmpty())
        assertTrue(obs.none { it.value.isEmpty() })

        val bySubject = obs.groupBy { it.subject }
        val errors = obs.filter { it.key == ApkExcavationTunnel.KEY_ERROR }
        assertTrue("no per-app failures: $errors", errors.isEmpty())
        for ((pkg, list) in bySubject) {
            val keys = list.map { it.key }
            assertEquals(pkg, keys.size, keys.toSet().size)
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.SDK_COUNT)!!.toInt() >= 0)
            assertEquals(pkg, keys.count(ApkKeys::isTrackerKey), ApkKeys.value(list, ApkKeys.SDK_COUNT)!!.toInt())
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.SYSTEM) in setOf("true", "false"))
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.TARGET_SDK)!!.toInt() >= 0)
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.SIZE_MB)!!.toInt() >= 0)
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.CLEARTEXT) in setOf("true", "false"))
            assertTrue(pkg, ApkKeys.value(list, ApkKeys.DEBUGGABLE) in setOf(null, "true"))
            ApkKeys.value(list, ApkKeys.EXPORTED_OPEN)?.let { assertTrue("$pkg exported $it", ExportedCounts.parse(it) != null) }
            if (ApkKeys.value(list, ApkKeys.SYSTEM) == "true") assertTrue("$pkg: system apps skip exported", ApkKeys.EXPORTED_OPEN !in keys)
            val cert = ApkKeys.value(list, ApkKeys.CERT_SHA256)!!
            assertTrue("$pkg cert $cert", cert == "none" || Regex("[0-9A-F]{64}").matches(cert))
        }

        val framework = bySubject["android"] ?: error("the android package should always be visible")
        assertEquals("true", ApkKeys.value(framework, ApkKeys.SYSTEM))
        assertTrue(Regex("[0-9A-F]{64}").matches(ApkKeys.value(framework, ApkKeys.CERT_SHA256)!!))

        // Second scan hits the cache and must describe the same world.
        val again = module.scan(ScanProgress.NONE)
        assertEquals(obs.sortedWith(compareBy({ it.subject }, { it.key })), again.sortedWith(compareBy({ it.subject }, { it.key })))

        val draft = FindingDraft(module.id, "android", ApkRules.TRACKER_SDK, Severity.NOTICE, "x")
        val actions = module.actionsFor(draft)
        assertTrue(actions.first() is FindingAction.OpenAppDetails)
        assertTrue("system apps get no uninstall", actions.none { it is FindingAction.RequestUninstall })
        val userDraft = FindingDraft(module.id, context.packageName, ApkRules.TRACKER_SDK, Severity.NOTICE, "x")
        assertTrue(module.actionsFor(userDraft).any { it is FindingAction.RequestUninstall })
    }
}
