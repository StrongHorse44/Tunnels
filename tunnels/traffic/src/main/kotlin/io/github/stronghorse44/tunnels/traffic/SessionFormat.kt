package io.github.stronghorse44.tunnels.traffic

import io.github.stronghorse44.tunnels.dns.SessionTotals

/** Text for the notification and the panel. Pure, so it is unit-tested without a device. */
object SessionFormat {
    /** "under a minute", "12 min", "1 h 05 min". */
    fun elapsed(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
        }
    }

    /** Minutes left before the automatic stop, never negative. */
    fun remainingMinutes(startedAt: Long, now: Long, maxMs: Long): Long = ((startedAt + maxMs - now + 59_999) / 60_000).coerceAtLeast(0)

    /** "231 queries · 7 apps · 14 domains · 2 trackers · 40 blocked" (trackers, blocked and encrypted only when present). */
    fun counters(t: SessionTotals): String = buildString {
        append(plural(t.queries, "query", "queries"))
        append(" · ").append(plural(t.apps, "app", "apps"))
        append(" · ").append(plural(t.domains, "domain", "domains"))
        if (t.trackers > 0) append(" · ").append(plural(t.trackers, "tracker", "trackers"))
        if (t.blocked > 0) append(" · ").append(t.blocked).append(" blocked")
        if (t.encrypted > 0) append(" · ").append(plural(t.encrypted, "encrypted attempt", "encrypted attempts"))
    }

    fun plural(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"
}
