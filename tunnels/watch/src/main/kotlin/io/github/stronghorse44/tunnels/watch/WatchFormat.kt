package io.github.stronghorse44.tunnels.watch

import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus

/** Text for the inbox's check panel. Pure, so it is unit-tested without a device. */
object WatchFormat {
    /** "just now", "12 min ago", "3 h ago", "2 days ago". */
    fun ago(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** One line about the schedule and the last check. */
    fun statusLine(settings: WatchSettings, status: WatchStatus, scheduled: Boolean, now: Long): String {
        val schedule = when {
            !settings.enabled -> "Off"
            !scheduled -> "On, waiting for Android to schedule it"
            else -> "Every ${settings.intervalHours} h"
        }
        if (status.neverRan) return "$schedule · no check yet"
        val result = buildString {
            append("last ${ago(now - status.lastRunAt)} (${status.reason}): ${status.tunnels} tunnels, ")
            append(if (status.added == 0) "nothing new" else "${status.added} new")
            if (!status.stored) append(", no changes")
            if (status.failed.isNotEmpty()) append(", failed: ${status.failed.joinToString { TunnelCatalog.byId(it)?.title ?: it }}")
        }
        return "$schedule · $result"
    }

    /** What a check runs, in plain words, from the policy itself so the text cannot drift from the code. */
    fun scope(): String {
        fun names(ids: List<String>) = ids.joinToString(", ") { TunnelCatalog.byId(it)?.title ?: it }
        return "Each check runs ${names(WatchPolicy.ALWAYS)}; ${names(WatchPolicy.APP_FILES)} also run when apps changed, after a restart, " +
            "or once a day. Checks are offline and store a snapshot only when something changed, so your own snapshots are not pushed out."
    }
}
