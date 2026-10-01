package io.github.stronghorse44.tunnels.ble

import java.security.MessageDigest

/** Pseudonymous device keys: nothing stored can be turned back into a Bluetooth address. */
object DeviceKey {
    const val LENGTH = 8

    /** First eight hex characters of SHA-256 over the address and the id that recognised it. */
    fun of(address: String, idSource: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$address|$idSource".toByteArray(Charsets.UTF_8))
        return digest.take(LENGTH / 2).joinToString("") { "%02x".format(it) }
    }
}

/**
 * One tracker seen in one scan session: the unit the events table keeps for 30 days. A session is one
 * BLE scan window (a manual scan or one pass of the background monitor); a device appears at most once
 * per session, with the advertisements it sent folded into [count] and [rssi].
 */
data class SightingRecord(
    val session: String,
    val at: Long,
    val type: TrackerType,
    val key: String,
    val state: TrackerState,
    val rssi: Int,
    val count: Int = 1,
    val battery: String? = null,
    /** Device kind slug from the advertisement (Apple only, see [AppleFindMyFrame]). */
    val kind: String? = null,
) {
    val subject: String get() = SurroundingsKeys.trackerSubject(type, key)

    /** The events-table summary: `session=…;at=…;state=…;rssi=…;count=…`. */
    fun encode(): String = buildString {
        append("session=").append(session)
        append(";at=").append(at)
        append(";state=").append(state.slug)
        append(";rssi=").append(rssi)
        append(";count=").append(count)
        battery?.let { append(";battery=").append(it) }
        kind?.let { append(";kind=").append(it) }
    }

    companion object {
        /** Reads a record back from an events row; null for a row this version does not understand. */
        fun parse(subject: String, summary: String): SightingRecord? {
            val (type, key) = SurroundingsKeys.parseTrackerSubject(subject) ?: return null
            if (key == null) return null
            val fields = summary.split(';').mapNotNull { f ->
                val i = f.indexOf('=')
                if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
            }.toMap()
            val session = fields["session"]?.takeIf { it.isNotEmpty() } ?: return null
            val at = fields["at"]?.toLongOrNull() ?: return null
            return SightingRecord(
                session = session,
                at = at,
                type = type,
                key = key,
                state = TrackerState.bySlug(fields["state"]),
                rssi = fields["rssi"]?.toIntOrNull() ?: 0,
                count = fields["count"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                battery = fields["battery"],
                kind = fields["kind"],
            )
        }
    }
}

/**
 * Folds the advertisements one device sent within a scan window into one [SightingRecord]: signal is
 * averaged, "separated" beats everything else (a tag that said it is away is away), the latest battery
 * and device kind win. Shared by the scan window and find-it mode; thread-safe since scan callbacks race.
 */
class SightingFold(val type: TrackerType) {
    var state: TrackerState = TrackerState.UNKNOWN
        private set
    var battery: String? = null
        private set
    var kind: String? = null
        private set
    var count: Int = 0
        private set
    private var rssiSum = 0L

    @Synchronized
    fun add(match: TrackerMatch, rssi: Int) {
        rssiSum += rssi
        count++
        if (match.state == TrackerState.SEPARATED || state == TrackerState.UNKNOWN) state = match.state
        match.battery?.let { battery = it }
        match.kind?.let { kind = it }
    }

    @Synchronized
    fun record(session: String, at: Long, key: String): SightingRecord = SightingRecord(
        session = session,
        at = at,
        type = type,
        key = key,
        state = state,
        rssi = if (count == 0) 0 else (rssiSum / count).toInt(),
        count = count,
        battery = battery,
        kind = kind,
    )
}

/** Everything known about one pseudonymous device across sessions. */
data class DeviceSighting(
    val type: TrackerType,
    val key: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightings: Int,
    val sessions: Int,
    val rssiAvg: Int,
    /** State from the most recent session. */
    val state: TrackerState,
    val battery: String? = null,
    /** Signal in the most recent session. */
    val rssiLast: Int = rssiAvg,
    /** The session the device was last seen in. */
    val lastSession: String = "",
    val kind: String? = null,
    /** Sessions in which this identity reported itself separated from its owner: what the CRITICAL rule judges. */
    val sessionsSeparated: Int = 0,
    val firstSeparated: Long? = null,
    val lastSeparated: Long? = null,
) {
    val spanMinutes: Long get() = (lastSeen - firstSeen) / 60_000
    val spanSeparatedMinutes: Long get() = if (firstSeparated != null && lastSeparated != null) (lastSeparated - firstSeparated) / 60_000 else 0

    /** This identity's own assessment: the following rule is judged per identity, see [FollowingHeuristic]. */
    val level: FollowingLevel get() = FollowingHeuristic.assess(type, sessions, spanMinutes, sessionsSeparated, spanSeparatedMinutes)
}

/**
 * Everything known about one tracker family: a summary for the family card only. Strangers' tags of the
 * same family add up here, so nothing is ever judged as "following" on this level (see [FollowingHeuristic]).
 */
data class TypeSighting(
    val type: TrackerType,
    val devices: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightings: Int,
    val sessions: Int,
    val rssiAvg: Int,
    /** Sessions in which at least one device of the type reported itself separated from its owner. */
    val sessionsSeparated: Int,
    val firstSeparated: Long?,
    val lastSeparated: Long?,
    /** Devices whose latest state was "near its owner"; with [devices] this tells a family of benign tags from unknowns. */
    val devicesWithOwner: Int = 0,
) {
    val spanMinutes: Long get() = (lastSeen - firstSeen) / 60_000
    val spanSeparatedMinutes: Long get() = if (firstSeparated != null && lastSeparated != null) (lastSeparated - firstSeparated) / 60_000 else 0

    /**
     * Separated as soon as any device of the family said so in any session; near its owner when every
     * device last said so; unknown otherwise (a family that never states it, or a mix).
     */
    val state: TrackerState get() = when {
        sessionsSeparated > 0 -> TrackerState.SEPARATED
        devices > 0 && devicesWithOwner == devices -> TrackerState.WITH_OWNER
        else -> TrackerState.UNKNOWN
    }
}

data class SightingAggregate(
    val devices: Map<String, DeviceSighting>,
    val types: Map<TrackerType, TypeSighting>,
) {
    companion object {
        val EMPTY = SightingAggregate(emptyMap(), emptyMap())
    }
}

/** Folds sighting records (this scan's and the last 30 days' from the events table) into per-device and per-type facts. */
object SightingAggregator {
    fun aggregate(records: Collection<SightingRecord>): SightingAggregate {
        if (records.isEmpty()) return SightingAggregate.EMPTY
        val devices = records.groupBy { it.key }.mapValues { (key, rs) ->
            val sorted = rs.sortedBy { it.at }
            val latest = sorted.last()
            val separated = rs.filter { it.state == TrackerState.SEPARATED }
            DeviceSighting(
                type = latest.type,
                key = key,
                firstSeen = sorted.first().at,
                lastSeen = latest.at,
                sightings = rs.sumOf { it.count },
                sessions = rs.map { it.session }.toSet().size,
                rssiAvg = weightedRssi(rs),
                state = latest.state,
                battery = sorted.lastOrNull { it.battery != null }?.battery,
                rssiLast = latest.rssi,
                lastSession = latest.session,
                kind = sorted.lastOrNull { it.kind != null }?.kind,
                sessionsSeparated = separated.map { it.session }.toSet().size,
                firstSeparated = separated.minOfOrNull { it.at },
                lastSeparated = separated.maxOfOrNull { it.at },
            )
        }
        val types = records.groupBy { it.type }.mapValues { (type, rs) ->
            val separated = rs.filter { it.state == TrackerState.SEPARATED }
            val withOwner = devices.values.count { it.type == type && it.state == TrackerState.WITH_OWNER }
            TypeSighting(
                type = type,
                devices = rs.map { it.key }.toSet().size,
                firstSeen = rs.minOf { it.at },
                lastSeen = rs.maxOf { it.at },
                sightings = rs.sumOf { it.count },
                sessions = rs.map { it.session }.toSet().size,
                rssiAvg = weightedRssi(rs),
                sessionsSeparated = separated.map { it.session }.toSet().size,
                firstSeparated = separated.minOfOrNull { it.at },
                lastSeparated = separated.maxOfOrNull { it.at },
                devicesWithOwner = withOwner,
            )
        }
        return SightingAggregate(devices, types)
    }

    private fun weightedRssi(rs: Collection<SightingRecord>): Int {
        val weight = rs.sumOf { it.count }
        if (weight == 0) return 0
        return Math.round(rs.sumOf { it.rssi.toDouble() * it.count } / weight).toInt()
    }
}

enum class FollowingLevel { NONE, WARN, CRITICAL }

/**
 * Whether one tracker identity (one pseudonymous device key) is following the user. A key that keeps
 * turning up is suspicious once it was seen in three separate scan sessions at least half an hour apart;
 * an Apple tag that says it is away from its owner for over an hour across three sessions is the
 * strongest signal a stalking tag gives.
 *
 * Judged per identity, never per family: in a busy place strangers' AirPods and AirTags of one family
 * turn up in every scan, so family-level counts cross any threshold although no single device followed
 * anyone. Per-key judgement also aims at the right tags. Apple Find My devices rotate their address, and
 * with it our key, often while near their owner (about every quarter of an hour, by public research)
 * but rarely once separated from it (about once a day), so a near-owner key seldom lives long enough to
 * reach the threshold while a separated, possibly planted, tag keeps one key for hours. Rotation periods
 * of the other families are not verified on-device; a family that rotates faster than the threshold is
 * only caught while one key is stable. The UI never quotes rotation times.
 */
object FollowingHeuristic {
    const val MIN_SESSIONS = 3
    const val MIN_SPAN_MINUTES = 30L
    const val CRITICAL_SPAN_MINUTES = 60L

    /** The WARN threshold in words, shared by the finding text, the verdict and the guide so they never drift apart. */
    val thresholdText: String get() = "$MIN_SESSIONS separate scans spread over at least $MIN_SPAN_MINUTES minutes"

    /** Assesses one identity from its own counts: all its sessions and minutes, and its separated-only ones. */
    fun assess(type: TrackerType, sessions: Int, spanMinutes: Long, sessionsSeparated: Int, spanSeparatedMinutes: Long): FollowingLevel = when {
        type == TrackerType.APPLE_FINDMY && sessionsSeparated >= MIN_SESSIONS && spanSeparatedMinutes >= CRITICAL_SPAN_MINUTES -> FollowingLevel.CRITICAL
        sessions >= MIN_SESSIONS && spanMinutes >= MIN_SPAN_MINUTES -> FollowingLevel.WARN
        else -> FollowingLevel.NONE
    }

    fun assess(d: DeviceSighting): FollowingLevel = d.level

    /**
     * How close one identity is to following, for ordering: its level first, then its progress toward the
     * WARN threshold (scans and minutes, each capped at the threshold). Higher is closer.
     */
    fun closeness(level: FollowingLevel, sessions: Int, spanMinutes: Long): Double =
        level.ordinal * 10.0 +
            sessions.coerceIn(0, MIN_SESSIONS).toDouble() / MIN_SESSIONS +
            spanMinutes.coerceIn(0L, MIN_SPAN_MINUTES).toDouble() / MIN_SPAN_MINUTES

    /** An identity seen in a single scan is never "close to following": every passer-by is. */
    fun isCandidate(sessions: Int): Boolean = sessions > 1

    /** Closest to following first, then the most recently seen. */
    val deviceOrder: Comparator<DeviceSighting> =
        compareByDescending<DeviceSighting> { closeness(it.level, it.sessions, it.spanMinutes) }.thenByDescending { it.lastSeen }.thenBy { it.key }
}
