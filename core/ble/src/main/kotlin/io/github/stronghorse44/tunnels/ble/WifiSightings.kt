package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.model.Observation

/** One access point in one Wi-Fi scan: its keyed id and the name it broadcast. */
data class ApSeen(val key: String, val name: String)

/**
 * One Wi-Fi scan: the unit the Wi-Fi stream ([SurroundingsKeys.WIFI_LEDGER]) keeps for 30 days, one row per scan.
 * Access points appear by [ApSeen.key], the first 16 hex of an HMAC of the address under the Surroundings keystore
 * key: the address itself is never stored, and nobody off this phone can compute the key. The name each one
 * broadcast is kept with it (RJ's exception to rule 3, 2026-10-10: see CLAUDE.md). [place] is the phone's place
 * number for the scan ([PlaceTracking]), or null when the scan had no fix.
 */
data class WifiScanRecord(val session: String, val at: Long, val place: Long?, val aps: List<ApSeen>) {
    /** `at=…;place=…;aps=<key>~<name>,…` with `%`, `,`, `~`, `;`, `=` and control characters in names %-escaped. */
    fun encode(): String = buildString {
        append("at=").append(at)
        append(";place=").append(place?.toString().orEmpty())
        append(";aps=").append(aps.joinToString(",") { "${it.key}~${escape(it.name)}" })
    }

    companion object {
        private val KEY = Regex("[0-9a-f]{${WifiFollowing.KEY_LENGTH}}")

        /** Reads a row back; null for one this version does not understand. Malformed entries are dropped. */
        fun parse(session: String, summary: String): WifiScanRecord? {
            if (session.isEmpty()) return null
            val fields = summary.split(';').mapNotNull { f ->
                val i = f.indexOf('=')
                if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
            }.toMap()
            val at = fields["at"]?.toLongOrNull() ?: return null
            val place = fields["place"]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            val aps = fields["aps"].orEmpty().split(',').filter { it.isNotEmpty() }.mapNotNull { entry ->
                val tilde = entry.indexOf('~')
                if (tilde <= 0) return@mapNotNull null
                val key = entry.substring(0, tilde).takeIf { KEY.matches(it) } ?: return@mapNotNull null
                ApSeen(key, unescape(entry.substring(tilde + 1)) ?: return@mapNotNull null)
            }
            return WifiScanRecord(session, at, place, aps)
        }

        private const val SPECIAL = "%,~;="

        fun escape(name: String): String = buildString {
            for (c in name) {
                if (c in SPECIAL || c.code < 0x20 || c.code == 0x7f) append('%').append("%04x".format(c.code)) else append(c)
            }
        }

        /** The inverse of [escape]; null when the text is not a valid escape. */
        fun unescape(text: String): String? {
            if ('%' !in text) return text
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                if (i + 5 > text.length) return null
                val code = text.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                out.append(code.toChar())
                i += 5
            }
            return out.toString()
        }
    }
}

/** Everything known about one access point (one keyed id) across the scans of the last 30 days. */
data class AccessPointSighting(
    val key: String,
    /** The name in its most recent scan. */
    val name: String,
    /** Distinct names it broadcast under this id. */
    val names: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Scans it was in. */
    val sessions: Int,
    /** Distinct place numbers it was seen at. */
    val places: Int,
    /** Real moves ([WifiFollowing.realMoves]) it was seen on both sides of. */
    val moves: Int,
    val lastSession: String,
) {
    val spanMinutes: Long get() = (lastSeen - firstSeen) / 60_000L
}

/**
 * Wi-Fi access points seen over time, the way Bluetooth tags are ([FollowingHeuristic]), with the threshold RJ chose
 * (2026-10-10): every access point seen in [DEFAULT_THRESHOLD] scans spread over [MIN_SPAN_MINUTES] minutes is
 * flagged ([Level.REPEATED], a notice) wherever it is, and one that was also seen on both sides of a real move
 * travelled with the phone ([Level.FOLLOWING], a warning). The threshold is the user's setting.
 *
 * Android shows apps only access points (routers, phone hotspots, mobile Wi-Fi boxes, car Wi-Fi, cameras in setup
 * mode); a phone or camera that only joins a network never appears in a scan, so it cannot be counted here.
 */
