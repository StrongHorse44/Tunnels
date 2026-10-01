package io.github.stronghorse44.tunnels.notifications

import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.notifrules.NotifKeys
import io.github.stronghorse44.tunnels.notifrules.NotifRecord
import io.github.stronghorse44.tunnels.notifrules.NotifRules
import io.github.stronghorse44.tunnels.runtime.RestrictedSettings
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the tunnel on a stock emulator: no Notification access granted, nothing recorded by the listener yet. */
@RunWith(AndroidJUnit4::class)
class NotificationsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyPattern = Regex("app:label|notif:[a-zA-Z0-9]+|listener:(connected|dropped)|access:granted|apps:[a-zA-Z0-9]+|lockscreen:showsNotifications|events:truncated")

    @Test
    fun scansWithoutAccess() = runBlocking {
        val module = NotificationsTunnels().create(context).single()
        assertEquals(NotifKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertEquals(NotifRules.all.size, module.rules.size)
        assertTrue("the tunnel draws its own summary panel", module is TunnelUi && module.showObservations)
        val access = module.specialAccess.single()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, access.settingsAction)
        assertTrue("Android restricts notification access for apps installed from a file", access.restricted)
        val screen = RestrictedSettings.settingsIntent(context, access)
        assertEquals("the listener's own switch", Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, screen.action)
        assertNotNull(screen.resolveActivity(context.packageManager))
        assertNotNull(RestrictedSettings.appInfoIntent(context).resolveActivity(context.packageManager))
        assertFalse("a stock emulator has not granted notification access", access.isGranted())
        assertFalse(NotifListenerService.connected)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.all { it.tunnelId == NotifKeys.TUNNEL_ID })
        assertTrue(obs.none { it.value.isEmpty() })
        assertTrue(obs.map { it.key }.filterNot(keyPattern::matches).isEmpty())
        obs.groupBy { it.subject }.forEach { (s, list) -> assertEquals(s, list.size, list.map { it.key }.toSet().size) }

        val summary = obs.filter { it.subject == NotifKeys.SUMMARY }
        assertEquals("false", NotifKeys.value(summary, NotifKeys.ACCESS_GRANTED))
        assertEquals("false", NotifKeys.value(summary, NotifKeys.LISTENER_CONNECTED))
        assertNotNull(NotifKeys.value(summary, NotifKeys.TOTAL_7)?.toIntOrNull())
        assertNotNull(NotifKeys.value(summary, NotifKeys.APPS_NOISY)?.toIntOrNull())
        assertTrue("the lock screen setting is readable without a permission", NotifKeys.value(summary, NotifKeys.LOCKSCREEN_SHOWS) in setOf("true", "false"))
        assertNull("the listener never ran, so nothing was dropped", NotifKeys.value(summary, NotifKeys.LISTENER_DROPPED))
        assertNull(NotifKeys.value(summary, NotifKeys.EVENTS_TRUNCATED))
        assertEquals(0, NotifListenerService.dropped)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 60_000L, 60_000L), listOf(1, 2, 3, 6, 40).map { NotifListenerService.backoffMs(it) })

        // Without access there is no LISTENER_DISCONNECTED (or LISTENER_DROPPED) finding to nag about.
        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), isFirstScan = true)) }
        assertTrue(drafts.none { it.subject == NotifKeys.SUMMARY })

        // Every finding kind has an action, the per-app ones lead with the app's notification settings.
        for (kind in listOf(NotifRules.NOISY_APP, NotifRules.NIGHT_NOISE, NotifRules.SPOOFED_URGENCY, NotifRules.LOCK_SCREEN_EXPOSURE)) {
            val actions = module.actionsFor(FindingDraft(module.id, "com.example.app", kind, Severity.NOTICE, "x"))
            assertTrue(kind, actions.first() is FindingAction.Perform && actions.first().label == "Notification settings")
            assertTrue(kind, actions.any { it is FindingAction.OpenAppDetails })
        }
        val exposure = module.actionsFor(FindingDraft(module.id, "com.example.app", NotifRules.LOCK_SCREEN_EXPOSURE, Severity.INFO, "x"))
        assertTrue(exposure.any { it is FindingAction.OpenSettings && it.action == NotificationsTunnel.ACTION_NOTIFICATION_SETTINGS })
        for (kind in listOf(NotifRules.LISTENER_DISCONNECTED, NotifRules.LISTENER_DROPPED)) {
            val listener = module.actionsFor(FindingDraft(module.id, NotifKeys.SUMMARY, kind, Severity.INFO, "x"))
            assertTrue(kind, listener.any { it is FindingAction.OpenSettings && it.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS })
        }

        // The listener is declared so the system can find it, and only the system may bind it.
        val info = context.packageManager.getServiceInfo(NotifListenerService.component(context), PackageManager.ComponentInfoFlags.of(0))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission)
        assertTrue(info.exported)
    }

    @Test
    fun recordedEventsComeBackAsCounts() = runBlocking {
        val module = NotificationsTunnels().create(context).single()
        val store = TunnelsStore.get(context)
        val pkg = "io.github.stronghorse44.tunnels.synthetic.chat"
        val record = NotifRecord.of(importance = 4, visibility = 1, category = "msg", ongoing = false, silent = false, hour = 23)
        repeat(3) { store.recordEvent(NotifKeys.TUNNEL_ID, NotifKeys.EVENT_POSTED, pkg, record.encode()) }
        store.recordEvent(NotifKeys.TUNNEL_ID, "OTHER", pkg, "ignored")

        val obs = module.scan(ScanProgress.NONE)
        val mine = obs.filter { it.subject == pkg }
        assertEquals(10, mine.size)
        assertEquals("the package is not installed, so its name stands in", pkg, NotifKeys.value(mine, NotifKeys.LABEL))
        // >= because the device keeps events for 30 days across test runs.
        assertTrue(NotifKeys.int(mine, NotifKeys.COUNT_7) >= 3)
        assertTrue(NotifKeys.int(mine, NotifKeys.LOCK_PUBLIC_7) >= 3)
        assertTrue(NotifKeys.int(mine, NotifKeys.URGENT_7) >= 3)
        assertTrue(NotifKeys.int(mine, NotifKeys.NIGHT_7) >= 3)
        assertTrue("msg" in NotifKeys.categoriesOf(NotifKeys.value(mine, NotifKeys.CATEGORIES)))
        assertTrue(NotifKeys.int(obs.filter { it.subject == NotifKeys.SUMMARY }, NotifKeys.TOTAL_7) >= 3)

        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), isFirstScan = true)) }
        assertTrue(drafts.any { it.subject == pkg && it.kind == NotifRules.LOCK_SCREEN_EXPOSURE })
    }
}
