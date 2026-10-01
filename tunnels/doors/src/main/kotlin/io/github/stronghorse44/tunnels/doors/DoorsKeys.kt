package io.github.stronghorse44.tunnels.doors

/** Observation key schema of the doors tunnel. Subject is always the package name; values are counts unless noted. */
object DoorsKeys {
    const val TUNNEL_ID = "doors"

    const val APP_LABEL = "app:label"
    /** "true" for system and updated-system apps. */
    const val APP_SYSTEM = "app:system"
    /** Component kinds too large to read ("activities,receivers"), whose counts below are 0; absent when everything was read. */
    const val APP_PARTIAL = "app:partial"

    const val EXPORTED_ACTIVITIES = "exported:activities"
    const val EXPORTED_SERVICES = "exported:services"
    const val EXPORTED_RECEIVERS = "exported:receivers"
    const val EXPORTED_PROVIDERS = "exported:providers"
    /** Exported components any app can reach: no permission attribute (providers: neither read nor write permission). */
    const val EXPORTED_UNPROTECTED = "exported:unprotected"
    const val UNPROTECTED_ACTIVITIES = "unprotected:activities"
    const val UNPROTECTED_SERVICES = "unprotected:services"
    const val UNPROTECTED_RECEIVERS = "unprotected:receivers"
    const val UNPROTECTED_PROVIDERS = "unprotected:providers"
    /** Exported providers with grantUriPermissions. */
    const val PROVIDER_GRANT_URI = "provider:grantUri"
    /** Web domains the system verified for this app (DomainVerificationManager). */
    const val LINKS_VERIFIED = "links:verified"
    /** Web domains the user chose to open in this app. */
    const val LINKS_SELECTED = "links:selected"
    /** "true", only present when the app offers an ACTION_SEND target for text or images. */
    const val HANDLER_SHARE = "handler:share"
    /** "true", only present when the app offers to open http/https links. */
    const val HANDLER_BROWSER = "handler:browser"

    const val TRUE = "true"
}
