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
    /** Scans and minutes in which this identity said it was away from its owner: what the CRITICAL rule judges. */
    val separatedScans: Int = 0,
    val separatedMinutes: Long = 0L,
    /** Seen within [FollowingHeuristic.RECENT_DAYS] days; older identities are history and never flagged. */
    val recent: Boolean = true,
) {
    val proximityLast: Proximity? get() = rssiLast?.let(Proximity::of)
    val proximityAvg: Proximity? get() = rssiAvg?.let(Proximity::of)

    /** This identity's standing against the following threshold, from its own counts. */
    val progress: FollowingProgress get() = FollowingProgress(scans, spanMinutes)

    /** The identity's own counts against the threshold, before mutes and the recency gate. */
    val assessed: FollowingLevel get() = FollowingHeuristic.assess(type, scans, spanMinutes, separatedScans, separatedMinutes)

    /** The same level the rule acts on: never for a muted identity or one not seen recently. */
    val level: FollowingLevel get() = if (muted || !recent) FollowingLevel.NONE else assessed

    /** Close to following in the shared sense ([FollowingHeuristic.isClose]): unmuted, recent, real progress. */
    val close: Boolean get() = !muted && recent && FollowingHeuristic.isClose(scans, spanMinutes)

    /** For ordering rows: closest to following first (muted ones last), see [order]. */
    val closeness: Double get() = if (muted) -1.0 else FollowingHeuristic.closeness(level, scans, spanMinutes)

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
            separatedScans = facts[SurroundingsKeys.SEEN_SESSIONS_SEPARATED]?.toIntOrNull() ?: 0,
            separatedMinutes = facts[SurroundingsKeys.SEEN_SPAN_SEPARATED]?.toLongOrNull() ?: 0L,
            recent = facts[SurroundingsKeys.SEEN_RECENT] != "false",
        )

        /** Identity rows: closest to following first, then the most recently seen. */
        val order: Comparator<IdentityFacts> =
            compareByDescending<IdentityFacts> { it.closeness }.thenByDescending { it.lastSeen ?: 0L }.thenBy { it.key }
    }
}

/** How far one identity is from the "following you" threshold. */
data class FollowingProgress(val scans: Int, val spanMinutes: Long) {
    val scansNeeded: Int get() = FollowingHeuristic.MIN_SESSIONS
    val minutesNeeded: Long get() = FollowingHeuristic.MIN_SPAN_MINUTES
    val scanFraction: Float get() = (scans.toFloat() / scansNeeded).coerceIn(0f, 1f)
    val minuteFraction: Float get() = (spanMinutes.toFloat() / minutesNeeded).coerceIn(0f, 1f)
    val reached: Boolean get() = scans >= scansNeeded && spanMinutes >= minutesNeeded

    /** "1 of 3 scans · 0 of 30 min". */
    val label: String get() = "$scansHint · ${spanMinutes.coerceIn(0L, minutesNeeded)} of $minutesNeeded min"

    /** "2 of 3 scans": the small hint on an identity row. */
    val scansHint: String get() = "${scans.coerceIn(0, scansNeeded)} of $scansNeeded scans"
}

/** Plain-language sentences about one identity. Every string here is shown to the user verbatim. */
object TrackerVerdict {
    /** "Seen in 1 scan over 0 min." */
    fun seen(scans: Int, spanMinutes: Long): String = "Seen in $scans scan${if (scans == 1) "" else "s"} over ${minutes(spanMinutes)}."

    /**
     * The verdict line under an identity: what was seen, where this identity stands against the following
     * threshold (judged per identity, see [FollowingHeuristic]) and what its state means.
     */
    fun line(facts: IdentityFacts): String = buildString {
        append(seen(facts.scans, facts.spanMinutes))
        append(' ')
        when {
            facts.muted -> append("Muted as a known tracker: it is listed but never flagged.")
            !facts.recent && facts.assessed != FollowingLevel.NONE -> append(
                "It was past the following threshold, but has not been seen in the last ${FollowingHeuristic.RECENT_DAYS} days, " +
                    "so it is kept as history and no longer flagged.",
            )
            facts.level == FollowingLevel.CRITICAL -> append(
                "Flagged as following you: this identity, reporting itself away from its owner, was with you across " +
                    "${facts.separatedScans} scans over ${minutes(facts.separatedMinutes)}.",
            )
            facts.level == FollowingLevel.WARN -> append(
                "Flagged as following you: this identity was with you in ${facts.scans} scans over ${minutes(facts.spanMinutes)}, " +
                    "past the threshold of ${FollowingHeuristic.thresholdText}.",
            )
            else -> append("Flagged as following you only after ${FollowingHeuristic.thresholdText} by this same identity (now ${facts.progress.label}).")
        }
        append(' ')
        append(stateExplanation(facts.type, facts.state))
    }

