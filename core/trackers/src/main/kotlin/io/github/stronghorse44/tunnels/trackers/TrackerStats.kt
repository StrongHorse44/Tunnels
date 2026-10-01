package io.github.stronghorse44.tunnels.trackers

import io.github.stronghorse44.tunnels.model.Observation

/** Aggregates for the tunnel's summary panel, computed from one snapshot's observations. */
data class TrackerStats(
    val apps: Int,
    val appsWithTrackers: Int,
    val appsSkipped: Int,
    /** Tracker id to number of apps embedding it, most common first. */
    val perTracker: List<Pair<String, Int>>,
    /** Category to number of apps with at least one SDK in it, most common first. */
    val perCategory: List<Pair<TrackerCategory, Int>>,
) {
    companion object {
        val EMPTY = TrackerStats(0, 0, 0, emptyList(), emptyList())

        fun from(observations: List<Observation>): TrackerStats {
            val bySubject = observations.groupBy { it.subject }
            val trackerApps = HashMap<String, Int>()
            val categoryApps = HashMap<TrackerCategory, Int>()
            var withTrackers = 0
            var skipped = 0
            for ((_, obs) in bySubject) {
                val cats = HashSet<TrackerCategory>()
                var any = false
                for (o in obs) {
                    if (o.key == ApkKeys.SDK_SKIPPED) skipped++
                    val id = ApkKeys.trackerId(o.key) ?: continue
                    any = true
                    trackerApps[id] = (trackerApps[id] ?: 0) + 1
                    cats += ApkKeys.categoriesOf(o.value)
                }
                if (any) withTrackers++
                for (c in cats) categoryApps[c] = (categoryApps[c] ?: 0) + 1
            }
            return TrackerStats(
                apps = bySubject.size,
                appsWithTrackers = withTrackers,
                appsSkipped = skipped,
                perTracker = trackerApps.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { TrackerCatalog.nameOf(it.key) })
                    .map { it.key to it.value },
                perCategory = categoryApps.entries.sortedWith(compareByDescending<Map.Entry<TrackerCategory, Int>> { it.value }.thenBy { it.key.ordinal })
                    .map { it.key to it.value },
            )
        }
    }
}
