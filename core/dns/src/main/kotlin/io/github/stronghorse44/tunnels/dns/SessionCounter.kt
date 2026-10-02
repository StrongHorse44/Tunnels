package io.github.stronghorse44.tunnels.dns

/** Live totals of a running session, for the notification and the panel. Cumulative since start. */
data class SessionTotals(
    val queries: Int = 0,
    val apps: Int = 0,
    val domains: Int = 0,
    val encrypted: Int = 0,
    val trackers: Int = 0,
    /** Lookups the session answered itself because [BlockPolicy] blocks them. */
    val blocked: Int = 0,
)

/**
 * Counts DNS queries per app during one session and turns them into [SessionRecord] rows at each flush.
 * Everything is bounded: at most [maxApps] subjects (the rest fold into [TrafficKeys.OTHER_SUBJECT]),
 * at most [maxDomainsPerApp] distinct domains counted per app per interval (further new domains are
 * only tallied). Thread-safe: the forwarder thread records while the service thread flushes.
 */
class SessionCounter(
    val session: String,
    private val matchTracker: (String) -> TrackerDomain? = TrackerDomains::match,
    private val maxApps: Int = 200,
    private val maxDomainsPerApp: Int = 1000,
) {
    private class AppCounts {
        val perDomain = HashMap<String, Int>()
        var overflowDomains = 0
        var queries = 0
        var encrypted = 0
        var blocked = 0
        val perTracker = HashMap<String, Int>()
    }

    private val interval = HashMap<String, AppCounts>()
    private val allApps = HashSet<String>()
    private val allDomains = HashSet<String>()
    private val allTrackers = HashSet<String>()
    private var totalQueries = 0
    private var totalEncrypted = 0
    private var totalBlocked = 0
    private var overflowDomainsTotal = 0

    val totals: SessionTotals
        @Synchronized get() = SessionTotals(totalQueries, allApps.size, allDomains.size + overflowDomainsTotal, totalEncrypted, allTrackers.size, totalBlocked)

    /** A query by [subject] for [host] (the DNS question name). */
    @Synchronized
    fun query(subject: String, host: String) {
        val app = counts(subject)
        val domain = PublicSuffix.registrableDomain(host)
        if (domain.isEmpty()) return
        app.queries++
        totalQueries++
        if (domain in app.perDomain || app.perDomain.size < maxDomainsPerApp) {
            app.perDomain[domain] = (app.perDomain[domain] ?: 0) + 1
        } else {
            app.overflowDomains++
        }
        if (allDomains.size < maxDomainsPerApp * 5) allDomains += domain else overflowDomainsTotal++
        matchTracker(host)?.let { t ->
            app.perTracker[t.domain] = (app.perTracker[t.domain] ?: 0) + 1
            allTrackers += t.domain
        }
    }

    /** A lookup by [subject] that the session answered itself (already counted by [query]). */
    @Synchronized
    fun blocked(subject: String) {
        counts(subject).blocked++
        totalBlocked++
    }

    /** An attempt at encrypted DNS (port 853) by [subject]; nothing about it can be read. */
    @Synchronized
    fun encrypted(subject: String) {
        counts(subject).encrypted++
        totalEncrypted++
    }

    private fun counts(subject: String): AppCounts {
        val key = if (subject in interval || interval.size < maxApps) subject else TrafficKeys.OTHER_SUBJECT
        allApps += key
        return interval.getOrPut(key) { AppCounts() }
    }

    /**
     * Rows for the events table covering activity since the previous flush, then resets the interval.
     * The [TrafficKeys.SUMMARY] marker is included when there was activity or [force] is set (session end).
     */
    @Synchronized
    fun flush(minutes: Int, force: Boolean = false): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(interval.size + 1)
        var queries = 0
        for ((subject, app) in interval.entries.sortedBy { it.key }) {
            if (app.queries == 0 && app.encrypted == 0) continue
            queries += app.queries
            val record = SessionRecord(
                session = session,
                domains = app.perDomain.size + app.overflowDomains,
                queries = app.queries,
                encrypted = app.encrypted,
                top = topOf(app.perDomain, SessionRecord.TOP_MAX),
                trackers = app.perTracker.size,
                trackerTop = topOf(app.perTracker, SessionRecord.TRACKER_TOP_MAX),
                blocked = app.blocked,
            )
            rows += subject to record.encode()
        }
        if (rows.isNotEmpty() || force) {
            rows += TrafficKeys.SUMMARY to SessionMarker(session, rows.size, queries, minutes).encode()
        }
        interval.clear()
        return rows
    }

    companion object {
        fun topOf(counts: Map<String, Int>, max: Int): List<Pair<String, Int>> =
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(max)
                .map { it.key to it.value }

        private val tokenChars = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** A random 6-character session token. */
        fun newToken(random: kotlin.random.Random = kotlin.random.Random.Default): String =
            buildString(6) { repeat(6) { append(tokenChars[random.nextInt(tokenChars.length)]) } }
    }
}
