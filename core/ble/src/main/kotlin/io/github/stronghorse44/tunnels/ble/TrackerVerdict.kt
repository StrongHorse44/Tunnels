package io.github.stronghorse44.tunnels.ble

/** Everything the panel shows about one pseudonymous tracker identity, read back from a snapshot's observations. */
data class IdentityFacts(
    val type: TrackerType,
    val key: String,
    val state: TrackerState,
    val scans: Int,
    val sightings: Int,
    val spanMinutes: Long,
    val firstSeen: Long?,
    val lastSeen: Long?,
    val rssiAvg: Int?,
    val rssiLast: Int?,
    val battery: String?,
    val kind: String?,
    val seenThisScan: Boolean,
    val muted: Boolean,
) {
    val proximityLast: Proximity? get() = rssiLast?.let(Proximity::of)
    val proximityAvg: Proximity? get() = rssiAvg?.let(Proximity::of)

    companion object {
        /** Reads a device subject's facts (key → value) as [SurroundingsKeys.bleObservations] wrote them. */
        fun from(type: TrackerType, key: String, facts: Map<String, String>): IdentityFacts = IdentityFacts(
            type = type,
            key = key,
            state = TrackerState.bySlug(facts[SurroundingsKeys.STATE]),
            scans = facts[SurroundingsKeys.SEEN_SESSIONS]?.toIntOrNull() ?: 0,
            sightings = facts[SurroundingsKeys.SEEN_COUNT]?.toIntOrNull() ?: 0,
            spanMinutes = facts[SurroundingsKeys.SEEN_SPAN]?.toLongOrNull() ?: 0L,
            firstSeen = facts[SurroundingsKeys.SEEN_FIRST]?.toLongOrNull(),
            lastSeen = facts[SurroundingsKeys.SEEN_LAST]?.toLongOrNull(),
            rssiAvg = facts[SurroundingsKeys.RSSI_AVG]?.toIntOrNull(),
            rssiLast = facts[SurroundingsKeys.RSSI_LAST]?.toIntOrNull(),
            battery = facts[SurroundingsKeys.BATTERY],
            kind = facts[SurroundingsKeys.KIND],
            seenThisScan = facts[SurroundingsKeys.SEEN_THIS_SCAN] == "true",
            muted = facts[SurroundingsKeys.MUTED] == "true",
        )
    }
}

/** How far a tracker family is from the "following you" threshold. */
data class FollowingProgress(val scans: Int, val spanMinutes: Long) {
    val scansNeeded: Int get() = FollowingHeuristic.MIN_SESSIONS
    val minutesNeeded: Long get() = FollowingHeuristic.MIN_SPAN_MINUTES
    val scanFraction: Float get() = (scans.toFloat() / scansNeeded).coerceIn(0f, 1f)
    val minuteFraction: Float get() = (spanMinutes.toFloat() / minutesNeeded).coerceIn(0f, 1f)
    val reached: Boolean get() = scans >= scansNeeded && spanMinutes >= minutesNeeded

    /** "1 of 3 scans · 0 of 30 min". */
    val label: String get() = "${scans.coerceAtMost(scansNeeded)} of $scansNeeded scans · ${spanMinutes.coerceAtMost(minutesNeeded)} of $minutesNeeded min"
}

/** Plain-language sentences about one identity. Every string here is shown to the user verbatim. */
object TrackerVerdict {
    /** "Seen in 1 scan over 0 min." */
    fun seen(scans: Int, spanMinutes: Long): String = "Seen in $scans scan${if (scans == 1) "" else "s"} over ${minutes(spanMinutes)}."

    /**
     * The verdict line under an identity: what was seen, where the family stands against the following
     * threshold and what its state means. [typeProgress] is the family's progress (rotating identities make
     * one tag look like several, so the threshold is judged per family); [level] is the family's assessment.
     */
    fun line(
        facts: IdentityFacts,
        typeProgress: FollowingProgress,
        level: FollowingLevel,
        /** The family's separated-only counts, which are what the CRITICAL rule judged; default to the overall ones. */
        separatedScans: Int = typeProgress.scans,
        separatedMinutes: Long = typeProgress.spanMinutes,
    ): String = buildString {
        append(seen(facts.scans, facts.spanMinutes))
        append(' ')
        when {
            facts.muted -> append("Muted as a known tracker: it is listed but never flagged.")
            level == FollowingLevel.CRITICAL -> append("Flagged as following you: an Apple tag away from its owner was with you across $separatedScans scans over ${minutes(separatedMinutes)}.")
            level == FollowingLevel.WARN -> append("Flagged as following you: this family has been with you in ${typeProgress.scans} scans over ${minutes(typeProgress.spanMinutes)}, past the threshold of ${FollowingHeuristic.thresholdText}.")
            else -> {
                append("Flagged as following you only after ${FollowingHeuristic.thresholdText}")
                if (typeProgress.scans != facts.scans || typeProgress.spanMinutes != facts.spanMinutes) {
                    append(" (this family together: ${typeProgress.label})")
                } else {
                    append(" (now ${typeProgress.label})")
                }
                append('.')
            }
        }
        append(' ')
        append(stateExplanation(facts.type, facts.state))
    }