object WifiFollowing {
    const val DEFAULT_THRESHOLD = 5
    const val MIN_THRESHOLD = 2
    const val MAX_THRESHOLD = 50
    const val MIN_SPAN_MINUTES = 30L

    /** Findings only for access points seen within this many days, like Bluetooth identities. */
    const val RECENT_DAYS = 7

    /** Hex characters of an access point's keyed id. */
    const val KEY_LENGTH = 16

    /**
     * A change of place counts as a move only when few of the access points seen at the larger side were also seen at
     * the other: a position fix that wandered while the phone sat still keeps nearly all of them.
     */
    const val MAX_SHARED_FRACTION = 0.5

    enum class Level(val slug: String) {
        NONE("none"),
        REPEATED("repeated"),
        FOLLOWING("following"),
        ;

        companion object {
            fun bySlug(slug: String?): Level = entries.firstOrNull { it.slug == slug } ?: NONE
        }
    }

    fun threshold(setting: String?): Int = setting?.trim()?.toIntOrNull()?.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) ?: DEFAULT_THRESHOLD

    fun thresholdText(threshold: Int): String = "$threshold separate scans spread over at least $MIN_SPAN_MINUTES minutes"

    /**
     * The places p for which the step p → p + 1 was a real move: both places have Wi-Fi data, and at most
     * [MAX_SHARED_FRACTION] of the larger side's access points were also seen at the other.
     */
    fun realMoves(records: Collection<WifiScanRecord>): Set<Long> {
        val byPlace = HashMap<Long, MutableSet<String>>()
        for (r in records) {
            val p = r.place ?: continue
            if (r.aps.isEmpty()) continue
            byPlace.getOrPut(p) { HashSet() } += r.aps.map { it.key }
        }
        return byPlace.keys.filter { p ->
            val a = byPlace[p] ?: return@filter false
            val b = byPlace[p + 1] ?: return@filter false
            val shared = a.count { it in b }
            shared <= MAX_SHARED_FRACTION * maxOf(a.size, b.size)
        }.toSet()
    }

    /** Folds the scans into one entry per access point, most recently seen first. */
    fun aggregate(records: Collection<WifiScanRecord>): List<AccessPointSighting> {
        val moves = realMoves(records)
        data class Fold(var name: String, var nameAt: Long, val names: MutableSet<String>, var first: Long, var last: Long, var lastSession: String, val sessions: MutableSet<String>, val places: MutableSet<Long>)
        val folds = HashMap<String, Fold>()
        for (r in records) {
            for (ap in r.aps.distinctBy { it.key }) {
                val f = folds.getOrPut(ap.key) { Fold(ap.name, r.at, HashSet(), r.at, r.at, r.session, HashSet(), HashSet()) }
                f.names += ap.name
                f.sessions += r.session
                r.place?.let { f.places += it }
                if (r.at < f.first) f.first = r.at
                if (r.at >= f.last) {
                    f.last = r.at
                    f.lastSession = r.session
                }
                if (r.at >= f.nameAt) {
                    f.name = ap.name
                    f.nameAt = r.at
                }
            }
        }
        return folds.map { (key, f) ->
            AccessPointSighting(
                key = key,
                name = f.name,
                names = f.names.size,
                firstSeen = f.first,
                lastSeen = f.last,
                sessions = f.sessions.size,
                places = f.places.size,
                moves = f.places.count { p -> p in moves && (p + 1) in f.places },
                lastSession = f.lastSession,
            )
        }.sortedByDescending { it.lastSeen }
    }

    fun isRecent(lastSeen: Long, now: Long): Boolean = now - lastSeen <= RECENT_DAYS * SurroundingsKeys.DAY_MS

    /** How one access point stands against [threshold]; nothing for one not seen this week. */
    fun level(ap: AccessPointSighting, threshold: Int, now: Long): Level {
        if (!isRecent(ap.lastSeen, now)) return Level.NONE
        val enough = ap.sessions >= threshold && ap.spanMinutes >= MIN_SPAN_MINUTES
        return when {
            enough && ap.moves >= 1 -> Level.FOLLOWING
            enough -> Level.REPEATED
            else -> Level.NONE
        }
    }

    /** Listing order: flagged first (following, then repeated), then the most scans, then the most recent. */
    fun order(threshold: Int, now: Long): Comparator<AccessPointSighting> =
        compareByDescending<AccessPointSighting> { level(it, threshold, now).ordinal }
            .thenByDescending { it.moves }
            .thenByDescending { it.sessions }
            .thenByDescending { it.lastSeen }
}

