package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation

/** What the pin plan needs to know about one stored snapshot. [tag] and [state] are null when it carries no census. */
data class CensusSnap(
    val id: Long,
    val takenAt: Long,
    val pinned: Boolean,
    /** True when home_network is the only tunnel in the snapshot. */
    val homenetOnly: Boolean,
    val tag: String?,
    val state: String?,
)

/** The pin changes to make: pin [pin] (null for none) and unpin every id in [unpin]. */
data class PinPlan(val pin: Long?, val unpin: List<Long>)

/**
 * Keeps one snapshot per confirmed network pinned, so the list survives the twelve-snapshot retention. Rule 4 allows
 * pinned snapshots; this keeps that to one per network and only ever a snapshot that holds nothing but Home network,
 * so no other tunnel's data is kept past retention. Plain JVM, unit-tested.
 */
object CensusPins {
    fun snapOf(id: Long, takenAt: Long, pinned: Boolean, homenetOnly: Boolean, observations: List<Observation>): CensusSnap {
        val summary = DeviceCensus.summaryOf(observations)
        return CensusSnap(
            id, takenAt, pinned, homenetOnly,
            tag = LanKeys.value(summary, LanKeys.SCAN_NETWORK),
            state = LanKeys.value(summary, LanKeys.CENSUS_STATE),
        )
    }

    /**
     * The target is the newest (taken_at, then id) Home-network-only snapshot of [tag] whose list is `set` and that was
     * taken after [lastReset]; it is pinned if it is not already. Every other pinned Home-network-only census snapshot
     * of [tag] is unpinned, including one the user pinned by hand. A snapshot with no census, one of another network
     * and a mixed one (other tunnels' data in it) are never touched.
     */
    fun plan(snaps: List<CensusSnap>, tag: String, lastReset: Long?): PinPlan {
        val mine = snaps.filter { it.tag == tag && it.state != null && it.homenetOnly }
        val target = mine
            .filter { it.state == DeviceCensus.STATE_SET && (lastReset == null || it.takenAt > lastReset) }
            .maxWithOrNull(compareBy<CensusSnap> { it.takenAt }.thenBy { it.id })
        val unpin = mine.filter { it.pinned && it.id != target?.id }.map { it.id }
        return PinPlan(pin = target?.takeIf { !it.pinned }?.id, unpin = unpin)
    }
}
