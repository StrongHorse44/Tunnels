package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.model.Observation

/** Surroundings tunnel: subjects, keys and the pure builders from aggregates to [Observation]s. */
object SurroundingsKeys {
    const val TUNNEL_ID = "surroundings"

    // Events-table kinds (30-day retention).
    const val EVENT_SIGHTING = "ble.sighting"
    const val EVENT_CELL = "cell.check"
    const val EVENT_WIFI = "wifi.check"
    const val EVENT_MUTE = "tracker.mute"
    const val EVENT_UNMUTE = "tracker.unmute"
    /**
     * The events stream (the `tunnel_id` column) mute and unmute rows are written to since v3, apart from the
     * sightings, so a bounded read of the few mute rows is never crowded out by thousands of sighting rows.
     * The store has no kind-filtered query yet; this separate stream is the workaround. Rows written before
     * v3 sit in the [TUNNEL_ID] stream and are still read from there.
     */
    const val MUTE_LEDGER = "surroundings.mutes"
    /** Mute rows read from [MUTE_LEDGER]: one per user tap, so a few hundred cover 30 days with room to spare. */
    const val MAX_MUTE_ROWS = 2_000
    const val EVENT_MONITOR = "monitor"
    /** One find-it session: `minutes=3;closest=-48;type=findmy`. Nothing else of the session is kept. */
    const val EVENT_FINDIT = "findit.session"
    /** One DULT query of a tag: a one-line summary such as "Apple AirTag, battery full, sound played". */
    const val EVENT_DULT = "dult.query"

    const val BLE_SUMMARY = "ble:summary"
    const val CELL_SUMMARY = "cell:summary"
    const val WIFI_SUMMARY = "wifi:summary"

    const val BLE_AVAILABLE = "ble:available"
    const val DEVICES_TOTAL = "devices:total"
    const val TRACKERS_TOTAL = "trackers:total"
    const val TRACKERS_BY_TYPE = "trackers:byType"
    const val TRACKERS_UNLISTED = "trackers:unlisted"
    /** Scan sessions of the last 30 days in which at least one tracker was seen. */
    const val SESSIONS_30D = "sessions:withTrackers:30d"
    const val WIFI_TWINS_RECORDED = "wifi:twinsRecorded:30d"

    const val SEEN_COUNT = "seen:count"
    const val SEEN_SESSIONS = "seen:sessions"
    const val SEEN_SPAN = "seen:spanMinutes"
    /** Per-identity subjects only (since v3): sessions and minutes in which that identity said it was away from its owner. */
    const val SEEN_SESSIONS_SEPARATED = "seen:sessions:separated"
    const val SEEN_SPAN_SEPARATED = "seen:spanMinutes:separated"
    const val SEEN_LAST_DAY = "seen:lastDay"
    /** Epoch millis of the first and last sighting of a device (per-device subjects only). */
    const val SEEN_FIRST = "seen:first"
    const val SEEN_LAST = "seen:last"
    /** True when the identity was seen within [FollowingHeuristic.RECENT_DAYS] days: only then can it carry a following finding. */
    const val SEEN_RECENT = "seen:recent"
    /** True when the device was in the scan that produced this snapshot, not only in the 30-day history. */
    const val SEEN_THIS_SCAN = "seen:thisScan"
    /**
     * Per-identity subjects: the longest run of consecutive place numbers the identity was seen at (2 or more: seen
     * on both sides of a move), how many of its sessions knew the phone's place, and the [Movement] that makes.
     */
    const val SEEN_PLACE_RUN = "seen:placeRun"
    const val SEEN_PLACED_SESSIONS = "seen:placedSessions"
    const val MOVEMENT = "movement"
    /** Summary: whether this scan knew the phone's place ([AVAILABLE_YES], [AVAILABLE_NO_FIX], [AVAILABLE_LOCATION_OFF], ...). */
    const val PLACE_AVAILABLE = "place:available"
    const val RSSI_AVG = "rssi:avg"
    /** Signal in the device's most recent session. */
    const val RSSI_LAST = "rssi:last"
    const val STATE = "state"
    const val BATTERY = "battery"
    /** Device kind slug (Apple only): airtag, accessory, airpods, apple-device. */
    const val KIND = "kind"
    const val DEVICES = "devices"
    /** Family subjects: how many of the family's identities last reported each state (all identities, listed or not). */
    const val DEVICES_WITH_OWNER = "devices:withOwner"
    const val DEVICES_SEPARATED = "devices:separated"
    const val DEVICES_UNKNOWN = "devices:unknown"
    /**
     * Family subjects: the unmuted identity of the family closest to following ([FollowingHeuristic.closeness]),
     * or [NONE] when no recent unmuted identity is close ([FollowingHeuristic.isClose]); with its own scans and minutes.
     */
    const val CLOSEST_KEY = "following:closest"
    const val CLOSEST_SESSIONS = "following:closest:sessions"
    const val CLOSEST_SPAN = "following:closest:spanMinutes"
    /** Family subjects: unmuted identities at WARN or above. */
    const val FOLLOWING_COUNT = "following:identities"
    /**
     * Family subjects, non-Apple only and only when [RotatingTagHeuristic] matches: the sessions, minutes and
     * distinct keys of what may be one tag changing its identity.
     */
    const val ROTATING_SESSIONS = "rotating:sessions"
    const val ROTATING_SPAN = "rotating:spanMinutes"
    const val ROTATING_KEYS = "rotating:keys"
    const val NONE = "none"
    const val CONFIDENCE = "confidence"
    const val MUTED = "muted"