/** Access points in one scan that look like one device: their addresses share the first five bytes. */
data class NameCluster(
    /** The keyed id of the shared prefix (the Android side computes it; the prefix itself is never stored). */
    val key: String,
    /** Distinct names broadcast, hidden ones left out, sorted. */
    val names: List<String>,
    /** How many of those names are open networks. */
    val open: Int,
    val bssids: Int,
)

/**
 * "One device, many names": a rogue access point (the Wi-Fi Pineapple kind) answers to whatever network names nearby
 * phones ask for, so one radio shows up under many names, mostly open. Routers and office access points broadcast a
 * few names too (main, guest, IoT), almost all secured, so the warning needs many names and several open ones.
 */
object ManyNames {
    /** A warning: this many names from one device, at least [WARN_OPEN] of them open. */
    const val WARN_NAMES = 5
    const val WARN_OPEN = 3

    /** A notice: this many names whatever their security. */
    const val NOTICE_NAMES = 8

    enum class Level(val slug: String) {
        NONE("none"),
        NOTICE("notice"),
        WARN("warn"),
        ;

        companion object {
            fun bySlug(slug: String?): Level = entries.firstOrNull { it.slug == slug } ?: NONE
        }
    }

    /**
     * The device part of an address: its first five bytes, with the locally administered bit of the first cleared
     * (radios derive extra addresses by setting it). Null for an address that is not six bytes.
     */
    fun prefixOf(bssid: String): String? {
        val bytes = bssid.lowercase().split(':')
        if (bytes.size != 6) return null
        val first = bytes[0].toIntOrNull(16) ?: return null
        if (bytes.drop(1).any { it.length != 2 || it.toIntOrNull(16) == null }) return null
        return (listOf("%02x".format(first and 0xfd)) + bytes.subList(1, 5)).joinToString(":")
    }

    /** The clusters of [networks] with two or more names; [keyOf] turns a prefix into its keyed id. */
    fun clusters(networks: Collection<WifiNetwork>, keyOf: (String) -> String): List<NameCluster> =
        networks.groupBy { prefixOf(it.bssid) }
            .filterKeys { it != null }
            .mapNotNull { (prefix, aps) ->
                val named = aps.filter { it.subject != WifiHeuristics.HIDDEN }
                val names = named.map { it.subject }.toSortedSet().toList()
                if (names.size < 2) return@mapNotNull null
                val open = named.filter { it.security == WifiSecurity.OPEN }.map { it.subject }.toSet().size
                NameCluster(keyOf(prefix!!), names, open, aps.map { it.bssid.lowercase() }.toSet().size)
            }

    fun level(c: NameCluster): Level = when {
        c.names.size >= WARN_NAMES && c.open >= WARN_OPEN -> Level.WARN
        c.names.size >= NOTICE_NAMES -> Level.NOTICE
        else -> Level.NONE
    }
}

/** What the screen shows of Wi-Fi over time, read back from a scan's observations. Plain Kotlin. */
object WifiOverTime {
    data class ApFacts(
        val subject: String,
        val name: String,
        val names: Int,
        val sessions: Int,
        val spanMinutes: Long,
        val places: Int,
        val moves: Int,
        val lastSeen: Long?,
        val thisScan: Boolean,
        val level: WifiFollowing.Level,
        val muted: Boolean,
    ) {
        /** "12 scans over 9 hours · 2 places · 1 move". */
        val counts: String
            get() = listOfNotNull(
                "$sessions ${if (sessions == 1) "scan" else "scans"}" + if (sessions > 1) " over ${SurroundingsRules.duration(spanMinutes)}" else "",
                if (places > 1) "$places places" else null,
                if (moves > 0) "$moves ${if (moves == 1) "move" else "moves"}" else null,
                if (names > 1) "$names names" else null,
            ).joinToString(" · ")
    }

