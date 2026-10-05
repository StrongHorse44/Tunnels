package io.github.stronghorse44.tunnels.deepmode

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.posture.PostureRules

/**
 * Findings of the deep_mode tunnel. State rules describe the current scan and clear with it; OPS_CHANGED
 * is the one change rule and is sticky. Evidence is written for someone who has never heard of app ops.
 */
object DeepRules {
    const val MIC_OR_CAMERA_RECENT = "MIC_OR_CAMERA_RECENT"
    const val BACKGROUND_SENSOR_ACCESS = "BACKGROUND_SENSOR_ACCESS"
    const val CLIPBOARD_READER = "CLIPBOARD_READER"
    const val ADB_ENABLED = "ADB_ENABLED"
    const val SCAN_ALWAYS_ENABLED = "SCAN_ALWAYS_ENABLED"
    const val LOCK_SCREEN_PRIVATE_CONTENT = "LOCK_SCREEN_PRIVATE_CONTENT"
    const val ACCESSIBILITY_SERVICE_ON = "ACCESSIBILITY_SERVICE_ON"
    const val OPS_CHANGED = "OPS_CHANGED"
    const val DEEP_UNAVAILABLE = "DEEP_UNAVAILABLE"

    /** Background use within this many days warns. */
    const val BACKGROUND_DAYS = 7

    /** Clipboard reads within this many days are noticed. */
    const val CLIPBOARD_DAYS = 7

    /** Kinds whose subject is a package. */
    val appKinds: Set<String> = setOf(MIC_OR_CAMERA_RECENT, BACKGROUND_SENSOR_ACCESS, CLIPBOARD_READER, OPS_CHANGED)

    private fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    private fun name(subject: String, obs: List<Observation>): String = value(obs, DeepKeys.APP_LABEL)?.takeIf { it.isNotBlank() } ?: subject

    /** "Signal used the microphone today" for RECORD_AUDIO or CAMERA last used within 24 hours. */
    val micOrCameraRecent: FindingRule = Rules.perSubject(MIC_OR_CAMERA_RECENT, Severity.NOTICE) { subject, obs ->
        if (!DeepKeys.isApp(subject)) return@perSubject null
        val used = listOf(DeepKeys.RECORD_AUDIO, DeepKeys.CAMERA).filter { value(obs, DeepKeys.lastKey(it)) == DeepKeys.AGE_TODAY }
        if (used.isEmpty()) null
        else "${name(subject, obs)} used the ${DeepKeys.joinLabels(used.map(DeepKeys::opLabel))} today"
    }

    /** Camera, microphone or location used while the app was not on screen, within [BACKGROUND_DAYS]. */
    val backgroundSensorAccess: FindingRule = Rules.perSubject(BACKGROUND_SENSOR_ACCESS, Severity.WARN) { subject, obs ->
        if (!DeepKeys.isApp(subject)) return@perSubject null
        val recent = DeepKeys.SENSOR_OPS.mapNotNull { op ->
            val age = value(obs, DeepKeys.bgLastKey(op)) ?: return@mapNotNull null
            val days = DeepKeys.ageDays(age) ?: return@mapNotNull null
            if (days <= BACKGROUND_DAYS) op to age else null
        }
        if (recent.isEmpty()) return@perSubject null
        val parts = recent.map { (op, age) -> "${DeepKeys.opLabel(op)} $age" }
        "${name(subject, obs)} used the ${DeepKeys.joinLabels(parts)} while in the background"
    }

    /** An app that is not a keyboard read the clipboard within [CLIPBOARD_DAYS]. */
    val clipboardReader: FindingRule = Rules.perSubject(CLIPBOARD_READER, Severity.NOTICE) { subject, obs ->
        if (!DeepKeys.isApp(subject)) return@perSubject null
        if (value(obs, DeepKeys.APP_IME) == "true") return@perSubject null
        val age = value(obs, DeepKeys.lastKey(DeepKeys.READ_CLIPBOARD)) ?: return@perSubject null
        val days = DeepKeys.ageDays(age) ?: return@perSubject null
        if (days > CLIPBOARD_DAYS) null
        else "${name(subject, obs)} read the clipboard $age; it is not a keyboard"
    }