    /** What the state means for the user, without claiming more than the advertisement says. */
    fun stateExplanation(type: TrackerType, state: TrackerState): String = when (state) {
        TrackerState.WITH_OWNER ->
            "It reports being near its owner, which is usually benign: the owner's phone is close by (someone in the room, on the bus, or in the next car)."
        TrackerState.SEPARATED ->
            "It reports being away from its owner. A lost item looks like this, and so does a planted tag; what matters is whether it keeps turning up as you move."
        TrackerState.UNKNOWN ->
            if (type == TrackerType.APPLE_FINDMY) "Its advertisement did not say whether its owner is near."
            else "${type.label} tags do not say whether their owner is near; Tunnels can only count how often one recurs."
    }

    /** "0 min", "45 min", "1 h 20 min", "3 d". Short form for the dense verdict line. */
    fun minutes(m: Long): String {
        if (m < 60) return "$m min"
        val h = m / 60
        if (h < 48) {
            val rest = m % 60
            return "$h h" + if (rest > 0) " $rest min" else ""
        }
        return "${h / 24} d"
    }
}

/** The one line at the top of the tracker section, and the monitor's live counterpart. */
object ThreatSummary {
    /**
     * "2 identities nearby: 1 near its owner, 1 separated (seen 1×, not yet following)". The unit is the
     * pseudonymous identity, as everywhere else (one tag rotates through several). Identities seen in this
     * scan are "nearby"; the rest come from the 30-day history and are counted separately so the number
     * never claims more than the last scan heard. [levelByType] is each family's assessment, [scansByType]
     * each family's scan count (the "seen N×" of a separated or unknown group), [unlisted] the identities
     * counted but not listed.
     */
    fun line(
        identities: List<IdentityFacts>,
        levelByType: Map<TrackerType, FollowingLevel>,
        scansByType: Map<TrackerType, Int> = emptyMap(),
        unlisted: Int = 0,
    ): String {
        val total = identities.size + unlisted
        if (total == 0) return "No trackers seen in 30 days."
        val nearby = identities.count { it.seenThisScan }
        val head = when {
            nearby == total -> "${identities(total)} nearby"
            nearby == 0 -> "${identities(total)} in 30 days, none in this scan"
            else -> "${identities(total)} in 30 days, $nearby in this scan"
        }
        val groups = listOf(TrackerState.WITH_OWNER, TrackerState.SEPARATED, TrackerState.UNKNOWN).mapNotNull { state ->
            val group = identities.filter { it.state == state && !it.muted }
            if (group.isEmpty()) null else "${group.size} ${stateWords(state, group.size)}${qualifier(state, group, levelByType, scansByType)}"
        }
        val muted = identities.count { it.muted }
        val parts = groups + (if (muted > 0) listOf("$muted muted") else emptyList()) + (if (unlisted > 0) listOf("$unlisted not listed") else emptyList())
        return "$head: ${parts.joinToString(", ")}"
    }

    /** The monitor's notification line, same vocabulary: "3 identities since start: 1 near its owner, 2 separated". */
    fun monitorLine(byState: Map<TrackerState, Int>): String {
        val total = byState.values.sum()
        if (total == 0) return "no trackers yet"
        val parts = listOf(TrackerState.WITH_OWNER, TrackerState.SEPARATED, TrackerState.UNKNOWN)
            .mapNotNull { s -> byState[s]?.takeIf { it > 0 }?.let { "$it ${stateWords(s, it)}" } }
        return "${identities(total)} since start: ${parts.joinToString(", ")}"
    }

    fun identities(n: Int): String = "$n identit${if (n == 1) "y" else "ies"}"

    private fun stateWords(state: TrackerState, n: Int): String = when (state) {
        TrackerState.WITH_OWNER -> if (n == 1) "near its owner" else "near their owners"
        TrackerState.SEPARATED -> "separated"
        TrackerState.UNKNOWN -> "state unknown"
    }

    /**
     * Near-owner tags carry no qualifier: the owner is right there. The others say where they stand against
     * the threshold, counting the family's scans (the unit the rule judges), not one rotating identity's.
     */
    private fun qualifier(state: TrackerState, group: List<IdentityFacts>, levelByType: Map<TrackerType, FollowingLevel>, scansByType: Map<TrackerType, Int>): String {
        if (state == TrackerState.WITH_OWNER) return ""
        val following = group.any { (levelByType[it.type] ?: FollowingLevel.NONE) != FollowingLevel.NONE }
        if (following) return " (following you)"
        val most = group.maxOf { maxOf(scansByType[it.type] ?: 0, it.scans) }
        return " (seen ${most}×, not yet following)"
    }
}
