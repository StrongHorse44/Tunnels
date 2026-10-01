package io.github.stronghorse44.tunnels.surroundings

import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.Observation

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

    /** One line for the notification: scans, trackers, cell. */
    fun monitorLine(windows: Int, trackerKeys: Int, cellType: String?): String = buildString {
        append(windows).append(if (windows == 1) " scan" else " scans")
        append(" · ").append(trackerKeys).append(if (trackerKeys == 1) " tracker" else " trackers")
        if (cellType != null) append(" · ").append(cellType)
    }

    fun stateLabel(state: TrackerState): String = when (state) {
        TrackerState.SEPARATED -> "away from owner"
        TrackerState.WITH_OWNER -> "near its owner"
        TrackerState.UNKNOWN -> "state unknown"
    }

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

    data class DeviceRow(val subject: String, val key: String, val facts: Map<String, String>)

    data class TrackerCard(val type: TrackerType, val subject: String, val facts: Map<String, String>, val devices: List<DeviceRow>)
}
