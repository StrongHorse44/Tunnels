package io.github.stronghorse44.tunnels.timeline

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the tunnel through its provider on a stock emulator. Usage access is not granted there, so the
 * scan must come back with the summary subject alone rather than throw; when it is granted (a developer
 * toggled it by hand) every per-app key must follow the schema.
 */
@RunWith(AndroidJUnit4::class)
class TimelineSmokeTest {
    private val appKeys = setOf(
        TimelineKeys.LABEL, TimelineKeys.SYSTEM, TimelineKeys.FIRST_INSTALL,
        TimelineKeys.FG_MINUTES_7, TimelineKeys.FG_MINUTES_30, TimelineKeys.DAYS_USED_30, TimelineKeys.LAUNCHES_7, TimelineKeys.LAST_USED,
        TimelineKeys.WIFI_MB_30, TimelineKeys.MOBILE_MB_30, TimelineKeys.FG_MB_30, TimelineKeys.BG_MB_30,
        TimelineTunnel.KEY_ERROR,
    )
    private val summaryKeys = setOf(
        TimelineKeys.ACCESS_USAGE, TimelineKeys.APPS_TOTAL, TimelineKeys.APPS_UNUSED_60, TimelineKeys.NET_TOTAL_MB_30, TimelineKeys.NET_AVAILABLE,
    )

    @Test
    fun scansWithoutThrowingWhateverTheAccessState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = TimelineTunnels().create(context).single() as TimelineTunnel
        assertEquals(TimelineKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        val access = module.specialAccess.single()
        assertEquals("Usage access", access.label)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, access.settingsAction)
        assertEquals(TimelineRules.all().size, module.rules.size)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports >= 1)
        assertTrue(obs.all { it.tunnelId == TimelineKeys.TUNNEL_ID })
        assertTrue(obs.none { it.value.isEmpty() })
        assertFalse("this app is excluded", obs.any { it.subject == context.packageName })

        val summary = obs.filter { it.subject == TimelineKeys.SUMMARY }
        assertTrue(summary.isNotEmpty())
        assertTrue(summary.map { it.key }.all { it in summaryKeys })
        assertTrue(TimelineKeys.longValue(summary, TimelineKeys.APPS_TOTAL)!! >= 1)
        val granted = access.isGranted()
        assertEquals(granted, TimelineKeys.value(summary, TimelineKeys.ACCESS_USAGE) == TimelineKeys.GRANTED)

        val apps = obs.filter { it.subject != TimelineKeys.SUMMARY }.groupBy { it.subject }
        if (!granted) {
            assertTrue("no per-app data without usage access", apps.isEmpty())
            assertEquals(summary.size, obs.size)
            // Without access the summary is deterministic, so a second scan must describe the same world.
            assertEquals(obs.toSet(), module.scan(ScanProgress.NONE).toSet())
        } else {
            assertEquals(TimelineKeys.longValue(summary, TimelineKeys.APPS_TOTAL)!!.toInt(), apps.size)
            assertTrue(apps.containsKey("android"))
            for ((pkg, list) in apps) {
                val keys = list.map { it.key }
                assertEquals(pkg, keys.size, keys.toSet().size)
                assertTrue("$pkg $keys", keys.all { it in appKeys })
                assertTrue(pkg, TimelineKeys.value(list, TimelineKeys.SYSTEM) in setOf("true", "false"))
                assertTrue(pkg, TimelineKeys.longValue(list, TimelineKeys.FG_MINUTES_30)!! >= 0)
                val last = TimelineKeys.value(list, TimelineKeys.LAST_USED)!!
                assertTrue("$pkg $last", last == TimelineKeys.NEVER || Regex("\\d{4}-\\d{2}-\\d{2}").matches(last))
            }
        }

        val draft = FindingDraft(module.id, "android", TimelineRules.UNUSED_APP, Severity.INFO, "x")
        val actions = module.actionsFor(draft)
        assertTrue(actions.first() is FindingAction.OpenAppDetails)
        assertTrue("system apps get no uninstall", actions.none { it is FindingAction.RequestUninstall })
        assertTrue("usage findings get no data-usage link", actions.none { it is FindingAction.OpenSettings })
        val dataDraft = FindingDraft(module.id, context.packageName, TimelineRules.HEAVY_BACKGROUND_DATA, Severity.NOTICE, "x")
        val dataActions = module.actionsFor(dataDraft)
        assertTrue(dataActions.any { it is FindingAction.RequestUninstall })
        assertEquals(Settings.ACTION_DATA_USAGE_SETTINGS, (dataActions.last() as FindingAction.OpenSettings).action)
        assertTrue(module.actionsFor(FindingDraft(module.id, TimelineKeys.SUMMARY, TimelineRules.UNUSED_APP, Severity.INFO, "x")).isEmpty())
    }
}
