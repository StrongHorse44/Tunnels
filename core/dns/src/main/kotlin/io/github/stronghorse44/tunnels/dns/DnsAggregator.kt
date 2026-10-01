package io.github.stronghorse44.tunnels.dns

import io.github.stronghorse44.tunnels.model.Observation

/** One app's DNS behaviour over the aggregation window. */
data class AppDnsStats(
    val domains: Int,
    val queries: Int,
    val encrypted: Int,
    val top: List<Pair<String, Int>>,
    val trackerDomains: Int,
    val trackerTop: List<Pair<String, Int>>,
)

data class DnsAggregate(val perApp: Map<String, AppDnsStats>, val sessions: Int) {
    companion object {
        val EMPTY = DnsAggregate(emptyMap(), 0)
    }
}

/** Folds the events table's SESSION rows into per-app stats and observations. Pure, unit-tested. */
object DnsAggregator {
    /** [rows] are (subject, summary) pairs of kind SESSION, already limited to the retention window. */
    fun aggregate(rows: List<Pair<String, String>>): DnsAggregate {
        val sessions = HashSet<String>()
        val records = HashMap<String, MutableList<SessionRecord>>()
        for ((subject, summary) in rows) {
            if (subject == TrafficKeys.SUMMARY) {
                SessionMarker.parse(summary)?.let { sessions += it.session }
                continue
            }
            val record = SessionRecord.parse(summary) ?: continue
            sessions += record.session
            records.getOrPut(subject) { ArrayList() } += record
        }
        val perApp = records.mapValues { (_, list) -> merge(list) }
        return DnsAggregate(perApp, sessions.size)
    }

    /**
     * Rows hold distinct counts per interval, not the names behind them, so the 30-day distinct count is
     * a lower bound: the larger of the biggest single interval and the union of the top lists.
     */
    fun merge(records: List<SessionRecord>): AppDnsStats {
        val top = HashMap<String, Int>()
        val trackerTop = HashMap<String, Int>()
        var queries = 0
        var encrypted = 0
        var maxDomains = 0
        var maxTrackers = 0
        for (r in records) {
            queries += r.queries
            encrypted += r.encrypted
            maxDomains = maxOf(maxDomains, r.domains)
            maxTrackers = maxOf(maxTrackers, r.trackers)
            r.top.forEach { (d, n) -> top[d] = (top[d] ?: 0) + n }
            r.trackerTop.forEach { (d, n) -> trackerTop[d] = (trackerTop[d] ?: 0) + n }
        }
        return AppDnsStats(
            domains = maxOf(maxDomains, top.size),
            queries = queries,
            encrypted = encrypted,
            top = SessionCounter.topOf(top, TrafficKeys.TOP_MAX),
            trackerDomains = maxOf(maxTrackers, trackerTop.size),
            trackerTop = SessionCounter.topOf(trackerTop, TrafficKeys.TOP_MAX),
        )
    }

    fun observations(aggregate: DnsAggregate, sessionActive: Boolean, otherVpnActive: Boolean): List<Observation> {
        val t = TrafficKeys.TUNNEL_ID
        val out = ArrayList<Observation>(aggregate.perApp.size * 6 + 3)
        for ((subject, s) in aggregate.perApp.entries.sortedBy { it.key }) {
            out += Observation(t, subject, TrafficKeys.DOMAINS30, s.domains.toString())
            out += Observation(t, subject, TrafficKeys.QUERIES30, s.queries.toString())
            if (s.top.isNotEmpty()) out += Observation(t, subject, TrafficKeys.TOP, s.top.joinToString(",") { it.first })
            out += Observation(t, subject, TrafficKeys.TRACKER_DOMAINS30, s.trackerDomains.toString())
            if (s.trackerTop.isNotEmpty()) out += Observation(t, subject, TrafficKeys.TRACKER_TOP, s.trackerTop.joinToString(",") { it.first })
            if (s.encrypted > 0) out += Observation(t, subject, TrafficKeys.ENCRYPTED30, s.encrypted.toString())
        }
        out += Observation(t, TrafficKeys.SUMMARY, TrafficKeys.SESSIONS_COUNT30, aggregate.sessions.toString())
        out += Observation(t, TrafficKeys.SUMMARY, TrafficKeys.SESSION_ACTIVE, sessionActive.toString())
        out += Observation(t, TrafficKeys.SUMMARY, TrafficKeys.VPN_OTHER_ACTIVE, otherVpnActive.toString())
        return out
    }
}
