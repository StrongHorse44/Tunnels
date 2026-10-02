package io.github.stronghorse44.tunnels.dns

/**
 * What a Traffic session answers itself instead of forwarding: lookups of known tracking domains ([TrackerDomains])
 * in the chosen [kinds], for every app except the [exempt] ones. A blocked lookup gets "no such domain" (NXDOMAIN),
 * so the app's ads or analytics call fails quickly and everything else keeps working. Off until the user turns it on.
 */
data class BlockPolicy(
    val enabled: Boolean = false,
    val kinds: Set<TrackerKind> = DEFAULT_KINDS,
    /** Session subjects (package names) whose lookups are never blocked: for an app that breaks without its trackers. */
    val exempt: Set<String> = emptySet(),
) {
    /** The tracker entry that makes [host] blocked for [subject], or null when the lookup goes out as usual. */
    fun blocks(subject: String, host: String, match: (String) -> TrackerDomain? = TrackerDomains::match): TrackerDomain? {
        if (!enabled || subject in exempt) return null
        return match(host)?.takeIf { it.kind in kinds }
    }

    fun encode(): String = Fields.encode(
        listOf(
            "on" to enabled.toString(),
            "kinds" to TrackerKind.entries.filter { it in kinds }.joinToString(",") { it.name },
            "exempt" to exempt.sorted().joinToString(","),
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

        /** Missing or damaged values fall back to the defaults, with blocking off. */
        fun decode(s: String?): BlockPolicy {
            if (s.isNullOrBlank()) return BlockPolicy()
            val f = Fields.parse(s)
            val kinds = f["kinds"]?.let { v -> v.split(',').mapNotNull { n -> TrackerKind.entries.firstOrNull { it.name == n.trim() } }.toSet() }
            return BlockPolicy(
                enabled = f["on"] == "true",
                kinds = kinds ?: DEFAULT_KINDS,
                exempt = f["exempt"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
        }
    }
}
