package io.github.stronghorse44.tunnels.crossroads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.crossrules.CrossKeys
import io.github.stronghorse44.tunnels.crossrules.CrossRules
import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.SourceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Joins source data shaped like the real tunnels' on the device, and checks the actions against PackageManager. */
@RunWith(AndroidJUnit4::class)
class CrossroadsTunnelTest {
    @Test
    fun joinsDerivesAndOffersWorkingActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = CrossroadsTunnels().create(context).single() as DerivedTunnel
        assertEquals(CrossKeys.TUNNEL_ID, module.id)
        assertEquals("Crossroads", module.info.title)
        assertTrue(module.requiredPermissions.isEmpty())

        val now = Instant.now()
        // This test package is a real, removable app: sideloaded with an accessibility service on.
        val pkg = context.packageName
        val input = DerivedInput(
            sources = mapOf(
                "permissions" to SourceData(
                    listOf(
                        Observation("permissions", pkg, "app:system", "false"),
                        Observation("permissions", pkg, "access:accessibility", "enabled"),
                    ),
                    now,
                ),
                "apk_excavation" to SourceData(
                    listOf(
                        Observation("apk_excavation", pkg, "app:system", "false"),
                        Observation("apk_excavation", pkg, "installer", "com.android.packageinstaller"),
                    ),
                    now,
                ),
            ),
            findings = emptyList(),
            now = now,
        )
        val observations = module.derive(input)
        assertTrue(observations.all { it.tunnelId == module.id })
        val draft = module.rules.flatMap { it.evaluate(RuleContext(module.id, observations, emptyList(), isFirstScan = true)) }.single()
        assertEquals(CrossRules.SIDELOADED_ACCESSIBILITY, draft.kind)

        val actions = module.actionsFor(draft)
        assertTrue("accessibility settings first", actions.first() is FindingAction.OpenSettings)
        assertTrue(actions.any { it is FindingAction.OpenAppDetails })
        assertTrue("a removable app can be uninstalled", actions.any { it is FindingAction.RequestUninstall })

        // A system package never gets Uninstall.
        val systemActions = module.actionsFor(draft.copy(subject = "android"))
        assertTrue(systemActions.none { it is FindingAction.RequestUninstall })
    }
}

/** The per-app page is reachable by its action, and only offered for package-like subjects. */
@RunWith(AndroidJUnit4::class)
class AppPageTest {
    @Test
    fun theAppPageResolvesAndOpensForAnInstalledApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(io.github.stronghorse44.tunnels.runtime.AppPage.available(context))
        assertTrue(io.github.stronghorse44.tunnels.runtime.AppPage.isApp(context.packageName))
        assertTrue(!io.github.stronghorse44.tunnels.runtime.AppPage.isApp("uid:1000"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(AppActivity.intent(context, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        activity.finish()
    }
}