    /** USB or wireless debugging is switched on. */
    val adbEnabled: FindingRule = Rules.perSubject(ADB_ENABLED, Severity.NOTICE) { subject, obs ->
        if (subject != DeepKeys.SUBJECT_SETTINGS) return@perSubject null
        val usb = value(obs, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_enabled")) == "1"
        val wifi = value(obs, DeepKeys.settingKey(DeepKeys.GLOBAL, "adb_wifi_enabled")) == "1"
        when {
            usb && wifi -> "USB and wireless debugging are on. Shizuku needs one of them to start; turn them off when you are done"
            usb -> "USB debugging is on. Anything plugged into the phone can run shell commands once you accept its key"
            wifi -> "Wireless debugging is on. Shizuku needs it to start; turn it off when you are done"
            else -> null
        }
    }

    /** Wi-Fi or Bluetooth scanning keeps running while the radio is off. */
    val scanAlwaysEnabled: FindingRule = Rules.perSubject(SCAN_ALWAYS_ENABLED, Severity.INFO) { subject, obs ->
        if (subject != DeepKeys.SUBJECT_SETTINGS) return@perSubject null
        val wifi = value(obs, DeepKeys.settingKey(DeepKeys.GLOBAL, "wifi_scan_always_enabled")) == "1"
        val ble = value(obs, DeepKeys.settingKey(DeepKeys.GLOBAL, "ble_scan_always_enabled")) == "1"
        when {
            wifi && ble -> "Wi-Fi and Bluetooth scanning stay on even when both radios are off, so location services can still see nearby networks"
            wifi -> "Wi-Fi scanning stays on even when Wi-Fi is off, so location services can still see nearby networks"
            ble -> "Bluetooth scanning stays on even when Bluetooth is off, so location services can still see nearby devices"
            else -> null
        }
    }

    /** Notification content is readable on the lock screen without unlocking. */
    val lockScreenPrivateContent: FindingRule = Rules.perSubject(LOCK_SCREEN_PRIVATE_CONTENT, Severity.INFO) { subject, obs ->
        if (subject != DeepKeys.SUBJECT_SETTINGS) return@perSubject null
        val private = value(obs, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_allow_private_notifications")) == "1"
        val shown = value(obs, DeepKeys.settingKey(DeepKeys.SECURE, "lock_screen_show_notifications")) != "0"
        if (private && shown) "Notification content is shown on the lock screen; anyone holding the phone can read it" else null
    }

    /** One or more accessibility services are enabled: they can read the screen and act in any app. */
    val accessibilityServiceOn: FindingRule = Rules.perSubject(ACCESSIBILITY_SERVICE_ON, Severity.NOTICE) { subject, obs ->
        if (subject != DeepKeys.SUBJECT_SETTINGS) return@perSubject null
        val services = SettingsParser.accessibilityServices(value(obs, DeepKeys.settingKey(DeepKeys.SECURE, "enabled_accessibility_services")))
        if (services.isEmpty()) null
        else "Accessibility services can read the screen and tap for you. On: ${services.joinToString(", ")}"
    }

    /**
     * Sticky: an app's permission for one or more tracked ops went from one mode to another since the
     * last scan. One draft per app listing every changed op, because the engine keeps a single finding
     * per (subject, kind) and a second change in the same scan would otherwise be lost.
     */
    val opsChanged: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        ctx.diff.asSequence()
            .filterIsInstance<DiffEntry.Changed>()
            .filter { DeepKeys.isApp(it.after.subject) && DeepKeys.isModeKey(it.after.key) && DeepKeys.opOf(it.after.key) != null }
            .groupBy { it.after.subject }
            .map { (subject, changes) ->
                val parts = changes
                    .sortedBy { c -> DeepKeys.TRACKED_OPS.indexOf(DeepKeys.opOf(c.after.key)).let { if (it < 0) Int.MAX_VALUE else it } }
                    .map { c -> "${DeepKeys.opLabel(DeepKeys.opOf(c.after.key)!!)} from ${c.before.value} to ${c.after.value}" }
                FindingDraft(ctx.tunnelId, subject, OPS_CHANGED, Severity.INFO, "$CHANGED_PREFIX${DeepKeys.joinLabels(parts)}", sticky = true)
            }
    }

    /** Deep mode is off: Shizuku missing, stopped or not granted. Actions exist only when the app is installed. */
    val deepUnavailable: FindingRule = Rules.perSubject(DEEP_UNAVAILABLE, Severity.INFO) { subject, obs ->
        if (subject != DeepKeys.SUBJECT_DEEP || value(obs, DeepKeys.AVAILABLE) != "false") return@perSubject null
        "Deep mode is off: ${value(obs, DeepKeys.REASON) ?: "Shizuku is unavailable"}"
    }

    val all: List<FindingRule> = listOf(
        micOrCameraRecent, backgroundSensorAccess, clipboardReader, adbEnabled, scanAlwaysEnabled,
        lockScreenPrivateContent, accessibilityServiceOn, opsChanged, deepUnavailable,
    ) + PostureRules.all

    /**
     * Which tracked ops an app finding's evidence talks about, so actions can target them. Only the part
     * after the app's name counts: "Open Camera used the microphone today" is about the microphone, not
     * the camera. App rules write "<name> used the ..." or "<name> read the ..."; OPS_CHANGED evidence
     * starts with [CHANGED_PREFIX] and has no name.
     */
    fun opsMentioned(evidence: String): Set<String> = buildSet {
        val text = bodyOf(evidence).lowercase()
        if ("microphone" in text) add(DeepKeys.RECORD_AUDIO)
        if ("camera" in text) add(DeepKeys.CAMERA)
        if ("location" in text) add(DeepKeys.FINE_LOCATION)
        if ("clipboard" in text) add(DeepKeys.READ_CLIPBOARD)
    }

    /** Evidence of OPS_CHANGED starts with this; the rest lists "<op> from <mode> to <mode>" parts. */
    const val CHANGED_PREFIX = "Access changed: "

    private val bodyMarkers = listOf(" used the ", " read the ", CHANGED_PREFIX)

    /** The evidence without the app name that app rules put in front of it; empty when no marker is found. */
    private fun bodyOf(evidence: String): String {
        val cut = bodyMarkers.maxOf { marker -> evidence.lastIndexOf(marker).let { if (it < 0) -1 else it + marker.length } }
        return if (cut < 0) "" else evidence.substring(cut)
    }
}
