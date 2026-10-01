package io.github.stronghorse44.tunnels.surroundings

import io.github.stronghorse44.tunnels.ble.FollowingHeuristic
import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.FollowingProgress
import io.github.stronghorse44.tunnels.ble.IdentityFacts
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.ThreatSummary
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.Observation
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Plain-Kotlin formatting shared by the panel and the monitor notification. No Android here, so it is unit-tested. */
object SurroundingsFormat {
    /** `90000` → `1m 30s`, `3_600_000` → `1h 0m`. */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    /**
     * One line for the notification and the monitor card, in the tracker section's vocabulary:
     * "12 scans · 3 identities since start: 1 near its owner, 2 separated · LTE".
     */
    fun monitorLine(windows: Int, byState: Map<TrackerState, Int>, cellType: String?): String = buildString {
        append(windows).append(if (windows == 1) " scan" else " scans")
        append(" · ").append(ThreatSummary.monitorLine(byState))
        if (cellType != null) append(" · ").append(cellType)
    }

    fun stateLabel(state: TrackerState): String = when (state) {
        TrackerState.SEPARATED -> "away from owner"
        TrackerState.WITH_OWNER -> "near its owner"
        TrackerState.UNKNOWN -> "state unknown"
    }

    /** "today 14:32", "yesterday 09:05", "3 Oct 14:32". */
    fun timeOf(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val n = Instant.ofEpochMilli(now).atZone(zone)
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            n.toLocalDate() -> "today $hm"
            n.toLocalDate().minusDays(1) -> "yesterday $hm"
            else -> "${t.dayOfMonth} ${t.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $hm"
        }
    }

    /** "near (-55 dBm)" from a last reading, or "—" without one. */
    fun signal(rssi: Int?): String = rssi?.let { "${io.github.stronghorse44.tunnels.ble.Proximity.of(it).label} ($it dBm)" } ?: "—"

    /** `tracker:findmy:deadbeef` → "Apple Find My · deadbeef"; `tracker:tile` → "Tile". */
    fun trackerTitle(subject: String): String {
        val (type, key) = SurroundingsKeys.parseTrackerSubject(subject) ?: return subject
        return if (key == null) type.label else "${type.label} · $key"
    }

    /** The per-type and per-device subjects of a scan, grouped: type → (facts, devices → facts). */
    fun trackerCards(observations: List<Observation>): List<TrackerCard> {
        val bySubject = observations.groupBy { it.subject }
        val types = bySubject.keys.mapNotNull { s -> SurroundingsKeys.parseTrackerSubject(s)?.takeIf { it.second == null }?.first }
        return types.sortedBy { it.slug }.map { type ->
            val typeSubject = SurroundingsKeys.typeSubject(type)
            val devices = bySubject.filterKeys { s -> SurroundingsKeys.parseTrackerSubject(s)?.let { it.first == type && it.second != null } == true }
                .map { (s, obs) -> DeviceRow(s, SurroundingsKeys.parseTrackerSubject(s)!!.second!!, obs.associate { it.key to it.value }) }
                .sortedWith(compareByDescending<DeviceRow> { it.facts[SurroundingsKeys.SEEN_SESSIONS]?.toIntOrNull() ?: 0 }.thenBy { it.key })
            TrackerCard(type, typeSubject, bySubject[typeSubject].orEmpty().associate { it.key to it.value }, devices)
        }
    }

    data class DeviceRow(val subject: String, val key: String, val facts: Map<String, String>) {
        fun identity(type: TrackerType): IdentityFacts = IdentityFacts.from(type, key, facts)
    }

    data class TrackerCard(val type: TrackerType, val subject: String, val facts: Map<String, String>, val devices: List<DeviceRow>) {
        val identities: List<IdentityFacts> get() = devices.map { it.identity(type) }
        val devicesCount: Int get() = facts[SurroundingsKeys.DEVICES]?.toIntOrNull() ?: devices.size
        val sessions: Int get() = facts[SurroundingsKeys.SEEN_SESSIONS]?.toIntOrNull() ?: 0
        val spanMinutes: Long get() = facts[SurroundingsKeys.SEEN_SPAN]?.toLongOrNull() ?: 0L
        val state: TrackerState get() = TrackerState.bySlug(facts[SurroundingsKeys.STATE])
        val muted: Boolean get() = facts[SurroundingsKeys.MUTED] == "true"
        val seenToday: Boolean get() = facts[SurroundingsKeys.SEEN_LAST_DAY] == "true"

        /** The family's standing against the following threshold, from the same facts the rule reads. */
        val progress: FollowingProgress get() = FollowingProgress(sessions, spanMinutes)

        /** Sessions and minutes in which the family reported itself separated: what the CRITICAL rule judges. */
        val separatedSessions: Int get() = facts[SurroundingsKeys.SEEN_SESSIONS_SEPARATED]?.toIntOrNull() ?: 0
        val separatedMinutes: Long get() = facts[SurroundingsKeys.SEEN_SPAN_SEPARATED]?.toLongOrNull() ?: 0L

        val level: FollowingLevel get() = if (muted) FollowingLevel.NONE else FollowingHeuristic.assess(type, sessions, spanMinutes, separatedSessions, separatedMinutes)
    }
}
