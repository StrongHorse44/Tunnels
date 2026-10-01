package io.github.stronghorse44.tunnels.surroundings

import io.github.stronghorse44.tunnels.ble.FamilyFacts
import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.IdentityFacts
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.ThreatSummary
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.ble.TrackerVerdict
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
     * "12 scans · 3 identities since start: 1 near its owner, 2 separated · 1 identity close to following · LTE".
     */
    fun monitorLine(windows: Int, byState: Map<TrackerState, Int>, cellType: String?, close: Int = 0, following: Int = 0): String = buildString {
        append(windows).append(if (windows == 1) " scan" else " scans")
        append(" · ").append(ThreatSummary.monitorLine(byState, close, following))
        if (cellType != null) append(" · ").append(cellType)
    }

    fun stateLabel(state: TrackerState): String = TrackerVerdict.stateLabel(state)

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

    /**
     * The line after an unmute. Unmuting one identity that was muted through a legacy family mute lifts the
     * family mute too, and says so: "Unmuted Apple Find My · deadbeef and the whole Apple Find My family mute."
     */
    fun unmuteMessage(subjects: List<String>): String {
        val parsed = subjects.mapNotNull { s -> SurroundingsKeys.parseTrackerSubject(s)?.let { s to it } }
        val identity = parsed.firstOrNull { it.second.second != null }
        val family = parsed.firstOrNull { it.second.second == null }
        val what = when {
            identity != null && family != null -> "${trackerTitle(identity.first)} and the whole ${family.second.first.label} family mute"
            identity != null -> trackerTitle(identity.first)
            family != null -> "the whole ${family.second.first.label} family mute"
            else -> subjects.firstOrNull() ?: "nothing"
        }
        return "Unmuted $what. It is judged again from this scan on."
    }

    /** The per-type and per-device subjects of a scan, grouped: type → (facts, devices → facts). */
    fun trackerCards(observations: List<Observation>): List<TrackerCard> {
        val bySubject = observations.groupBy { it.subject }
        val types = bySubject.keys.mapNotNull { s -> SurroundingsKeys.parseTrackerSubject(s)?.takeIf { it.second == null }?.first }
        return types.sortedBy { it.slug }.map { type ->
            val typeSubject = SurroundingsKeys.typeSubject(type)
            val devices = bySubject.filterKeys { s -> SurroundingsKeys.parseTrackerSubject(s)?.let { it.first == type && it.second != null } == true }
                .map { (s, obs) -> DeviceRow(s, SurroundingsKeys.parseTrackerSubject(s)!!.second!!, obs.associate { it.key to it.value }) }
                .sortedWith(compareBy(IdentityFacts.order) { it.identity(type) })
            TrackerCard(type, typeSubject, bySubject[typeSubject].orEmpty().associate { it.key to it.value }, devices)
        }
    }

    data class DeviceRow(val subject: String, val key: String, val facts: Map<String, String>) {
        fun identity(type: TrackerType): IdentityFacts = IdentityFacts.from(type, key, facts)
    }

    /**
     * One family card: a summary of its identities (closest to following first). Each identity is judged on its
     * own; the family itself is never "following". [muted] is a family-level mute from before v3.
     */
    data class TrackerCard(val type: TrackerType, val subject: String, val facts: Map<String, String>, val devices: List<DeviceRow>) {
        val identities: List<IdentityFacts> get() = devices.map { it.identity(type) }
        val family: FamilyFacts get() = FamilyFacts.from(type, facts, identities)
        val devicesCount: Int get() = facts[SurroundingsKeys.DEVICES]?.toIntOrNull() ?: devices.size
        val sessions: Int get() = facts[SurroundingsKeys.SEEN_SESSIONS]?.toIntOrNull() ?: 0
        val spanMinutes: Long get() = facts[SurroundingsKeys.SEEN_SPAN]?.toLongOrNull() ?: 0L
        val muted: Boolean get() = facts[SurroundingsKeys.MUTED] == "true"
        val seenToday: Boolean get() = facts[SurroundingsKeys.SEEN_LAST_DAY] == "true"

        /** The worst level among the family's listed identities: tints the card, never a finding of its own. */
        val worstLevel: FollowingLevel get() = identities.maxOfOrNull { it.level } ?: FollowingLevel.NONE
    }
}