    /** "away from owner", "near its owner", "state unknown": the short state words of rows and findings. */
    fun stateLabel(state: TrackerState): String = when (state) {
        TrackerState.SEPARATED -> "away from owner"
        TrackerState.WITH_OWNER -> "near its owner"
        TrackerState.UNKNOWN -> "state unknown"
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

/**
 * One tracker family as its card shows it: a summary of its identities. The family is never judged as
 * following; its card names the identity closest to following instead.
 */
data class FamilyFacts(
    val type: TrackerType,
    val identities: Int,
    val withOwner: Int,
    val separated: Int,
    val unknown: Int,
    val scans: Int,
    val spanMinutes: Long,
    /** The unmuted identity closest to following, or null when none was seen in more than one scan. */
    val closestKey: String?,
    val closestScans: Int,
    val closestMinutes: Long,
    /** Unmuted identities at WARN or above. */
    val following: Int,
    /** A family-level mute from before v3, still honoured until it expires. */
    val muted: Boolean,
) {
    val closestProgress: FollowingProgress? get() = closestKey?.let { FollowingProgress(closestScans, closestMinutes) }

    companion object {
        /**
         * Reads a family subject's facts as [SurroundingsKeys.bleObservations] wrote them. A snapshot written
         * before v3 lacks the per-state and closest keys; they are then derived from the listed [listed] identities.
         */
        fun from(type: TrackerType, facts: Map<String, String>, listed: List<IdentityFacts>): FamilyFacts {
            fun int(k: String) = facts[k]?.toIntOrNull()
            val candidates = listed.filter { it.close }.sortedWith(IdentityFacts.order)
            val closestKey = facts[SurroundingsKeys.CLOSEST_KEY]?.takeIf { it != SurroundingsKeys.NONE }
                ?: if (SurroundingsKeys.CLOSEST_KEY in facts) null else candidates.firstOrNull()?.key
            val closest = listed.firstOrNull { it.key == closestKey }
            val identities = int(SurroundingsKeys.DEVICES) ?: listed.size
            val withOwner = int(SurroundingsKeys.DEVICES_WITH_OWNER) ?: listed.count { it.state == TrackerState.WITH_OWNER }
            val separated = int(SurroundingsKeys.DEVICES_SEPARATED) ?: listed.count { it.state == TrackerState.SEPARATED }
            // Pre-v3 fallback: identities beyond the listed ones have no known state, so the counts still sum to the total.
            val unknown = int(SurroundingsKeys.DEVICES_UNKNOWN) ?: (identities - withOwner - separated).coerceAtLeast(0)
            return FamilyFacts(
                type = type,
                identities = identities,
                withOwner = withOwner,
                separated = separated,
                unknown = unknown,
                scans = int(SurroundingsKeys.SEEN_SESSIONS) ?: 0,
                spanMinutes = facts[SurroundingsKeys.SEEN_SPAN]?.toLongOrNull() ?: 0L,
                closestKey = closestKey,
                closestScans = int(SurroundingsKeys.CLOSEST_SESSIONS) ?: closest?.scans ?: 0,
                closestMinutes = facts[SurroundingsKeys.CLOSEST_SPAN]?.toLongOrNull() ?: closest?.spanMinutes ?: 0L,
                following = int(SurroundingsKeys.FOLLOWING_COUNT) ?: listed.count { it.level != FollowingLevel.NONE },
                muted = facts[SurroundingsKeys.MUTED] == "true",
            )
        }
    }
}

/** The family card's lines. Every string here is shown to the user verbatim. */
object FamilySummary {
    /** "35 identities · 17 scans over 2 h 7 min". */
    fun headline(f: FamilyFacts): String =
        "${ThreatSummary.identities(f.identities)} · ${f.scans} scan${if (f.scans == 1) "" else "s"} over ${TrackerVerdict.minutes(f.spanMinutes)}"

    /** "3 near their owners · 30 separated · 2 state unknown": identities by their latest state. */
    fun states(f: FamilyFacts): String =
        listOf(TrackerState.WITH_OWNER to f.withOwner, TrackerState.SEPARATED to f.separated, TrackerState.UNKNOWN to f.unknown)
            .filter { it.second > 0 }
            .joinToString(" · ") { (s, n) -> "$n ${ThreatSummary.stateWords(s, n)}" }
            .ifEmpty { "no identities listed" }

    /**
     * "Closest to following: deadbeef, 2 of 3 scans · 25 of 30 min", "Closest to following: none close", or,
     * once an identity crossed the threshold, "Following you: deadbeef, 3 of 3 scans · 30 of 30 min".
     */
    fun closest(f: FamilyFacts): String {
        val p = f.closestProgress ?: return "Closest to following: none close"
        if (f.following > 0) {
            val more = f.following - 1
            return "Following you: ${f.closestKey}, ${p.label}" + if (more > 0) " (and $more more)" else ""
        }
        return "Closest to following: ${f.closestKey}, ${p.label}"
    }
}

/** The one line at the top of the tracker section, and the monitor's live counterpart. */
object ThreatSummary {
    /**
     * "2 identities nearby: 1 near its owner, 1 separated (seen 1×, not yet following)". The unit is the
     * pseudonymous identity, as everywhere else, and each identity is judged on its own counts. Identities
     * seen in this scan are "nearby"; the rest come from the 30-day history and are counted separately so
     * the number never claims more than the last scan heard. [unlisted] counts identities not listed.
     */
    fun line(identities: List<IdentityFacts>, unlisted: Int = 0): String {
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
            if (group.isEmpty()) null else "${group.size} ${stateWords(state, group.size)}${qualifier(state, group)}"
        }
        val muted = identities.count { it.muted }
        val parts = groups + (if (muted > 0) listOf("$muted muted") else emptyList()) + (if (unlisted > 0) listOf("$unlisted not listed") else emptyList())
        return "$head: ${parts.joinToString(", ")}"
    }

    /**
     * The monitor's notification line, same vocabulary: "3 identities since start: 1 near its owner, 2 separated
     * · 1 identity close to following". [close] counts identities seen in more than one window but not yet over
     * the threshold, [following] those over it.
     */
    fun monitorLine(byState: Map<TrackerState, Int>, close: Int = 0, following: Int = 0): String {
        val total = byState.values.sum()
        if (total == 0) return "no trackers yet"
        val parts = listOf(TrackerState.WITH_OWNER, TrackerState.SEPARATED, TrackerState.UNKNOWN)
            .mapNotNull { s -> byState[s]?.takeIf { it > 0 }?.let { "$it ${stateWords(s, it)}" } }
        return "${identities(total)} since start: ${parts.joinToString(", ")}" +
            (if (following > 0) " · ${identities(following)} following you" else "") +
            (if (close > 0) " · ${identities(close)} close to following" else "")
    }

    fun identities(n: Int): String = "$n identit${if (n == 1) "y" else "ies"}"

    fun stateWords(state: TrackerState, n: Int): String = when (state) {
        TrackerState.WITH_OWNER -> if (n == 1) "near its owner" else "near their owners"
        TrackerState.SEPARATED -> "separated"
        TrackerState.UNKNOWN -> "state unknown"
    }

    /**
     * A group with an identity over the threshold says so whatever its state. Otherwise near-owner tags carry
     * no qualifier (the owner is right there) and the others say how often their most-seen identity was seen.
     */
    private fun qualifier(state: TrackerState, group: List<IdentityFacts>): String {
        if (group.any { it.level != FollowingLevel.NONE }) return " (following you)"
        if (state == TrackerState.WITH_OWNER) return ""
        return " (seen ${group.maxOf { it.scans }}×, not yet following)"
    }
}