    const val WIFI_AVAILABLE = "wifi:available"
    const val WIFI_NETWORKS = "wifi:networks"
    const val WIFI_SECURITY = "wifi:security"
    const val WIFI_BSSIDS = "wifi:bssids"
    const val WIFI_BANDS = "wifi:bands"
    const val WIFI_CURRENT = "wifi:current"
    const val WIFI_TWIN = "wifi:twinSuspect"

    const val CELL_AVAILABLE = "cell:available"
    const val CELL_TYPE = "cell:registeredType"
    const val CELL_OPERATOR = "cell:operator"
    const val CELL_NEIGHBOURS = "cell:neighbours"
    const val CELL_CHANGED = "cell:changedSinceLast"
    const val CELL_DOWNGRADES_RECORDED = "cell:downgradesRecorded"

    // The cell logbook (subject [CELL_SUMMARY]). Summaries only: no place hash, cell token or code is an observation.
    const val LOG_STATE = "log:state"
    const val LOG_VERDICT = "log:verdict"
    const val LOG_SIGNALS = "log:signals"
    /** The first 8 hex of the unfamiliar cell's keyed token: 32 bits of a hash that only this phone can compute. */
    const val LOG_TOWER = "log:tower"
    const val LOG_PLACES = "log:places"
    const val LOG_PLACE_SCANS = "log:placeScans"
    const val LOG_PLACE_CELLS = "log:placeCells"

    /** `log:state` values. The logbook is off until the user starts it (no row). */
    const val LOG_OFF = "off"
    const val LOG_ON = "on"
    const val LOG_NO_PLACE = "no-place"
    const val LOG_NO_CELL_ID = "no-cell-id"
    const val LOG_RESTARTED = "restarted"
    const val LOG_UNREADABLE = "unreadable"
    const val LOG_FAILED = "failed"

    /** Availability values shared by the three radios. */
    const val AVAILABLE_YES = "yes"
    const val AVAILABLE_NO_ADAPTER = "no adapter"
    const val AVAILABLE_OFF = "off"
    const val AVAILABLE_NO_PERMISSION = "no permission"
    /** The device's location toggle is off: Android then hands out no Wi-Fi scan results and no cell list. */
    const val AVAILABLE_LOCATION_OFF = "location off"
    /** No position fix accurate to [PlaceGrid.MAX_ACCURACY_METERS] arrived in time (indoors, no network location). */
    const val AVAILABLE_NO_FIX = "no fix"
    const val AVAILABLE_FAILED = "failed"

    /** Devices listed one by one; more than this many are counted under [TRACKERS_UNLISTED]. */
    const val MAX_LISTED_DEVICES = 40
    /** Network names listed one by one; more are counted in [WIFI_SUMMARY]. */
    const val MAX_LISTED_NETWORKS = 60
    const val MUTE_DAYS = 30
    const val DAY_MS = 24 * 60 * 60_000L

