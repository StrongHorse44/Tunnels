package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.Snapshot
import java.time.Duration
import java.time.Instant

object RetentionPolicy {
    const val KEEP_SNAPSHOTS = 12
    val EVENT_TTL: Duration = Duration.ofDays(30)

    /** Snapshots to delete: everything beyond the newest [keep] unpinned ones. Pinned snapshots are never deleted. */
    fun snapshotsToDelete(snapshots: List<Snapshot>, keep: Int = KEEP_SNAPSHOTS): List<Snapshot> =
        snapshots.filterNot { it.pinned }
            .sortedWith(compareByDescending<Snapshot> { it.takenAt }.thenByDescending { it.id })
            .drop(keep)

    /** Events at or before this instant have expired. */
    fun eventCutoff(now: Instant): Instant = now.minus(EVENT_TTL)
}
