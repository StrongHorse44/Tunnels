package io.github.stronghorse44.tunnels.deepmode

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.posture.PostureKeys
import io.github.stronghorse44.tunnels.posture.PostureRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepRulesTest {
    private fun obs(subject: String, key: String, value: String) = Observation(DeepKeys.TUNNEL_ID, subject, key, value)

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(DeepKeys.TUNNEL_ID, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return DeepRules.all.flatMap { it.evaluate(ctx) }
    }

    @Test
    fun micAndCameraUsedTodayAreNoticed() {
        val drafts = evaluate(
            listOf(
                obs("com.chat", DeepKeys.APP_LABEL, "Chat"),
                obs("com.chat", DeepKeys.lastKey(DeepKeys.RECORD_AUDIO), "today"),
                obs("com.chat", DeepKeys.lastKey(DeepKeys.CAMERA), "today"),
                obs("com.old", DeepKeys.lastKey(DeepKeys.CAMERA), "2 days ago"),
            ),
        )
        val d = drafts.single { it.kind == DeepRules.MIC_OR_CAMERA_RECENT }
        assertEquals("com.chat", d.subject)
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals("Chat used the microphone and camera today", d.evidence)
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun backgroundSensorUseWarnsWithinSevenDays() {
        val drafts = evaluate(
            listOf(
                obs("com.spy", DeepKeys.bgLastKey(DeepKeys.FINE_LOCATION), "3 days ago"),
                obs("com.spy", DeepKeys.bgLastKey(DeepKeys.RECORD_AUDIO), "today"),
                obs("com.fine", DeepKeys.bgLastKey(DeepKeys.CAMERA), "9 days ago"),
                obs("com.never", DeepKeys.bgLastKey(DeepKeys.CAMERA), "30+ days"),
            ),
        )
        val warn = drafts.filter { it.kind == DeepRules.BACKGROUND_SENSOR_ACCESS }
        assertEquals(listOf("com.spy"), warn.map { it.subject })
        assertEquals(Severity.WARN, warn.single().severity)
        assertEquals("com.spy used the microphone today and precise location 3 days ago while in the background", warn.single().evidence)
        assertEquals(setOf(DeepKeys.RECORD_AUDIO, DeepKeys.FINE_LOCATION), DeepRules.opsMentioned(warn.single().evidence))
    }

    @Test
    fun keyboardsMayReadTheClipboard() {
        val drafts = evaluate(
            listOf(
                obs("com.keyboard", DeepKeys.APP_IME, "true"),
                obs("com.keyboard", DeepKeys.lastKey(DeepKeys.READ_CLIPBOARD), "today"),
                obs("com.shop", DeepKeys.lastKey(DeepKeys.READ_CLIPBOARD), "5 days ago"),
                obs("com.rare", DeepKeys.lastKey(DeepKeys.READ_CLIPBOARD), "20 days ago"),
            ),
        )
        val readers = drafts.filter { it.kind == DeepRules.CLIPBOARD_READER }
        assertEquals(listOf("com.shop"), readers.map { it.subject })
        assertEquals("com.shop read the clipboard 5 days ago; it is not a keyboard", readers.single().evidence)
    }

    @Test
    fun settingsRulesReadTheSettingsSubject() {
        val s = DeepKeys.SUBJECT_SETTINGS
        val drafts = evaluate(
            listOf(
                obs(s, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_enabled"), "0"),
                obs(s, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_wifi_enabled"), "1"),
                obs(s, DeepKeys.settingKey(DeepKeys.GLOBAL, "wifi_scan_always_enabled"), "1"),
                obs(s, DeepKeys.settingKey(DeepKeys.GLOBAL, "ble_scan_always_enabled"), "0"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_allow_private_notifications"), "1"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_show_notifications"), "1"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "enabled_accessibility_services"), "com.a/com.a.Svc:com.b/com.b.x.Other"),
            ),
        )
        val kinds = drafts.associateBy { it.kind }
        assertTrue(kinds.getValue(DeepRules.ADB_ENABLED).evidence.startsWith("Wireless debugging is on"))
        assertTrue(kinds.getValue(DeepRules.SCAN_ALWAYS_ENABLED).evidence.startsWith("Wi-Fi scanning stays on"))
        assertEquals(Severity.INFO, kinds.getValue(DeepRules.LOCK_SCREEN_PRIVATE_CONTENT).severity)
        assertEquals(
            "Accessibility services can read the screen and tap for you. On: com.a/.Svc, com.b/.x.Other",
            kinds.getValue(DeepRules.ACCESSIBILITY_SERVICE_ON).evidence,
        )
        assertTrue(drafts.all { it.subject == s })
    }

    @Test
    fun quietSettingsProduceNothing() {
        val s = DeepKeys.SUBJECT_SETTINGS
        val drafts = evaluate(
            listOf(
                obs(s, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_enabled"), "0"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_allow_private_notifications"), "1"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_show_notifications"), "0"),
                obs(s, DeepKeys.settingKey(DeepKeys.SECURE, "enabled_accessibility_services"), "null"),
                obs(DeepKeys.SUBJECT_DEEP, DeepKeys.AVAILABLE, "true"),
            ),
        )
        assertTrue(drafts.toString(), drafts.isEmpty())
    }

    @Test
    fun opsChangedIsStickyAndSkipsTheFirstScan() {
        val before = listOf(obs("com.app", DeepKeys.modeKey(DeepKeys.CAMERA), "allow"), obs("com.app", DeepKeys.lastKey(DeepKeys.CAMERA), "today"))
        val after = listOf(obs("com.app", DeepKeys.modeKey(DeepKeys.CAMERA), "ignore"), obs("com.app", DeepKeys.lastKey(DeepKeys.CAMERA), "1 day ago"))
        assertTrue(evaluate(after).none { it.kind == DeepRules.OPS_CHANGED })
        val changed = evaluate(after, before).single { it.kind == DeepRules.OPS_CHANGED }
        assertTrue(changed.sticky)
        assertEquals("com.app", changed.subject)
        assertEquals("Access changed: camera from allow to ignore", changed.evidence)
        assertEquals(setOf(DeepKeys.CAMERA), DeepRules.opsMentioned(changed.evidence))
        // Only the age moved: no finding.
        val aged = listOf(obs("com.app", DeepKeys.modeKey(DeepKeys.CAMERA), "allow"), obs("com.app", DeepKeys.lastKey(DeepKeys.CAMERA), "2 days ago"))
        assertTrue(evaluate(aged, before).none { it.kind == DeepRules.OPS_CHANGED })
    }

    @Test
    fun opsChangedReportsEveryChangedOpOfAnAppInOneFinding() {
        val before = listOf(
            obs("com.app", DeepKeys.modeKey(DeepKeys.CAMERA), "allow"),
            obs("com.app", DeepKeys.modeKey(DeepKeys.RECORD_AUDIO), "allow"),
            obs("com.app", DeepKeys.modeKey(DeepKeys.READ_CLIPBOARD), "allow"),
            obs("com.other", DeepKeys.modeKey(DeepKeys.FINE_LOCATION), "foreground"),
            obs(DeepKeys.SUBJECT_SETTINGS, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_enabled"), "0"),
        )
        val after = listOf(
            obs("com.app", DeepKeys.modeKey(DeepKeys.CAMERA), "ignore"),
            obs("com.app", DeepKeys.modeKey(DeepKeys.RECORD_AUDIO), "ignore"),
            obs("com.app", DeepKeys.modeKey(DeepKeys.READ_CLIPBOARD), "allow"),
            obs("com.other", DeepKeys.modeKey(DeepKeys.FINE_LOCATION), "ignore"),
            obs(DeepKeys.SUBJECT_SETTINGS, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_enabled"), "1"),
        )
        val changed = evaluate(after, before).filter { it.kind == DeepRules.OPS_CHANGED }.associateBy { it.subject }
        assertEquals(setOf("com.app", "com.other"), changed.keys)
        assertEquals("Access changed: camera from allow to ignore and microphone from allow to ignore", changed.getValue("com.app").evidence)
        assertEquals(setOf(DeepKeys.CAMERA, DeepKeys.RECORD_AUDIO), DeepRules.opsMentioned(changed.getValue("com.app").evidence))
        assertEquals("Access changed: precise location from foreground to ignore", changed.getValue("com.other").evidence)
        assertTrue(changed.values.all { it.sticky })
    }

    @Test
    fun opsMentionedIgnoresTheAppName() {
        assertEquals(setOf(DeepKeys.RECORD_AUDIO), DeepRules.opsMentioned("Open Camera used the microphone today"))
        assertEquals(setOf(DeepKeys.READ_CLIPBOARD), DeepRules.opsMentioned("Camera Location Pro read the clipboard today; it is not a keyboard"))
        assertEquals(
            setOf(DeepKeys.CAMERA, DeepKeys.FINE_LOCATION),
            DeepRules.opsMentioned("Microphone Notes used the camera today and approximate location 2 days ago while in the background"),
        )
        assertTrue(DeepRules.opsMentioned("Something without a marker about the camera").isEmpty())
    }

    @Test
    fun unavailableDeepModeIsAnInfoOnTheDeepSubject() {
        val drafts = evaluate(
            listOf(
                obs(DeepKeys.SUBJECT_DEEP, DeepKeys.AVAILABLE, "false"),
                obs(DeepKeys.SUBJECT_DEEP, DeepKeys.REASON, DeepKeys.REASON_NOT_RUNNING),
            ),
        )
        val d = drafts.single()
        assertEquals(DeepRules.DEEP_UNAVAILABLE, d.kind)
        assertEquals(DeepKeys.SUBJECT_DEEP, d.subject)
        assertEquals("Deep mode is off: Shizuku is installed but not running", d.evidence)
        assertTrue(evaluate(listOf(obs(DeepKeys.SUBJECT_DEEP, DeepKeys.AVAILABLE, "true"))).isEmpty())
    }

    @Test
    fun postureSubjectIsNotAnApp() {
        assertEquals(PostureKeys.SUBJECT, DeepKeys.SUBJECT_POSTURE)
        assertEquals(PostureKeys.TUNNEL_ID, DeepKeys.TUNNEL_ID)
        assertTrue(!DeepKeys.isApp(DeepKeys.SUBJECT_POSTURE))
        assertTrue(DeepKeys.isApp("com.chat"))
        // Keys that an app rule would act on, filed under posture, must not read as an app.
        val drafts = evaluate(
            listOf(
                obs(DeepKeys.SUBJECT_POSTURE, DeepKeys.lastKey(DeepKeys.RECORD_AUDIO), "today"),
                obs(DeepKeys.SUBJECT_POSTURE, DeepKeys.bgLastKey(DeepKeys.CAMERA), "today"),
                obs(DeepKeys.SUBJECT_POSTURE, DeepKeys.lastKey(DeepKeys.READ_CLIPBOARD), "today"),
                obs(DeepKeys.SUBJECT_POSTURE, DeepKeys.modeKey(DeepKeys.CAMERA), "ignore"),
            ),
            previous = listOf(obs(DeepKeys.SUBJECT_POSTURE, DeepKeys.modeKey(DeepKeys.CAMERA), "allow")),
        )
        assertTrue(drafts.toString(), drafts.isEmpty())
    }

    @Test
    fun postureRulesRunWithDeepModesRules() {
        assertTrue(DeepRules.all.containsAll(PostureRules.all))
        val drafts = evaluate(
            listOf(
                obs(DeepKeys.SUBJECT_POSTURE, "posture:auto_reboot", "weak"),
                obs(DeepKeys.SUBJECT_POSTURE, "posture:auto_reboot:value", "off"),
            ),
        )
        val d = drafts.single()
        assertEquals("POSTURE_AUTO_REBOOT", d.kind)
        assertEquals(DeepKeys.SUBJECT_POSTURE, d.subject)
        assertEquals(Severity.WARN, d.severity)
    }
}
