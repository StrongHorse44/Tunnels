package io.github.stronghorse44.tunnels.timeline

import io.github.stronghorse44.tunnels.model.Observation
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Observation key schema of the timeline tunnel. The subject is a package name, or [SUMMARY] for the
 * device-wide totals. Everything is a count, a day or a rounded megabyte figure: no session times, no
 * per-hour histograms.
 */
object TimelineKeys {
    const val TUNNEL_ID = "timeline"
    const val SUMMARY = "summary"

    const val LABEL = "app:label"
    /** "true" for system (preinstalled) apps, "false" otherwise. */
    const val SYSTEM = "app:system"
    /** ISO date (day granularity) of the first install. */
    const val FIRST_INSTALL = "app:firstInstall"
    /**
     * Minutes in the foreground over the last 7 days, rounded. Approximate: only daily buckets that start
     * inside the window count, so the partial bucket at the window edge is left out (about 6.5 days).
     */
    const val FG_MINUTES_7 = "usage:fgMinutes7"
    /**
     * Minutes in the foreground over the last 30 days, rounded (under 30 seconds rounds to 0). Approximate:
     * the larger of the daily sum (Android keeps about ten days of daily buckets) and the weekly sum, where
     * a weekly bucket that ends inside the window counts in full, so it may include up to a week of older use.
     */
    const val FG_MINUTES_30 = "usage:fgMinutes30"
    /**
     * Distinct days with foreground use among the daily buckets Android still retains (about the last ten
     * days), so this is a floor for the 30-day window, not the full count.
     */
    const val DAYS_USED_30 = "usage:daysUsed30"
    /** Times an activity of the app came to the foreground in the last 7 days. */
    const val LAUNCHES_7 = "usage:launches7"
    /** ISO date of the last foreground use, or [NEVER]. */
    const val LAST_USED = "usage:lastUsed"
    /** Megabytes over Wi-Fi in the last 30 days (rounded, SI megabytes). */
    const val WIFI_MB_30 = "net:wifiMb30"
    /** Megabytes over mobile data in the last 30 days. */
    const val MOBILE_MB_30 = "net:mobileMb30"
    /** Megabytes moved while the app was in the foreground; only when the system splits traffic by state. */
    const val FG_MB_30 = "net:fgMb30"
    /** Megabytes moved in the background; only when the system splits traffic by state. */
    const val BG_MB_30 = "net:bgMb30"
    /**
     * Number of other installed apps sharing this app's Linux user id. Traffic is counted per uid, so the
     * `net:` figures of such apps are the same shared total. Only emitted when greater than zero.
     */
    const val SHARED_UID_APPS = "net:sharedUidApps"

    /** Summary: [GRANTED] or [NOT_GRANTED]. Without usage access only the summary subject is emitted. */
    const val ACCESS_USAGE = "access:usage"
    /** Summary: installed apps covered (this app excluded). */
    const val APPS_TOTAL = "apps:total"
    /** Summary: user apps not opened in [UNUSED_DAYS]+ days. */
    const val APPS_UNUSED_60 = "apps:unused60"
    /** Summary: megabytes over all networks and apps in the last 30 days. */
    const val NET_TOTAL_MB_30 = "net:totalMb30"
    /** Summary: [NET_YES], [NET_TIMEOUT] or [NET_ERROR]: whether network totals could be read. */
    const val NET_AVAILABLE = "net:available"

    const val NEVER = "never"
    const val GRANTED = "granted"
    const val NOT_GRANTED = "not granted"
    const val NET_YES = "yes"
    const val NET_TIMEOUT = "timeout"
    const val NET_ERROR = "error"

    /** Days without a foreground use after which a user app counts as unused. */
    const val UNUSED_DAYS = 60L

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun longValue(obs: List<Observation>, key: String): Long? = value(obs, key)?.toLongOrNull()

    fun isSummary(subject: String) = subject == SUMMARY

    /** True for a package's observations (the summary subject has no label). */
    fun isApp(obs: List<Observation>) = obs.any { it.key == LABEL }

    fun isSystem(obs: List<Observation>) = value(obs, SYSTEM) == "true"

    /** Whole days from an ISO date to [today]; null for [NEVER] or anything unparseable. */
    fun daysSince(isoDate: String?, today: LocalDate): Long? {
        if (isoDate == null || isoDate == NEVER) return null
        return try {
            ChronoUnit.DAYS.between(LocalDate.parse(isoDate), today)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * A user app that has not been opened in [UNUSED_DAYS] days (or ever), and has been installed at
     * least that long, so a fresh install is not called unused on day one.
     */
    fun isUnused(obs: List<Observation>, today: LocalDate): Boolean {
        if (!isApp(obs) || isSystem(obs)) return false
        val lastUsed = value(obs, LAST_USED) ?: return false
        val installedDays = daysSince(value(obs, FIRST_INSTALL), today)
        if (installedDays != null && installedDays < UNUSED_DAYS) return false
        if (lastUsed == NEVER) return true
        val days = daysSince(lastUsed, today) ?: return false
        return days >= UNUSED_DAYS
    }

    /** Wi-Fi plus mobile megabytes, when both were observed. */
    fun totalMb(obs: List<Observation>): Long? {
        val wifi = longValue(obs, WIFI_MB_30) ?: return null
        val mobile = longValue(obs, MOBILE_MB_30) ?: return null
        return wifi + mobile
    }
}
