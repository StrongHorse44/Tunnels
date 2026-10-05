package io.github.stronghorse44.tunnels.snapshots

import io.github.stronghorse44.tunnels.model.DiffEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How many observations appeared, disappeared or changed value. */
data class DiffCounts(val added: Int, val removed: Int, val changed: Int) {
    val total: Int get() = added + removed + changed

    operator fun plus(other: DiffCounts) = DiffCounts(added + other.added, removed + other.removed, changed + other.changed)

    companion object {
        val ZERO = DiffCounts(0, 0, 0)

        fun of(entries: Collection<DiffEntry>) = DiffCounts(
            added = entries.count { it is DiffEntry.Added },
            removed = entries.count { it is DiffEntry.Removed },
            changed = entries.count { it is DiffEntry.Changed },
        )
    }
}

/** One subject's changes; [hidden] entries were cut to keep the screen readable. */
data class SubjectDiff(val subject: String, val counts: DiffCounts, val entries: List<DiffEntry>, val hidden: Int)

data class TunnelDiff(val tunnelId: String, val counts: DiffCounts, val subjects: List<SubjectDiff>)

/** A diff grouped by tunnel, then subject. Counts always cover everything, even what was cut from [tunnels]. */
data class DiffReport(val counts: DiffCounts, val tunnels: List<TunnelDiff>, val truncatedSubjects: Int) {
    val isEmpty: Boolean get() = counts.total == 0
}

/** Shapes a flat diff for a phone screen: grouped, sorted, and capped per subject and overall. */
object DiffGroups {
    const val PER_SUBJECT = 40
    const val MAX_SUBJECTS = 300

    fun build(entries: List<DiffEntry>, perSubject: Int = PER_SUBJECT, maxSubjects: Int = MAX_SUBJECTS): DiffReport {
        var shown = 0
        var truncated = 0
        val tunnels = entries.groupBy { it.key.tunnelId }.toSortedMap().map { (tunnelId, inTunnel) ->
            val subjects = inTunnel.groupBy { it.key.subject }.toSortedMap().mapNotNull { (subject, inSubject) ->
                if (shown >= maxSubjects) {
                    truncated++
                    return@mapNotNull null
                }
                shown++
                val sorted = inSubject.sortedBy { it.key.key }
                SubjectDiff(subject, DiffCounts.of(sorted), sorted.take(perSubject), (sorted.size - perSubject).coerceAtLeast(0))
            }
            TunnelDiff(tunnelId, DiffCounts.of(inTunnel), subjects)
        }
        return DiffReport(DiffCounts.of(entries), tunnels, truncated)
    }
}

/** `tunnels-YYYYMMDD-HHMMSSZ.fwx`, the time in UTC (container spec, section 5.3). The name says nothing about what is inside. */
fun exportFileName(at: Instant = Instant.now()): String =
    "tunnels-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC).format(at)}.fwx"
