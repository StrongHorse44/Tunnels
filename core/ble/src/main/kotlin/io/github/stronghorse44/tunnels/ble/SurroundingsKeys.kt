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
    const val EVENT_MONITOR = "monitor"

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
    const val SEEN_SESSIONS_SEPARATED = "seen:sessions:separated"
    const val SEEN_SPAN_SEPARATED = "seen:spanMinutes:separated"
    const val SEEN_LAST_DAY = "seen:lastDay"
    const val RSSI_AVG = "rssi:avg"
    const val STATE = "state"
    const val BATTERY = "battery"
    const val DEVICES = "devices"
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

    /** Availability values shared by the three radios. */
    const val AVAILABLE_YES = "yes"
    const val AVAILABLE_NO_ADAPTER = "no adapter"
    const val AVAILABLE_OFF = "off"
    const val AVAILABLE_NO_PERMISSION = "no permission"
    /** The device's location toggle is off: Android then hands out no Wi-Fi scan results and no cell list. */
    const val AVAILABLE_LOCATION_OFF = "location off"
    const val AVAILABLE_FAILED = "failed"

    /** Devices listed one by one; more than this many are counted under [TRACKERS_UNLISTED]. */
    const val MAX_LISTED_DEVICES = 40
    /** Network names listed one by one; more are counted in [WIFI_SUMMARY]. */
    const val MAX_LISTED_NETWORKS = 60
    const val MUTE_DAYS = 30
    const val DAY_MS = 24 * 60 * 60_000L

    private const val TRACKER_PREFIX = "tracker:"

    fun typeSubject(type: TrackerType): String = TRACKER_PREFIX + type.slug

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

    /**
     * Observations for the BLE side. [muted] holds subjects the user marked as known trackers (type or
     * device subjects); their observations stay but carry `muted=true` so the rules skip them.
     */
    fun bleObservations(
        aggregate: SightingAggregate,
        devicesTotal: Int,
        available: String,
        now: Long,
        muted: Set<String> = emptySet(),
        sessions30d: Int = 0,
    ): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))

        add(BLE_SUMMARY, BLE_AVAILABLE, available)
        add(BLE_SUMMARY, DEVICES_TOTAL, devicesTotal.toString())
        add(BLE_SUMMARY, TRACKERS_TOTAL, aggregate.devices.size.toString())
        add(BLE_SUMMARY, SESSIONS_30D, sessions30d.toString())
        val byType = aggregate.types.values.sortedBy { it.type.slug }
        add(BLE_SUMMARY, TRACKERS_BY_TYPE, if (byType.isEmpty()) "none" else byType.joinToString(",") { "${it.type.slug}=${it.devices}" })

        for (t in byType) {
            val subject = typeSubject(t.type)
            add(subject, DEVICES, t.devices.toString())
            add(subject, SEEN_COUNT, t.sightings.toString())
            add(subject, SEEN_SESSIONS, t.sessions.toString())
            add(subject, SEEN_SPAN, t.spanMinutes.toString())
            add(subject, SEEN_SESSIONS_SEPARATED, t.sessionsSeparated.toString())
            add(subject, SEEN_SPAN_SEPARATED, t.spanSeparatedMinutes.toString())
            add(subject, SEEN_LAST_DAY, (now - t.lastSeen < DAY_MS).toString())
            add(subject, RSSI_AVG, t.rssiAvg.toString())
            add(subject, STATE, t.state.slug)
            add(subject, CONFIDENCE, TrackerSignatures.of(t.type).confidence.name.lowercase())
            if (subject in muted) add(subject, MUTED, "true")
        }

        val devices = aggregate.devices.values.sortedWith(compareByDescending<DeviceSighting> { it.sessions }.thenByDescending { it.lastSeen })
        for (d in devices.take(MAX_LISTED_DEVICES)) {
            val subject = trackerSubject(d.type, d.key)
            add(subject, SEEN_COUNT, d.sightings.toString())
            add(subject, SEEN_SESSIONS, d.sessions.toString())
            add(subject, SEEN_SPAN, d.spanMinutes.toString())
            add(subject, RSSI_AVG, d.rssiAvg.toString())
            add(subject, STATE, d.state.slug)
            d.battery?.let { add(subject, BATTERY, it) }
            if (subject in muted || typeSubject(d.type) in muted) add(subject, MUTED, "true")
        }
        if (devices.size > MAX_LISTED_DEVICES) add(BLE_SUMMARY, TRACKERS_UNLISTED, (devices.size - MAX_LISTED_DEVICES).toString())
        return out
    }

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
}