    data class DeviceFacts(val subject: String, val names: Int, val open: Int, val sample: String, val level: ManyNames.Level, val muted: Boolean)

    data class Summary(val tracked: Int, val repeated: Int, val following: Int, val threshold: Int, val unlisted: Int)

    private fun facts(observations: List<Observation>, subject: String): Map<String, String> =
        observations.filter { it.subject == subject }.associate { it.key to it.value }

    fun summary(observations: List<Observation>): Summary? {
        val f = facts(observations, SurroundingsKeys.WIFI_SUMMARY)
        val tracked = f[SurroundingsKeys.AP_TRACKED]?.toIntOrNull() ?: return null
        return Summary(
            tracked = tracked,
            repeated = f[SurroundingsKeys.AP_REPEATED]?.toIntOrNull() ?: 0,
            following = f[SurroundingsKeys.AP_FOLLOWING]?.toIntOrNull() ?: 0,
            threshold = f[SurroundingsKeys.AP_THRESHOLD]?.toIntOrNull() ?: WifiFollowing.DEFAULT_THRESHOLD,
            unlisted = f[SurroundingsKeys.AP_UNLISTED]?.toIntOrNull() ?: 0,
        )
    }

    /** Listed access points: flagged and unmuted first (following, then repeated), then muted, then the rest by scans. */
    fun accessPoints(observations: List<Observation>): List<ApFacts> =
        observations.map { it.subject }.distinct().filter { SurroundingsKeys.parseApSubject(it) != null }.map { subject ->
            val f = facts(observations, subject)
            ApFacts(
                subject = subject,
                name = f[SurroundingsKeys.AP_NAME] ?: WifiHeuristics.HIDDEN,
                names = f[SurroundingsKeys.AP_NAMES]?.toIntOrNull() ?: 1,
                sessions = f[SurroundingsKeys.AP_SESSIONS]?.toIntOrNull() ?: 0,
                spanMinutes = f[SurroundingsKeys.AP_SPAN]?.toLongOrNull() ?: 0L,
                places = f[SurroundingsKeys.AP_PLACES]?.toIntOrNull() ?: 0,
                moves = f[SurroundingsKeys.AP_MOVES]?.toIntOrNull() ?: 0,
                lastSeen = f[SurroundingsKeys.AP_LAST]?.toLongOrNull(),
                thisScan = f[SurroundingsKeys.AP_THIS_SCAN] == "true",
                level = WifiFollowing.Level.bySlug(f[SurroundingsKeys.AP_LEVEL]),
                muted = f[SurroundingsKeys.MUTED] == "true",
            )
        }.sortedWith(
            compareBy<ApFacts> { if (it.level != WifiFollowing.Level.NONE && !it.muted) 0 else if (it.muted) 2 else 1 }
                .thenByDescending { it.level.ordinal }
                .thenByDescending { it.sessions }
                .thenBy { it.name },
        )

    fun devices(observations: List<Observation>): List<DeviceFacts> =
        observations.map { it.subject }.distinct().filter { SurroundingsKeys.parseDeviceSubject(it) != null }.map { subject ->
            val f = facts(observations, subject)
            DeviceFacts(
                subject = subject,
                names = f[SurroundingsKeys.DEV_NAMES]?.toIntOrNull() ?: 0,
                open = f[SurroundingsKeys.DEV_OPEN]?.toIntOrNull() ?: 0,
                sample = f[SurroundingsKeys.DEV_SAMPLE].orEmpty(),
                level = ManyNames.Level.bySlug(f[SurroundingsKeys.DEV_LEVEL]),
                muted = f[SurroundingsKeys.MUTED] == "true",
            )
        }.sortedByDescending { it.level.ordinal }

    /** Subjects the "Mute all repeated" button covers: unmuted access points at [WifiFollowing.Level.REPEATED] only. */
    fun muteAllCandidates(aps: List<ApFacts>): List<String> =
        aps.filter { it.level == WifiFollowing.Level.REPEATED && !it.muted }.map { it.subject }
}
