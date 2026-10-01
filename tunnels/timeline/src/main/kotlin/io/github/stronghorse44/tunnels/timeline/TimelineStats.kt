package io.github.stronghorse44.tunnels.timeline

import io.github.stronghorse44.tunnels.model.Observation

/** Aggregates for the tunnel's summary panel, computed from one snapshot's observations. */
data class TimelineStats(
    /** Null before any scan; otherwise whether usage access was granted at the last scan. */
    val accessGranted: Boolean?,
    val apps: Int,
    val unused60: Int,
    /** Device-wide megabytes in the last 30 days, or null when traffic could not be read. */
    val totalMb: Long?,
    /** Apps by foreground minutes in the last 30 days, most used first. */
    val topByForeground: List<Entry>,
    /** Apps by Wi-Fi plus mobile megabytes in the last 30 days, heaviest first. */
    val topByData: List<Entry>,
) {
    data class Entry(val packageName: String, val label: String, val amount: Long)

    companion object {
        const val TOP = 5
        val EMPTY = TimelineStats(null, 0, 0, null, emptyList(), emptyList())

        fun from(observations: List<Observation>): TimelineStats {
            if (observations.isEmpty()) return EMPTY
            val bySubject = observations.groupBy { it.subject }
            val summary = bySubject[TimelineKeys.SUMMARY].orEmpty()
            val granted = TimelineKeys.value(summary, TimelineKeys.ACCESS_USAGE)?.let { it == TimelineKeys.GRANTED }
            val byForeground = ArrayList<Entry>()
            val byData = ArrayList<Entry>()
            var apps = 0
            for ((pkg, obs) in bySubject) {
                if (!TimelineKeys.isApp(obs)) continue
                apps++
                val label = TimelineKeys.value(obs, TimelineKeys.LABEL) ?: pkg
                TimelineKeys.longValue(obs, TimelineKeys.FG_MINUTES_30)?.takeIf { it > 0 }?.let { byForeground += Entry(pkg, label, it) }
                TimelineKeys.totalMb(obs)?.takeIf { it > 0 }?.let { byData += Entry(pkg, label, it) }
            }
            val order = compareByDescending<Entry> { it.amount }.thenBy { it.label.lowercase() }
            return TimelineStats(
                accessGranted = granted,
                apps = TimelineKeys.longValue(summary, TimelineKeys.APPS_TOTAL)?.toInt() ?: apps,
                unused60 = TimelineKeys.longValue(summary, TimelineKeys.APPS_UNUSED_60)?.toInt() ?: 0,
                totalMb = TimelineKeys.longValue(summary, TimelineKeys.NET_TOTAL_MB_30),
                topByForeground = byForeground.sortedWith(order).take(TOP),
                topByData = byData.sortedWith(order).take(TOP),
            )
        }
    }
}
