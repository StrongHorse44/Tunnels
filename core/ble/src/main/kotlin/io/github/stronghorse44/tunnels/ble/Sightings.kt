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
    /** The keys heard in each session, for [RotatingTagHeuristic]; summaries of the stored rows, nothing new. */
    val sessionKeys: List<SessionKeys> = emptyList(),
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
                sessionKeys = rs.groupBy { it.session }.map { (session, srs) -> SessionKeys(session, srs.minOf { it.at }, srs.map { it.key }.toSet()) },
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

    /**
     * "Close to following": real progress toward the threshold, one scan short of it and at least half its
     * span. The background monitor scans every couple of minutes, so "seen twice" alone would count most of
     * a crowd; two scans four minutes apart are not close. Identities over the threshold are close too.
     * Shared by the family card, the monitor notification and the summary tint.
     */
    fun isClose(sessions: Int, spanMinutes: Long): Boolean =
        sessions >= MIN_SESSIONS - 1 && spanMinutes >= MIN_SPAN_MINUTES / 2

    /**
     * Following findings are raised only for identities seen within this many days. Older ones stay listed
     * as history: a key seen once a week ago is no news, and a tag that changes its identity daily would
     * otherwise leave a fresh finding behind for every day of the 30-day history.
     */
    const val RECENT_DAYS = 7
    private const val DAY_MS = 24 * 60 * 60_000L

    fun isRecent(lastSeen: Long, now: Long): Boolean = now - lastSeen <= RECENT_DAYS * DAY_MS

    /** The level the rule acts on: the identity's own assessment, but only while it is recent. */
    fun level(d: DeviceSighting, now: Long): FollowingLevel = if (isRecent(d.lastSeen, now)) d.level else FollowingLevel.NONE

    /**
     * Listing order: unmuted before muted, then closest to following (recent levels only), then the most
     * recently seen. [muted] says whether an identity is muted, directly or through a legacy family mute.
     */
    fun deviceOrder(now: Long, muted: (DeviceSighting) -> Boolean): Comparator<DeviceSighting> =
        compareBy<DeviceSighting> { muted(it) }
            .thenByDescending { closeness(level(it, now), it.sessions, it.spanMinutes) }
            .thenByDescending { it.lastSeen }
            .thenBy { it.key }
}

/**
 * A weak, family-level hint for non-Apple tags. The per-identity rule relies on a tag keeping one key for
 * a while; how often Tile, Samsung, Chipolo and other tags change their identity is not verified on-device.
 * A lone tag that changes its identity looks like a few keys per scan (at most [MAX_KEYS_PER_SESSION]) of
 * one family recurring across [FollowingHeuristic.MIN_SESSIONS] scans over [FollowingHeuristic.MIN_SPAN_MINUTES]
 * minutes. A crowd shows many keys per scan and never matches; a different stranger's tag in each scan does
 * match, which is why this only ever is a NOTICE.
 */
object RotatingTagHeuristic {
    const val MAX_KEYS_PER_SESSION = 2

    data class Pattern(val sessions: Int, val spanMinutes: Long, val keys: Int)

    /**
     * [sessions] are the family's scan sessions (unmuted keys only). Null unless every one of them had at most
     * [MAX_KEYS_PER_SESSION] keys, the family recurred across enough sessions and minutes, it changed key at least
     * once, and it was seen within [FollowingHeuristic.RECENT_DAYS] days.
     */
    fun assess(type: TrackerType, sessions: List<SessionKeys>, now: Long): Pattern? {
        if (type == TrackerType.APPLE_FINDMY || sessions.isEmpty()) return null
        if (sessions.any { it.keys.size > MAX_KEYS_PER_SESSION }) return null
        val first = sessions.minOf { it.at }
        val last = sessions.maxOf { it.at }
        val span = (last - first) / 60_000
        val keys = sessions.flatMap { it.keys }.toSet().size
        if (sessions.size < FollowingHeuristic.MIN_SESSIONS || span < FollowingHeuristic.MIN_SPAN_MINUTES || keys < 2) return null
        if (!FollowingHeuristic.isRecent(last, now)) return null
        return Pattern(sessions.size, span, keys)
    }
}

/** The keys of one family heard in one scan session. */
data class SessionKeys(val session: String, val at: Long, val keys: Set<String>)
