package io.github.stronghorse44.tunnels.dns

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation key schema of the traffic tunnel. App subjects are package names (or `uid:<n>` for a uid
 * with no package, `unknown` when the owner could not be resolved, `other` once the per-session app cap
 * is reached); the [SUMMARY] subject describes the tunnel itself.
 */
object TrafficKeys {
    const val TUNNEL_ID = "traffic"
    const val SUMMARY = "summary"
    const val UNKNOWN_SUBJECT = "unknown"
    const val UID_PREFIX = "uid:"
    const val OTHER_SUBJECT = "other"

    /** Events-table kind of every row the session service writes. */
    const val EVENT_KIND = "SESSION"
    /** `android.provider.Settings.ACTION_VPN_SETTINGS`, spelled out so plain-Kotlin rules can name it. */
    const val ACTION_VPN_SETTINGS = "android.settings.VPN_SETTINGS"

    /** Distinct registrable domains the app looked up: the most seen in any one logging interval of the last 30 days. */
    const val DOMAINS30 = "dns:domains30"
    /** DNS queries over the last 30 days of sessions. */
    const val QUERIES30 = "dns:queries30"
    /** Up to five most-queried registrable domains, comma-separated. */
    const val TOP = "dns:top"
    /** Distinct tracking domains (per [TrackerDomains]) contacted in the last 30 days. */
    const val TRACKER_DOMAINS30 = "dns:trackerDomains30"
    /** Up to five most-queried tracking domains, comma-separated. */
    const val TRACKER_TOP = "dns:trackerTop"
    /** Encrypted DNS (port 853) attempts seen in the last 30 days; present only when non-zero. */
    const val ENCRYPTED30 = "dns:encrypted30"

    const val SESSIONS_COUNT30 = "sessions:count30"
    const val SESSION_ACTIVE = "session:active"
    const val VPN_OTHER_ACTIVE = "vpn:otherActive"

    const val TOP_MAX = 5

    fun isPackageSubject(subject: String): Boolean =
        subject != SUMMARY && subject != UNKNOWN_SUBJECT && subject != OTHER_SUBJECT && !subject.startsWith(UID_PREFIX)

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun intValue(obs: List<Observation>, key: String): Int? = value(obs, key)?.toIntOrNull()

    fun list(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
