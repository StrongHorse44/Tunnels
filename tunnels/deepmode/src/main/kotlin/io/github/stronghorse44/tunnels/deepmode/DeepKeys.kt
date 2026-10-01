package io.github.stronghorse44.tunnels.deepmode

import java.util.concurrent.TimeUnit

/**
 * Observation schema of the deep_mode tunnel. Plain Kotlin.
 *
 * Subjects: `deep` (availability and totals), `settings` (hidden Settings keys) and one per package.
 * Per-package keys: `app:label`, `app:system`, `app:ime`, `ops:<OP>:mode`, `ops:<OP>:last`,
 * `ops:<OP>:bgLast`. Ages are coarse ("today", "3 days ago", "30+ days", "never"), never timestamps.
 */
object DeepKeys {
    const val TUNNEL_ID = "deep_mode"

    const val SUBJECT_DEEP = "deep"
    const val SUBJECT_SETTINGS = "settings"

    const val AVAILABLE = "deep:available"
    const val REASON = "deep:reason"
    const val SHIZUKU_VERSION = "deep:shizukuVersion"
    const val APPS_SCANNED = "deep:appsScanned"
    const val APPS_OMITTED = "deep:appsOmitted"
    const val DISABLED_BY_USER = "pkgs:disabledByUser"

    const val APP_LABEL = "app:label"
    const val APP_SYSTEM = "app:system"
    /** Present (and "true") only for apps that provide a keyboard: they read the clipboard by design. */
    const val APP_IME = "app:ime"

    const val REASON_NOT_INSTALLED = "Shizuku is not installed"
    const val REASON_NOT_RUNNING = "Shizuku is installed but not running"
    const val REASON_NOT_GRANTED = "Shizuku is running but Tunnels has not been granted access"
    const val REASON_SHELL_FAILED = "The deep shell did not start"

    const val GLOBAL = "global"
    const val SECURE = "secure"

    // App ops this tunnel tracks (names as `appops` prints them).
    const val CAMERA = "CAMERA"
    const val RECORD_AUDIO = "RECORD_AUDIO"
    const val COARSE_LOCATION = "COARSE_LOCATION"
    const val FINE_LOCATION = "FINE_LOCATION"
    const val READ_CLIPBOARD = "READ_CLIPBOARD"
    const val WRITE_CLIPBOARD = "WRITE_CLIPBOARD"
    const val READ_CONTACTS = "READ_CONTACTS"
    const val READ_SMS = "READ_SMS"
    const val GET_USAGE_STATS = "GET_USAGE_STATS"
    const val SYSTEM_ALERT_WINDOW = "SYSTEM_ALERT_WINDOW"

    val TRACKED_OPS: List<String> = listOf(
        CAMERA, RECORD_AUDIO, COARSE_LOCATION, FINE_LOCATION, READ_CLIPBOARD, WRITE_CLIPBOARD,
        READ_CONTACTS, READ_SMS, GET_USAGE_STATS, SYSTEM_ALERT_WINDOW,
    )

    /** Ops whose background use is worth a warning. */
    val SENSOR_OPS: List<String> = listOf(CAMERA, RECORD_AUDIO, COARSE_LOCATION, FINE_LOCATION)

    /** Settings keys worth knowing, looked up in both the global and the secure table. */
    val WATCHED_SETTINGS: Set<String> = setOf(
        "adb_enabled", "adb_wifi_enabled", "development_settings_enabled", "install_non_market_apps",
        "package_verifier_enable", "verifier_verify_adb_installs", "usb_mass_storage_enabled", "mock_location",
        "airplane_mode_on", "private_dns_mode", "lock_screen_allow_private_notifications",
        "lock_screen_show_notifications", "accessibility_enabled", "enabled_accessibility_services",
        "default_input_method", "location_mode", "bluetooth_on", "wifi_scan_always_enabled", "ble_scan_always_enabled",
    )

    const val AGE_TODAY = "today"
    const val AGE_OLD = "30+ days"
    const val AGE_NEVER = "never"

    /** Longest a settings value is kept; the rest is replaced by an ellipsis. */
    const val MAX_SETTING_VALUE = 300

    fun modeKey(op: String) = "ops:$op:mode"
    fun lastKey(op: String) = "ops:$op:last"
    fun bgLastKey(op: String) = "ops:$op:bgLast"
    fun settingKey(table: String, key: String) = "$table:$key"

    fun isModeKey(key: String) = key.startsWith("ops:") && key.endsWith(":mode")

    /** "ops:CAMERA:mode" -> "CAMERA"; null for other keys. */
    fun opOf(key: String): String? {
        if (!key.startsWith("ops:")) return null
        val rest = key.substring(4)
        val cut = rest.lastIndexOf(':')
        return if (cut <= 0) null else rest.substring(0, cut)
    }

    /** Subjects that are not packages. */
    fun isApp(subject: String) = subject != SUBJECT_DEEP && subject != SUBJECT_SETTINGS

    /** Day-granularity age for an access [agoMillis] in the past; null means never. */
    fun coarseAge(agoMillis: Long?): String {
        if (agoMillis == null || agoMillis < 0) return AGE_NEVER
        val days = TimeUnit.MILLISECONDS.toDays(agoMillis)
        return when {
            days < 1 -> AGE_TODAY
            days == 1L -> "1 day ago"
            days < 30 -> "$days days ago"
            else -> AGE_OLD
        }
    }

    /** Inverse of [coarseAge] for rule thresholds: "today" -> 0, "3 days ago" -> 3, "30+ days"/"never" -> null. */
    fun ageDays(value: String?): Int? = when {
        value == null -> null
        value == AGE_TODAY -> 0
        value.endsWith(" ago") -> value.substringBefore(' ').toIntOrNull()
        else -> null
    }

    /** Plain-language name of an op for evidence strings. */
    fun opLabel(op: String): String = when (op) {
        CAMERA -> "camera"
        RECORD_AUDIO -> "microphone"
        COARSE_LOCATION -> "approximate location"
        FINE_LOCATION -> "precise location"
        READ_CLIPBOARD -> "clipboard (read)"
        WRITE_CLIPBOARD -> "clipboard (write)"
        READ_CONTACTS -> "contacts"
        READ_SMS -> "SMS"
        GET_USAGE_STATS -> "usage access"
        SYSTEM_ALERT_WINDOW -> "draw over other apps"
        else -> op.lowercase().replace('_', ' ')
    }

    /** Joins labels for a sentence: "camera", "camera and microphone", "camera, microphone and location". */
    fun joinLabels(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
}
