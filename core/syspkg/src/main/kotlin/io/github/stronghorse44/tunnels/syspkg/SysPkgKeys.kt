package io.github.stronghorse44.tunnels.syspkg

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation key schema of the system_packages tunnel. Subject is the package name, plus one
 * [SUMMARY] subject with device-wide counts.
 */
object SysPkgKeys {
    const val TUNNEL_ID = "system_packages"

    /** Subject of the device-wide counts. No real package is named this. */
    const val SUMMARY = "summary"

    const val LABEL = "pkg:label"
    /** One of [ENABLED_VALUE], [DISABLED_VALUE], [DISABLED_USER_VALUE], [DISABLED_UNTIL_USED_VALUE], [UNINSTALLED_USER_VALUE]. */
    const val ENABLED = "pkg:enabled"
    /** "true" when an update to the preinstalled version is installed (FLAG_UPDATED_SYSTEM_APP). */
    const val UPDATED = "pkg:updated"
    /** "versionName (versionCode)". */
    const val VERSION = "pkg:version"
    /** "true" when the package is in [SystemPackageKb]. */
    const val KNOWN = "pkg:known"
    /** [PackageCategory.label]; [UNKNOWN_CATEGORY] for packages not in the knowledge base. */
    const val CATEGORY = "pkg:category"
    /** [Namespace.label]: aosp, grapheneos, google or other. */
    const val NAMESPACE = "pkg:namespace"
    /** "true" when the APK lives in a priv-app directory (public approximation of a privileged app). */
    const val PRIVILEGED = "pkg:privileged"
    /** "true" when the package has a launcher activity. */
    const val HAS_LAUNCHER = "pkg:hasLauncher"
    /** The knowledge-base purpose line. Present only for known packages. */
    const val PURPOSE = "pkg:purpose"
    /** [DisableRisk.label]. Present only for known packages. */
    const val DISABLE_RISK = "pkg:disableRisk"

    const val TOTAL = "pkg:total"
    /** Number of system packages whose [ENABLED] value is not [ENABLED_VALUE]. */
    const val DISABLED = "pkg:disabled"
    const val UNKNOWN = "pkg:unknown"
    /** Present on [SUMMARY] only when more packages exist than [MAX_PACKAGES]; value is the count skipped. */
    const val SKIPPED = "pkg:skipped"

    const val ENABLED_VALUE = "enabled"
    const val DISABLED_VALUE = "disabled"
    const val DISABLED_USER_VALUE = "disabled-user"
    const val DISABLED_UNTIL_USED_VALUE = "disabled-until-used"
    /** Removed for the current user (`pm uninstall --user`) while the system image copy remains. */
    const val UNINSTALLED_USER_VALUE = "uninstalled-user"

    const val UNKNOWN_CATEGORY = "unknown"

    /** At most this many packages are described per scan; the rest are counted in [SKIPPED]. */
    const val MAX_PACKAGES = 1500

    // PackageManager.COMPONENT_ENABLED_STATE_* values, public API constants since API 1.
    const val STATE_DEFAULT = 0
    const val STATE_ENABLED = 1
    const val STATE_DISABLED = 2
    const val STATE_DISABLED_USER = 3
    const val STATE_DISABLED_UNTIL_USED = 4

    private val PRIV_APP_DIRS = listOf(
        "/system/priv-app/",
        "/system_ext/priv-app/",
        "/product/priv-app/",
        "/vendor/priv-app/",
        "/odm/priv-app/",
        "/oem/priv-app/",
    )
    private val APEX_PRIV_APP = Regex("^/apex/[^/]+/priv-app/")

    /** Maps a COMPONENT_ENABLED_STATE_* value and whether the package is installed for this user to an [ENABLED] value. */
    fun enabledValue(state: Int, installedForUser: Boolean = true): String = when {
        !installedForUser -> UNINSTALLED_USER_VALUE
        state == STATE_DISABLED -> DISABLED_VALUE
        state == STATE_DISABLED_USER -> DISABLED_USER_VALUE
        state == STATE_DISABLED_UNTIL_USED -> DISABLED_UNTIL_USED_VALUE
        else -> ENABLED_VALUE
    }

    /** "disabled-user" -> "disabled by the user", and so on, for evidence text. */
    fun describeEnabled(value: String): String = when (value) {
        ENABLED_VALUE -> "enabled"
        DISABLED_VALUE -> "disabled"
        DISABLED_USER_VALUE -> "disabled by the user"
        DISABLED_UNTIL_USED_VALUE -> "disabled until used"
        UNINSTALLED_USER_VALUE -> "removed for this user"
        else -> value
    }

    fun isDisabled(enabledValue: String?) = enabledValue != null && enabledValue != ENABLED_VALUE

    /** Whether an APK path points into a privileged app directory of any partition or APEX. */
    fun isPrivilegedPath(sourceDir: String?): Boolean {
        if (sourceDir.isNullOrEmpty()) return false
        return PRIV_APP_DIRS.any { sourceDir.startsWith(it) } || APEX_PRIV_APP.containsMatchIn(sourceDir)
    }

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun isKnown(obs: List<Observation>) = value(obs, KNOWN) == "true"
}
