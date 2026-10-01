package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.model.Observation
import java.util.Locale

/** One events-table row, already parsed: which app posted, when (epoch millis), and the flags. */
data class NotifEvent(val packageName: String, val at: Long, val record: NotifRecord)

/** What one app did over the two windows. Counts, never contents. */
data class PackageStats(
    val packageName: String,
    val count7: Int,
    val count30: Int,
    val lockPublic7: Int,
    val urgent7: Int,
    val silent7: Int,
    val ongoing7: Int,
    val night7: Int,
    /**
     * Categories seen over the 7-day window the rules read, or over 30 days when the app posted nothing
     * this week. Empty strings (no category) are kept as "" so rules can tell.
     */
    val categories: Set<String>,
) {
    /** Average posts per day over the 7-day window. */
    val perDay7: Double get() = count7 / 7.0
}

/**
 * What the scan knows about the listener and the device beyond the events themselves. Becomes the
 * [NotifKeys.SUMMARY] observations.
 */
data class NotifDeviceState(
    val listenerConnected: Boolean,
    val accessGranted: Boolean,
    /** Posts the listener failed to record since its process started; 0 when none. */
    val dropped: Int = 0,
    /** Whether the system lock screen shows notifications at all; null when unknown. */
    val lockScreenShowsNotifications: Boolean? = null,
    /** True when the scan read its row cap and the 30-day totals undercount. */
    val eventsTruncated: Boolean = false,
)

data class NotifAggregate(
    /** Per package, noisiest first (count7 desc, then name). */
    val perPackage: List<PackageStats>,
    val total7: Int,
    val total30: Int,
) {
    val appsActive7: Int get() = perPackage.count { it.count7 > 0 }

    companion object {
        val EMPTY = NotifAggregate(emptyList(), 0, 0)
    }
}

/** Turns the listener's events into per-app and device-wide statistics and observations. */
object NotifAggregator {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WINDOW_7_MS = 7 * DAY_MS
    const val WINDOW_30_MS = 30 * DAY_MS

    /** Most apps that get their own observations; the rest are folded into [NotifKeys.APPS_OVERFLOW]. */
    const val MAX_APPS = 150

    /** Aggregates [events] as of [now] (epoch millis). Events in the future or older than 30 days are ignored. */
    fun aggregate(events: Iterable<NotifEvent>, now: Long): NotifAggregate {
        val since30 = now - WINDOW_30_MS
        val since7 = now - WINDOW_7_MS
        class Acc {
            var count7 = 0
            var count30 = 0
            var lockPublic7 = 0
            var urgent7 = 0
            var silent7 = 0
            var ongoing7 = 0
            var night7 = 0
            val categories7 = HashSet<String>()
            val categories30 = HashSet<String>()
            val categories: Set<String> get() = if (count7 > 0) categories7 else categories30
        }
        val byPkg = HashMap<String, Acc>()
        for (e in events) {
            if (e.at > now || e.at <= since30 || e.packageName.isBlank()) continue
            val acc = byPkg.getOrPut(e.packageName) { Acc() }
            acc.count30++
            acc.categories30 += e.record.category
            if (e.at > since7) {
                acc.count7++
                acc.categories7 += e.record.category
                if (e.record.lockScreenPublic) acc.lockPublic7++
                if (e.record.importance.urgent) acc.urgent7++
                if (e.record.silent) acc.silent7++
                if (e.record.ongoing) acc.ongoing7++
                if (e.record.night) acc.night7++
            }
        }
        val stats = byPkg.map { (pkg, a) ->
            PackageStats(pkg, a.count7, a.count30, a.lockPublic7, a.urgent7, a.silent7, a.ongoing7, a.night7, a.categories)
        }.sortedWith(compareByDescending<PackageStats> { it.count7 }.thenByDescending { it.count30 }.thenBy { it.packageName })
        return NotifAggregate(stats, stats.sumOf { it.count7 }, stats.sumOf { it.count30 })
    }

    /** Formats a per-day rate with one decimal, locale-independent. */
    fun formatRate(perDay: Double): String = String.format(Locale.ROOT, "%.1f", perDay)

    /** [observations] with only the two listener flags known. */
    fun observations(
        aggregate: NotifAggregate,
        listenerConnected: Boolean,
        accessGranted: Boolean,
        label: (String) -> String? = { null },
    ): List<Observation> = observations(aggregate, NotifDeviceState(listenerConnected, accessGranted), label)

    /**
     * The tunnel's observations: one group per app (capped at [MAX_APPS], noisiest first) and the summary subject.
     * [label] resolves a package to its display name; null falls back to the package name.
     */
    fun observations(
        aggregate: NotifAggregate,
        state: NotifDeviceState,
        label: (String) -> String? = { null },
    ): List<Observation> {
        val t = NotifKeys.TUNNEL_ID
        val out = ArrayList<Observation>(aggregate.perPackage.size * 10 + 8)
        val shown = aggregate.perPackage.take(MAX_APPS)
        for (s in shown) {
            fun add(key: String, value: String) = out.add(Observation(t, s.packageName, key, value))
            add(NotifKeys.LABEL, label(s.packageName)?.takeIf { it.isNotBlank() } ?: s.packageName)
            add(NotifKeys.COUNT_7, s.count7.toString())
            add(NotifKeys.COUNT_30, s.count30.toString())
            add(NotifKeys.PER_DAY_7, formatRate(s.perDay7))
            add(NotifKeys.LOCK_PUBLIC_7, s.lockPublic7.toString())
            add(NotifKeys.URGENT_7, s.urgent7.toString())
            add(NotifKeys.SILENT_7, s.silent7.toString())
            add(NotifKeys.ONGOING_7, s.ongoing7.toString())
            add(NotifKeys.NIGHT_7, s.night7.toString())
            add(NotifKeys.CATEGORIES, NotifKeys.categoriesValue(s.categories))
        }
        val summary = NotifKeys.SUMMARY
        out += Observation(t, summary, NotifKeys.LISTENER_CONNECTED, state.listenerConnected.toString())
        out += Observation(t, summary, NotifKeys.ACCESS_GRANTED, state.accessGranted.toString())
        if (state.dropped > 0) out += Observation(t, summary, NotifKeys.LISTENER_DROPPED, state.dropped.toString())
        state.lockScreenShowsNotifications?.let { out += Observation(t, summary, NotifKeys.LOCKSCREEN_SHOWS, it.toString()) }
        if (state.eventsTruncated) out += Observation(t, summary, NotifKeys.EVENTS_TRUNCATED, "true")
        out += Observation(t, summary, NotifKeys.TOTAL_7, aggregate.total7.toString())
        out += Observation(t, summary, NotifKeys.TOTAL_30, aggregate.total30.toString())
        out += Observation(t, summary, NotifKeys.APPS_ACTIVE_7, aggregate.appsActive7.toString())
        out += Observation(t, summary, NotifKeys.APPS_NOISY, aggregate.perPackage.count { it.perDay7 > NotifRules.NOISY_PER_DAY }.toString())
        val overflow = aggregate.perPackage.size - shown.size
        if (overflow > 0) out += Observation(t, summary, NotifKeys.APPS_OVERFLOW, overflow.toString())
        return out
    }
}
