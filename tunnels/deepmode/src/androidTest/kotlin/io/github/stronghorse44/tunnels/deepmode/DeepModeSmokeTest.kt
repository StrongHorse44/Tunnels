package io.github.stronghorse44.tunnels.deepmode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.posture.PostureKeys
import io.github.stronghorse44.tunnels.posture.PostureRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The emulator has no Shizuku: the scan must report deep mode as off with a reason, never throw, and
 * never try to bind a user service. Findings derived from that scan must carry actions only when the
 * Shizuku app is installed.
 */
@RunWith(AndroidJUnit4::class)
class DeepModeSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportsUnavailableWithoutShizuku() = runBlocking {
        val module = DeepModeTunnels().create(context).single() as DeepModeTunnel
        assertEquals(DeepKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertEquals(1, module.specialAccess.size)
        assertEquals("Shizuku", module.specialAccess.single().label)
        assertFalse("no Shizuku on a stock emulator", module.specialAccess.single().isGranted())
        assertEquals(DeepRules.all.size, module.rules.size)

        val obs = module.scan(ScanProgress.NONE)
        assertTrue(obs.all { it.tunnelId == DeepKeys.TUNNEL_ID })
        assertEquals(setOf(DeepKeys.SUBJECT_DEEP), obs.map { it.subject }.toSet())
        assertEquals("false", obs.single { it.key == DeepKeys.AVAILABLE }.value)
        val reason = obs.single { it.key == DeepKeys.REASON }.value
        assertTrue(reason, reason == DeepKeys.REASON_NOT_INSTALLED || reason == DeepKeys.REASON_NOT_RUNNING || reason == DeepKeys.REASON_NOT_GRANTED)
        assertEquals(2, obs.size)
        assertEquals("no UserService binding without Shizuku", 0, module.shell.bindAttempts)

        // Rules must cope with an empty scan and with the unavailable one.
        assertTrue(module.rules.flatMap { it.evaluate(RuleContext(module.id, emptyList(), emptyList(), isFirstScan = true)) }.isEmpty())
        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), isFirstScan = true)) }
        val unavailable = drafts.single()
        assertEquals(DeepRules.DEEP_UNAVAILABLE, unavailable.kind)
        assertEquals(Severity.INFO, unavailable.severity)
        assertTrue(unavailable.evidence.startsWith("Deep mode is off: "))
        val actions = module.actionsFor(unavailable)
        val installed = ShizukuStatus.read(context).installed
        if (installed) assertTrue(actions.single() is FindingAction.Perform) else assertTrue("dropped by the engine", actions.isEmpty())

        // Per-app findings fall back to Settings when Shizuku is not granted.
        val appDraft = FindingDraft(module.id, "android", DeepRules.MIC_OR_CAMERA_RECENT, Severity.NOTICE, "Android used the microphone today")
        val appActions = module.actionsFor(appDraft)
        assertTrue(appActions.first() is FindingAction.OpenAppDetails)
        assertTrue("system apps get no uninstall", appActions.none { it is FindingAction.RequestUninstall })
        assertTrue("no shell actions without Shizuku", appActions.none { it is FindingAction.Perform })
        val userDraft = FindingDraft(module.id, context.packageName, DeepRules.CLIPBOARD_READER, Severity.NOTICE, "x read the clipboard today")
        assertTrue(module.actionsFor(userDraft).any { it is FindingAction.RequestUninstall })

        // Settings findings always have a Settings deep link.
        val adb = FindingDraft(module.id, DeepKeys.SUBJECT_SETTINGS, DeepRules.ADB_ENABLED, Severity.NOTICE, "USB debugging is on")
        assertTrue(module.actionsFor(adb).single() is FindingAction.OpenSettings)
        assertEquals(0, module.shell.bindAttempts)

        // Posture findings: a Settings deep link each, and none of them starts a shell.
        val reboot = FindingDraft(module.id, PostureKeys.SUBJECT, "POSTURE_AUTO_REBOOT", Severity.WARN, "Auto reboot is off.")
        val rebootActions = module.actionsFor(reboot)
        assertTrue(rebootActions.single() is FindingAction.OpenSettings)
        assertEquals("android.settings.SECURITY_SETTINGS", (rebootActions.single() as FindingAction.OpenSettings).action)
        for (kind in PostureRules.kinds - PostureRules.POSTURE_UNREAD) {
            val actions = module.actionsFor(FindingDraft(module.id, PostureKeys.SUBJECT, kind, Severity.INFO, "x"))
            assertTrue(kind, actions.single() is FindingAction.OpenSettings)
        }
        val unread = module.actionsFor(FindingDraft(module.id, PostureKeys.SUBJECT, PostureRules.POSTURE_UNREAD, Severity.NOTICE, "x"))
        if (installed) assertTrue(unread.single() is FindingAction.Perform) else assertTrue("dropped by the engine", unread.isEmpty())
        assertEquals("no posture draft binds a shell", 0, module.shell.bindAttempts)
    }
}