    private const val TRACKER_PREFIX = "tracker:"

    fun typeSubject(type: TrackerType): String = TRACKER_PREFIX + type.slug

    /** One mute or unmute row: [kind] is [EVENT_MUTE] or [EVENT_UNMUTE], [at] epoch millis. */
    data class MuteRow(val kind: String, val subject: String, val at: Long)

    /**
     * Subjects muted right now: the latest mute/unmute row per subject is a mute, and it is younger
     * than [MUTE_DAYS]. An unmute cancels every earlier mute of that subject.
     */
    fun activeMutes(rows: List<MuteRow>, now: Long): Set<String> =
        rows.filter { it.kind == EVENT_MUTE || it.kind == EVENT_UNMUTE }
            .groupBy { it.subject }
            .mapNotNull { (subject, list) ->
                val last = list.maxBy { it.at }
                subject.takeIf { last.kind == EVENT_MUTE && last.at >= now - MUTE_DAYS * DAY_MS }
            }
            .toSet()

    fun trackerSubject(type: TrackerType, key: String): String = "$TRACKER_PREFIX${type.slug}:$key"

    /** `tracker:<type>` → (type, null); `tracker:<type>:<key>` → (type, key); anything else → null. */
    fun parseTrackerSubject(subject: String): Pair<TrackerType, String?>? {
        if (!subject.startsWith(TRACKER_PREFIX)) return null
        val rest = subject.removePrefix(TRACKER_PREFIX)
        val colon = rest.indexOf(':')
        val type = TrackerType.bySlug(if (colon < 0) rest else rest.substring(0, colon)) ?: return null
        val key = if (colon < 0) null else rest.substring(colon + 1).takeIf { it.isNotEmpty() } ?: return null
        return type to key
    }

    fun isTypeSubject(subject: String): Boolean = parseTrackerSubject(subject)?.second == null && subject.startsWith(TRACKER_PREFIX)

    fun isWifiSubject(obs: List<Observation>): Boolean = obs.any { it.key == WIFI_SECURITY }

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    /** The find-it session summary row: how long the user searched and the strongest signal, nothing else. */
    fun findItSummary(type: TrackerType, minutes: Long, closestDbm: Int?): String =
        "type=${type.slug};minutes=$minutes" + (closestDbm?.let { ";closest=$it" } ?: "")

