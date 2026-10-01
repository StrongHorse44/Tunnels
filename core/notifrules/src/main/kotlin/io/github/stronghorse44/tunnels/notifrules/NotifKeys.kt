package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation key schema of the notifications tunnel. The subject is a package name, or [SUMMARY] for
 * the device-wide totals. Every value is a count, a rate, a boolean or a short comma list: never text
 * from a notification.
 */
object NotifKeys {
    const val TUNNEL_ID = "notifications"

    /** Kind of the one events-table row the listener writes per posted notification. */
    const val EVENT_POSTED = "POSTED"

    /** Subject of the device-wide observations. */
    const val SUMMARY = "summary"

    // Per package
    const val LABEL = "app:label"
    const val COUNT_7 = "notif:count7"
    const val COUNT_30 = "notif:count30"
    /** Average notifications per day over the last 7 days, one decimal ("12.4"). */
    const val PER_DAY_7 = "notif:perDay7"
    /** Notifications in the last 7 days whose contents show in full on the lock screen. */
    const val LOCK_PUBLIC_7 = "notif:lockPublic7"
    /** Notifications in the last 7 days posted at high (peeking, sounding) importance. */
    const val URGENT_7 = "notif:urgent7"
    /** Notifications in the last 7 days that made no sound or vibration. */
    const val SILENT_7 = "notif:silent7"
    const val ONGOING_7 = "notif:ongoing7"
    /** Notifications in the last 7 days posted between 23:00 and 06:00. */
    const val NIGHT_7 = "notif:night7"
    /**
     * Comma list of notification categories seen in the last 7 days (the last 30 when the app posted nothing this
     * week), "none" when every post had no category. Same window as the counts the rules read.
     */
    const val CATEGORIES = "notif:categories"

    // Summary subject
    /** "true" while the listener service is bound to the system. */
    const val LISTENER_CONNECTED = "listener:connected"
    /** "true" when the user has granted Notification access to this app. */
    const val ACCESS_GRANTED = "access:granted"
    const val TOTAL_7 = "notif:total7"
    const val TOTAL_30 = "notif:total30"
    /** Number of apps that notified in the last 7 days. */
    const val APPS_ACTIVE_7 = "apps:active7"
    /** Number of apps above the noisy threshold ([NotifRules.NOISY_PER_DAY]). */
    const val APPS_NOISY = "apps:noisy"
    /** Present only when more apps notified than fit the per-scan cap; value is how many were folded away. */
    const val APPS_OVERFLOW = "apps:overflow"
    /**
     * Present only when the listener failed to record posts since its process started (the encrypted store
     * would not open, or the queue overflowed); value is how many. Counts are low by at least that many.
     */
    const val LISTENER_DROPPED = "listener:dropped"
    /** "false" when the system lock screen hides notifications altogether (Settings > Lock screen), "true" otherwise. */
    const val LOCKSCREEN_SHOWS = "lockscreen:showsNotifications"
    /** Present ("true") only when the scan hit its row cap, so the 30-day totals undercount. */
    const val EVENTS_TRUNCATED = "events:truncated"

    const val NO_CATEGORIES = "none"

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun int(obs: List<Observation>, key: String): Int = value(obs, key)?.toIntOrNull() ?: 0

    fun double(obs: List<Observation>, key: String): Double = value(obs, key)?.toDoubleOrNull() ?: 0.0

    fun categories(obs: List<Observation>): Set<String> = categoriesOf(value(obs, CATEGORIES))

    fun categoriesOf(value: String?): Set<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != NO_CATEGORIES }.toSet()

    fun categoriesValue(categories: Collection<String>): String =
        categories.filter { it.isNotEmpty() }.toSortedSet().joinToString(",").ifEmpty { NO_CATEGORIES }
}
