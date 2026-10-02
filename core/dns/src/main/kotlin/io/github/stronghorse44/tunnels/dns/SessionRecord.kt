package io.github.stronghorse44.tunnels.dns

/**
 * One events-table row: what one app did during one flush interval of one logging session. Counts and
 * registrable domain names only; no timestamps per query and no full query log.
 *
 * Wire form: `s=1a2b3c;domains=14;queries=231;enc=0;top=example.com:120,cdn.example.net:40;trackers=2;trackerTop=app-measurement.com:12`
 */
data class SessionRecord(
    /** Short random token shared by every row of one session. */
    val session: String,
    val domains: Int,
    val queries: Int,
    val encrypted: Int,
    /** Registrable domain to query count, most queried first, at most [TOP_MAX]. */
    val top: List<Pair<String, Int>>,
    val trackers: Int,
    /** Tracker domain to query count, most queried first, at most [TRACKER_TOP_MAX]. */
    val trackerTop: List<Pair<String, Int>>,
    /** Lookups the session blocked ([BlockPolicy]); written only when non-zero, so rows from before blocking read 0. */
    val blocked: Int = 0,
) {
    fun encode(): String = Fields.encode(
        listOf(
            "s" to session,
            "domains" to domains.toString(),
            "queries" to queries.toString(),
            "enc" to encrypted.toString(),
            "top" to Fields.encodeCounts(top),
            "trackers" to trackers.toString(),
            "trackerTop" to Fields.encodeCounts(trackerTop),
        ) + if (blocked > 0) listOf("blocked" to blocked.toString()) else emptyList(),
    )

    companion object {
        const val TOP_MAX = 5
        const val TRACKER_TOP_MAX = 8

        /** Null for anything that is not a well-formed app row (a [SessionMarker], say). */
        fun parse(summary: String): SessionRecord? {
            val f = Fields.parse(summary)
            val session = f["s"]?.takeIf { it.isNotEmpty() } ?: return null
            val queries = f["queries"]?.toIntOrNull() ?: return null
            val domains = f["domains"]?.toIntOrNull() ?: return null
            return SessionRecord(
                session = session,
                domains = domains,
                queries = queries,
                encrypted = f["enc"]?.toIntOrNull() ?: 0,
                top = Fields.parseCounts(f["top"]).take(TOP_MAX),
                trackers = f["trackers"]?.toIntOrNull() ?: 0,
                trackerTop = Fields.parseCounts(f["trackerTop"]).take(TRACKER_TOP_MAX),
                blocked = f["blocked"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

/**
 * One row per flush under the [TrafficKeys.SUMMARY] subject, so sessions count even when no app made a
 * query. Wire form: `s=1a2b3c;apps=3;queries=400;minutes=12`.
 */
data class SessionMarker(val session: String, val apps: Int, val queries: Int, val minutes: Int) {
    fun encode(): String = Fields.encode(
        listOf("s" to session, "apps" to apps.toString(), "queries" to queries.toString(), "minutes" to minutes.toString()),
    )

    companion object {
        fun parse(summary: String): SessionMarker? {
            val f = Fields.parse(summary)
            val session = f["s"]?.takeIf { it.isNotEmpty() } ?: return null
            val apps = f["apps"]?.toIntOrNull() ?: return null
            return SessionMarker(session, apps, f["queries"]?.toIntOrNull() ?: 0, f["minutes"]?.toIntOrNull() ?: 0)
        }
    }
}

/** `key=value;key=value` encoding shared by the record types. Values never contain `;` or `=`. */
object Fields {
    fun encode(fields: List<Pair<String, String>>): String =
        fields.joinToString(";") { (k, v) -> "$k=${v.replace(';', '_').replace('=', '_')}" }

    fun parse(s: String): Map<String, String> =
        s.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }.toMap()

    /** `a.com:12,b.net:3`. Names are hostnames, so the last colon separates the count (IPv6 literals included). */
    fun encodeCounts(items: List<Pair<String, Int>>): String =
        items.joinToString(",") { (name, n) -> "${name.replace(',', '_')}:$n" }

    fun parseCounts(s: String?): List<Pair<String, Int>> =
        s.orEmpty().split(',').mapNotNull { item ->
            val t = item.trim()
            if (t.isEmpty()) return@mapNotNull null
            val colon = t.lastIndexOf(':')
            val count = if (colon > 0) t.substring(colon + 1).toIntOrNull() else null
            if (count == null) t to 1 else t.substring(0, colon) to count
        }
}
