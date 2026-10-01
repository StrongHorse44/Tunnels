package io.github.stronghorse44.tunnels.permrules

/** Observation key schema of the permissions tunnel. Subject is always the package name. */
object PermissionKeys {
    const val TUNNEL_ID = "permissions"

    const val APP_LABEL = "app:label"
    /** "true" for system and updated-system apps. */
    const val APP_SYSTEM = "app:system"
    const val APP_TARGET_SDK = "app:targetSdk"
    /** "versionName (versionCode)". */
    const val APP_VERSION = "app:version"
    /** Count of requested permissions not listed because the app requests more than the cap. */
    const val APP_PERMS_OMITTED = "app:permsOmitted"

    /** "perm:<android.permission.NAME>" = [GRANTED] | [DENIED]. */
    const val PERM_PREFIX = "perm:"
    /** GrapheneOS Network toggle: [ON] | [OFF] | [NA] when INTERNET is not requested. */
    const val TOGGLE_NETWORK = "toggle:network"
    /** GrapheneOS Sensors toggle: [ON] | [OFF] | [UNKNOWN] when OTHER_SENSORS is not visible. */
    const val TOGGLE_SENSORS = "toggle:sensors"
    /** [ENABLED] when one of the app's accessibility services is switched on, [INSTALLED] when it only ships one. */
    const val ACCESS_ACCESSIBILITY = "access:accessibility"

    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val ON = "on"
    const val OFF = "off"
    const val NA = "n/a"
    const val UNKNOWN = "unknown"
    const val ENABLED = "enabled"
    const val INSTALLED = "installed"

    fun permKey(permission: String) = PERM_PREFIX + permission

    fun isPermKey(key: String) = key.startsWith(PERM_PREFIX)

    fun permissionOf(key: String) = key.removePrefix(PERM_PREFIX)
}
