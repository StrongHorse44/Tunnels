package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation key schema of the crossroads tunnel: facts about one app that come from different tunnels, joined.
 * The subject is a package name, or [SUMMARY] for which sources were joined and how old each was.
 */
object CrossKeys {
    const val TUNNEL_ID = "crossroads"
    const val SUMMARY = "summary"

    const val LABEL = "app:label"
    /** "true" for system (preinstalled) apps. */
    const val SYSTEM = "app:system"
    /** [InstallSource.value] of the APK excavation's installer reading. */
    const val INSTALL_SOURCE = "install:source"
    /** The installing package, when it is a known one or anything other than a store. */
    const val INSTALLED_BY = "install:by"
    /** Sensitive permission groups granted, comma-separated labels (Permissions). Absent when none. */
    const val HELD = "access:held"
    /** "on" when one of the app's accessibility services is switched on (Permissions). */
    const val ACCESSIBILITY = "access:accessibility"
    /** GrapheneOS Network toggle: on, off or n/a (Permissions). */
    const val NETWORK = "access:network"
    /** Number of tracker SDKs embedded (APK excavation). */
    const val SDK_COUNT = "sdk:count"
    /** Up to [NAMES_MAX] SDK names, comma-separated. */
    const val SDK_NAMES = "sdk:names"
    /** Categories of those SDKs, comma-separated labels (ads, profiling, location, ...). */
    const val SDK_CATEGORIES = "sdk:categories"
    /** Distinct tracking domains looked up in the last 30 days of Traffic sessions. */
    const val DNS_TRACKERS = "dns:trackerDomains"
    /** Up to five of them, comma-separated. */
    const val DNS_TRACKER_TOP = "dns:trackerTop"
    /** Whole days without a foreground use, counted up to the Timeline scan's date. Only from [IDLE_DAYS] on. */
    const val IDLE_DAYS = "usage:idleDays"
    /** ISO date the app was last in the foreground, or "never" (Timeline). */
    const val LAST_OPENED = "usage:lastOpened"
    /** ISO date of the last camera use (Deep mode). */
    const val CAMERA_USED = "ops:cameraLastUsed"
    /** ISO date of the last microphone use (Deep mode). */
    const val MIC_USED = "ops:micLastUsed"
    /** "true" for Google Play services, GSF or the Play Store signed by Google (APK excavation); their SDK facts are left out. */
    const val GOOGLE_PLAY = "app:googlePlay"
    /** "true" while APK excavation holds an open finding that the signing key changed. */
    const val SIGNER_CHANGED = "signer:changed"
    /** What Permissions says the app newly gained, while that finding is open. */
    const val GAINED = "access:gained"

    /** Summary: `source:<tunnel>` = ISO date of the data that was joined, or [NONE]. */
    const val SOURCE_PREFIX = "source:"
    const val NONE = "none"
    const val ON = "on"
    const val NEVER = "never"

    /** Idle apps start counting here: well before Android resets permissions of unused apps (about 90 days). */
    const val IDLE_THRESHOLD_DAYS = 45L
    const val NAMES_MAX = 6

    fun sourceKey(tunnelId: String) = SOURCE_PREFIX + tunnelId

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun list(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Keys of source tunnels whose schemas live in Android modules (Timeline, Deep mode). tunnels:crossroads'
     * unit tests check every one against the module that owns it.
     */
    object Sources {
        const val PERMISSIONS = "permissions"
        const val APK = "apk_excavation"
        const val TRAFFIC = "traffic"

        const val TIMELINE = "timeline"
        const val TIMELINE_LABEL = "app:label"
        const val TIMELINE_SYSTEM = "app:system"
        const val TIMELINE_FIRST_INSTALL = "app:firstInstall"
        const val TIMELINE_LAST_USED = "usage:lastUsed"
        const val TIMELINE_NEVER = "never"

        const val DEEP = "deep_mode"
        const val DEEP_CAMERA = "CAMERA"
        const val DEEP_RECORD_AUDIO = "RECORD_AUDIO"
        const val DEEP_AGE_TODAY = "today"

        fun deepLastKey(op: String) = "ops:$op:last"

        /** Deep mode's coarse age ("today", "1 day ago", "3 days ago") in days; null for "30+ days", "never" or anything else. */
        fun deepAgeDays(value: String?): Int? = when {
            value == null -> null
            value == DEEP_AGE_TODAY -> 0
            value.endsWith(" ago") -> value.substringBefore(' ').toIntOrNull()
            else -> null
        }

        val ALL: Set<String> = setOf(PERMISSIONS, APK, TRAFFIC, TIMELINE, DEEP)
    }
}
