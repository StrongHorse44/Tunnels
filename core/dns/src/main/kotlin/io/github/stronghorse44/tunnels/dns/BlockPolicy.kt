package io.github.stronghorse44.tunnels.dns

/**
 * What a Traffic session answers itself instead of forwarding: lookups of known tracking domains ([TrackerDomains])
 * in the chosen [kinds] and hosts on the chosen bundled [lists], for every app except the [exempt] ones. Apps in
 * [strict] get every kind and every bundled list blocked, whether or not blocking is on for everyone. A blocked lookup
 * gets "no such domain" (NXDOMAIN), so the app's ads or analytics call fails quickly and everything else keeps
 * working. Off until the user turns it on.
 */
data class BlockPolicy(
    val enabled: Boolean = false,
    val kinds: Set<TrackerKind> = DEFAULT_KINDS,
    /** Session subjects (package names) whose lookups are never blocked: for an app that breaks without its trackers. */
    val exempt: Set<String> = emptySet(),
    /** Ids of [Blocklists] applied on top of the catalog while blocking is on. */
    val lists: Set<String> = emptySet(),
    /** Subjects with everything blocked: every kind and every bundled list, even while blocking is off for others. */
    val strict: Set<String> = emptySet(),
) {
    /**
     * The entry that makes [host] blocked for [subject], or null when the lookup goes out as usual. [listed] names the
     * bundled list (by id, from those asked for) that holds [host]; a hit becomes an [TrackerKind.ADS] entry named
     * after the list.
     */
    fun blocks(
        subject: String,
        host: String,
        match: (String) -> TrackerDomain? = TrackerDomains::match,
        listed: (host: String, ids: Set<String>) -> String? = { _, _ -> null },
    ): TrackerDomain? {
        if (subject in exempt) return null
        val all = subject in strict
        if (!enabled && !all) return null
        match(host)?.let { t -> if (all || t.kind in kinds) return t }
        val ids = if (all) Blocklists.ALL.map { it.id }.toSet() else lists
        if (ids.isEmpty()) return null
        val id = listed(host, ids) ?: return null
        return TrackerDomain(PublicSuffix.normalize(host), TrackerKind.ADS, "${Blocklists.byId(id)?.name ?: id} list", exactHost = true)
    }

    /** Whether any lookup can be blocked: some list needs loading only then. */
    val needsLists: Boolean get() = (enabled && lists.isNotEmpty()) || strict.isNotEmpty()

    /** Lets [subject]'s trackers through; it can no longer be [strict]. */
    fun exempting(subject: String, on: Boolean): BlockPolicy =
        if (on) copy(exempt = exempt + subject, strict = strict - subject) else copy(exempt = exempt - subject)

    /** Blocks everything for [subject]; it can no longer be [exempt]. */
    fun blockingAll(subject: String, on: Boolean): BlockPolicy =
        if (on) copy(strict = strict + subject, exempt = exempt - subject) else copy(strict = strict - subject)

    fun encode(): String = Fields.encode(
        listOf(
            "on" to enabled.toString(),
            "kinds" to TrackerKind.entries.filter { it in kinds }.joinToString(",") { it.name },
            "exempt" to exempt.sorted().joinToString(","),
        ) + listOfNotNull(
            lists.takeIf { it.isNotEmpty() }?.let { "lists" to it.sorted().joinToString(",") },
            strict.takeIf { it.isNotEmpty() }?.let { "strict" to it.sorted().joinToString(",") },
        ),
    )

    companion object {
        /** Settings key in the encrypted store. */
        const val KEY = "traffic.block"

        /** Blocked unless the user changes it: nothing here should break an app beyond its ads and analytics. */
        val DEFAULT_KINDS: Set<TrackerKind> = setOf(TrackerKind.ADS, TrackerKind.ANALYTICS, TrackerKind.ATTRIBUTION, TrackerKind.CRASH, TrackerKind.TELEMETRY)

        /** What blocking a kind can cost, for kinds that break more than tracking. */
        val CAVEATS: Map<TrackerKind, String> = mapOf(
            TrackerKind.SOCIAL to "can break signing in with Facebook and sharing to it",
            TrackerKind.PUSH to "can stop notifications of apps that use these services",
        )

        private fun set(value: String?): Set<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Missing or damaged values fall back to the defaults, with blocking off. */
        fun decode(s: String?): BlockPolicy {
            if (s.isNullOrBlank()) return BlockPolicy()
            val f = Fields.parse(s)
            val kinds = f["kinds"]?.let { v -> v.split(',').mapNotNull { n -> TrackerKind.entries.firstOrNull { it.name == n.trim() } }.toSet() }
            return BlockPolicy(
                enabled = f["on"] == "true",
                kinds = kinds ?: DEFAULT_KINDS,
                exempt = set(f["exempt"]),
                lists = set(f["lists"]).filter { Blocklists.byId(it) != null }.toSet(),
                strict = set(f["strict"]),
            )
        }
    }
}