    /**
     * Observations for the BLE side. [muted] holds subjects the user marked as known trackers: identity
     * subjects, and family subjects muted before v3 (still honoured until they expire, never offered again);
     * their observations stay but carry `muted=true` so the rules skip them.
     * [currentSession] is the scan window that produced this snapshot: devices last seen in it are marked
     * [SEEN_THIS_SCAN], the rest come from the 30-day history only. [placeAvailable] says whether this scan knew
     * the phone's place.
     */
    fun bleObservations(
        aggregate: SightingAggregate,
        devicesTotal: Int,
        available: String,
        now: Long,
        muted: Set<String> = emptySet(),
        sessions30d: Int = 0,
        currentSession: String? = null,
        placeAvailable: String? = null,
    ): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))

        add(BLE_SUMMARY, BLE_AVAILABLE, available)
        placeAvailable?.let { add(BLE_SUMMARY, PLACE_AVAILABLE, it) }
        add(BLE_SUMMARY, DEVICES_TOTAL, devicesTotal.toString())
        add(BLE_SUMMARY, TRACKERS_TOTAL, aggregate.devices.size.toString())
        add(BLE_SUMMARY, SESSIONS_30D, sessions30d.toString())
        val byType = aggregate.types.values.sortedBy { it.type.slug }
        add(BLE_SUMMARY, TRACKERS_BY_TYPE, if (byType.isEmpty()) "none" else byType.joinToString(",") { "${it.type.slug}=${it.devices}" })

        fun isMuted(d: DeviceSighting) = isMuted(d.type, d.key, muted)
        // Unmuted and closest to following first, so an identity near the threshold is listed (and so judged).
        val devices = aggregate.devices.values.sortedWith(FollowingHeuristic.deviceOrder(now, ::isMuted))
        fun level(d: DeviceSighting) = FollowingHeuristic.level(d, now)

        // Family subjects are a summary of their identities; the following rule never reads them.
        for (t in byType) {
            val subject = typeSubject(t.type)
            val family = devices.filter { it.type == t.type }
            add(subject, DEVICES, t.devices.toString())
            add(subject, DEVICES_WITH_OWNER, family.count { it.state == TrackerState.WITH_OWNER }.toString())
            add(subject, DEVICES_SEPARATED, family.count { it.state == TrackerState.SEPARATED }.toString())
            add(subject, DEVICES_UNKNOWN, family.count { it.state == TrackerState.UNKNOWN }.toString())
            add(subject, SEEN_COUNT, t.sightings.toString())
            add(subject, SEEN_SESSIONS, t.sessions.toString())
            add(subject, SEEN_SPAN, t.spanMinutes.toString())
            add(subject, SEEN_LAST_DAY, (now - t.lastSeen < DAY_MS).toString())
            add(subject, RSSI_AVG, t.rssiAvg.toString())
            add(subject, STATE, t.state.slug)
            add(subject, CONFIDENCE, TrackerSignatures.of(t.type).confidence.name.lowercase())
            val unmuted = family.filterNot(::isMuted)
            add(subject, FOLLOWING_COUNT, unmuted.count { level(it).isFollowing }.toString())
            // A key that stays put is not on its way to following, however often it turns up.
            val closest = unmuted.firstOrNull {
                FollowingHeuristic.isRecent(it.lastSeen, now) && FollowingHeuristic.isClose(it.sessions, it.spanMinutes) && it.movement != Movement.STAYED
            }
            add(subject, CLOSEST_KEY, closest?.key ?: NONE)
            if (closest != null) {
                add(subject, CLOSEST_SESSIONS, closest.sessions.toString())
                add(subject, CLOSEST_SPAN, closest.spanMinutes.toString())
            }
            // The weak rotating-tag hint, over unmuted keys only; see RotatingTagHeuristic.
            val unmutedKeys = unmuted.map { it.key }.toSet()
            val sessions = t.sessionKeys.mapNotNull { s -> s.keys.filter { it in unmutedKeys }.toSet().takeIf { it.isNotEmpty() }?.let { s.copy(keys = it) } }
            RotatingTagHeuristic.assess(t.type, sessions, now)?.let { p ->
                add(subject, ROTATING_SESSIONS, p.sessions.toString())
                add(subject, ROTATING_SPAN, p.spanMinutes.toString())
                add(subject, ROTATING_KEYS, p.keys.toString())
            }
            if (subject in muted) add(subject, MUTED, "true")
        }

        // The first rows by the order above, plus every unmuted identity the rule would flag even past the cap.
        val listed = devices.take(MAX_LISTED_DEVICES) + devices.drop(MAX_LISTED_DEVICES).filter { !isMuted(it) && level(it) != FollowingLevel.NONE }
        for (d in listed) {
            val subject = trackerSubject(d.type, d.key)
            add(subject, SEEN_COUNT, d.sightings.toString())
            add(subject, SEEN_SESSIONS, d.sessions.toString())
            add(subject, SEEN_SPAN, d.spanMinutes.toString())
            add(subject, SEEN_SESSIONS_SEPARATED, d.sessionsSeparated.toString())
            add(subject, SEEN_SPAN_SEPARATED, d.spanSeparatedMinutes.toString())
            add(subject, SEEN_FIRST, d.firstSeen.toString())
            add(subject, SEEN_LAST, d.lastSeen.toString())
            add(subject, SEEN_RECENT, FollowingHeuristic.isRecent(d.lastSeen, now).toString())
            add(subject, SEEN_THIS_SCAN, (currentSession != null && d.lastSession == currentSession).toString())
            add(subject, SEEN_PLACE_RUN, d.placeRun.toString())
            add(subject, SEEN_PLACED_SESSIONS, d.placedSessions.toString())
            add(subject, MOVEMENT, d.movement.slug)
            add(subject, RSSI_AVG, d.rssiAvg.toString())
            add(subject, RSSI_LAST, d.rssiLast.toString())
            add(subject, STATE, d.state.slug)
            d.battery?.let { add(subject, BATTERY, it) }
            d.kind?.let { add(subject, KIND, it) }
            // A family-level mute from before v3 still mutes every identity of the family until it expires.
            if (isMuted(d)) add(subject, MUTED, "true")
        }
        if (devices.size > listed.size) add(BLE_SUMMARY, TRACKERS_UNLISTED, (devices.size - listed.size).toString())
        return out
    }

    /** Muted directly, or through a family mute saved before v3 (honoured until it expires). */
    fun isMuted(type: TrackerType, key: String, muted: Set<String>): Boolean = trackerSubject(type, key) in muted || typeSubject(type) in muted

    fun wifiObservations(summaries: List<WifiSummary>, available: String, twinsRecorded: Int = 0): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))
        add(WIFI_SUMMARY, WIFI_AVAILABLE, available)
        add(WIFI_SUMMARY, WIFI_NETWORKS, summaries.size.toString())
        add(WIFI_SUMMARY, WIFI_TWINS_RECORDED, twinsRecorded.toString())
        // The connected network and suspects always make the list; the rest by name.
        val ordered = summaries.sortedWith(compareByDescending<WifiSummary> { it.current }.thenByDescending { it.twinSuspect != null }.thenBy { it.subject })
        for (s in ordered.take(MAX_LISTED_NETWORKS)) {
            add(s.subject, WIFI_SECURITY, s.security.slug)
            add(s.subject, WIFI_BSSIDS, s.bssids.toString())
            add(s.subject, WIFI_BANDS, s.bands.sorted().joinToString(","))
            add(s.subject, WIFI_CURRENT, s.current.toString())
            s.twinSuspect?.let { add(s.subject, WIFI_TWIN, it) }
        }
        return out
    }

    fun cellObservations(cell: CellSummary?, available: String, changedSinceLast: Boolean?, downgradesRecorded: Int): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(key: String, value: String) = out.add(Observation(TUNNEL_ID, CELL_SUMMARY, key, value))
        add(CELL_AVAILABLE, available)
        if (cell != null) {
            add(CELL_TYPE, cell.registered.slug)
            add(CELL_OPERATOR, cell.operator)
            add(CELL_NEIGHBOURS, cell.neighbours.toString())
            changedSinceLast?.let { add(CELL_CHANGED, it.toString()) }
        }
        add(CELL_DOWNGRADES_RECORDED, downgradesRecorded.toString())
        return out
    }

    /**
     * The logbook's observations for one scan: [state] always, and when the scan was judged ([judgement]) its verdict,
     * signals and counts. [book], when the caller has one read, gives the counts for a state without a judgement.
     */
    fun logObservations(state: String, judgement: CellJudgement? = null, book: CellLogbook? = null): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(key: String, value: String) = out.add(Observation(TUNNEL_ID, CELL_SUMMARY, key, value))
        add(LOG_STATE, state)
        if (judgement != null) {
            add(LOG_VERDICT, judgement.verdict.slug)
            add(LOG_SIGNALS, if (judgement.signals.isEmpty()) NONE else judgement.signals.sortedBy { it.ordinal }.joinToString(",") { it.slug })
            judgement.towerId?.let { add(LOG_TOWER, it) }
            add(LOG_PLACES, judgement.places.toString())
            add(LOG_PLACE_SCANS, judgement.placeScans.toString())
            add(LOG_PLACE_CELLS, judgement.placeCells.toString())
        } else if (book != null) {
            add(LOG_PLACES, book.places.size.toString())
        }
        return out
    }

    private const val TOWER_PREFIX = "tower "

    /** The subject of an unfamiliar-tower finding: `tower <8 hex>`. */
    fun towerSubject(towerId: String): String = TOWER_PREFIX + towerId

    /** The tower id in `tower <8 hex>`, or null for anything else. */
    fun parseTowerSubject(subject: String): String? =
        subject.removePrefix(TOWER_PREFIX).takeIf { subject.startsWith(TOWER_PREFIX) && TOWER_ID.matches(it) }

    private val TOWER_ID = Regex("[0-9a-f]{${CellLog.TOWER_ID_LENGTH}}")
}
