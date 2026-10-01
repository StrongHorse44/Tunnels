package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the surroundings tunnel. Pure functions over observations, unit-tested here. */
object SurroundingsRules {
    const val TRACKER_FOLLOWING = "TRACKER_FOLLOWING"
    const val NEW_TRACKER_TYPE = "NEW_TRACKER_TYPE"
    const val OPEN_WIFI_CONNECTED = "OPEN_WIFI_CONNECTED"
    const val EVIL_TWIN_SUSPECT = "EVIL_TWIN_SUSPECT"
    const val CELL_DOWNGRADE = "CELL_DOWNGRADE"
    const val CELL_DOWNGRADED = "CELL_DOWNGRADED"

    private fun List<Observation>.v(key: String) = SurroundingsKeys.value(this, key)
    private fun List<Observation>.muted() = v(SurroundingsKeys.MUTED) == "true"

    /**
     * State rule on identity subjects (`tracker:<type>:<key>`): WARN when one identity was seen in 3+
     * sessions over 30+ minutes, CRITICAL for an Apple identity separated from its owner across 3+ sessions
     * over an hour. Family subjects are summaries and never carry this finding: strangers' tags of one
     * family add up in a crowd (see [FollowingHeuristic]). Muted identities, and every identity of a family
     * muted before v3, are skipped.
     */
    val trackerFollowing: FindingRule = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            val (type, key) = SurroundingsKeys.parseTrackerSubject(subject) ?: return@mapNotNull null
            if (key == null || obs.muted()) return@mapNotNull null
            val sessions = obs.v(SurroundingsKeys.SEEN_SESSIONS)?.toIntOrNull() ?: return@mapNotNull null
            val span = obs.v(SurroundingsKeys.SEEN_SPAN)?.toLongOrNull() ?: 0L
            val sepSessions = obs.v(SurroundingsKeys.SEEN_SESSIONS_SEPARATED)?.toIntOrNull() ?: 0
            val sepSpan = obs.v(SurroundingsKeys.SEEN_SPAN_SEPARATED)?.toLongOrNull() ?: 0L
            val state = TrackerState.bySlug(obs.v(SurroundingsKeys.STATE))
            when (FollowingHeuristic.assess(type, sessions, span, sepSessions, sepSpan)) {
                FollowingLevel.NONE -> null
                FollowingLevel.WARN -> FindingDraft(
                    ctx.tunnelId, subject, TRACKER_FOLLOWING, Severity.WARN,
                    "${type.label} identity $key was seen in $sessions separate scans over ${duration(span)} (${TrackerVerdict.stateLabel(state)}). " +
                        "$SAME_IDENTITY A tag that stays with you across places and hours may have been planted. " +
                        "If it is yours or a companion's, mute it; otherwise check bags, pockets and the car.",
                )
                FollowingLevel.CRITICAL -> FindingDraft(
                    ctx.tunnelId, subject, TRACKER_FOLLOWING, Severity.CRITICAL,
                    "Apple Find My identity $key, reporting itself away from its owner, was with you in $sepSessions separate scans over ${duration(sepSpan)}. " +
                        "$SAME_IDENTITY That is how an AirTag planted on a person behaves. Find it (it chirps when moved after a while), " +
                        "remove its battery, and keep it as evidence if you suspect stalking.",
                )
            }
        }
    }

    /** Said in every following finding, so nobody reads it as "many tags of this kind were around". */
    const val SAME_IDENTITY = "It is the same identity in every one of those scans, not different tags of the same kind."

    /** Sticky NOTICE: a tracker family appeared that no earlier scan had seen. */
    val newTrackerType: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val mutedSubjects = ctx.current.filter { it.key == SurroundingsKeys.MUTED && it.value == "true" }.map { it.subject }.toSet()
        ctx.diff.mapNotNull { e ->
            val added = (e as? DiffEntry.Added)?.observation ?: return@mapNotNull null
            if (added.key != SurroundingsKeys.DEVICES) return@mapNotNull null
            val (type, key) = SurroundingsKeys.parseTrackerSubject(added.subject) ?: return@mapNotNull null
            if (key != null || added.subject in mutedSubjects) return@mapNotNull null
            FindingDraft(
                ctx.tunnelId, added.subject, NEW_TRACKER_TYPE, Severity.NOTICE,
                "A ${type.label} tracker was near you for the first time. One sighting means little; Tunnels will warn if it keeps turning up.",
                sticky = true,
            )
        }
    }

    /** State NOTICE: the network the phone is on has no encryption. */
    val openWifiConnected: FindingRule = Rules.perSubject(OPEN_WIFI_CONNECTED, Severity.NOTICE) { subject, obs ->
        if (obs.v(SurroundingsKeys.WIFI_CURRENT) != "true" || obs.v(SurroundingsKeys.WIFI_SECURITY) != WifiSecurity.OPEN.slug) return@perSubject null
        "You are connected to \"$subject\", an open network: anyone nearby can read unencrypted traffic and see which sites you visit. " +
            "Prefer a secured network or a VPN."
    }

    /** State WARN: one network name looks like an impostor set-up. */
    val evilTwinSuspect: FindingRule = Rules.perSubject(EVIL_TWIN_SUSPECT, Severity.WARN) { subject, obs ->
        val reason = obs.v(SurroundingsKeys.WIFI_TWIN) ?: return@perSubject null
        "\"$subject\" looks like it may have an impostor: $reason. A fake access point with a familiar name can intercept traffic. " +
            "Forget the network if you do not need it, and do not enter passwords while on it."
    }

    /** State WARN: registered on 2G right now. */
    val cellDowngrade: FindingRule = Rules.perSubject(CELL_DOWNGRADE, Severity.WARN) { subject, obs ->
        if (subject != SurroundingsKeys.CELL_SUMMARY) return@perSubject null
        val tech = CellTech.bySlug(obs.v(SurroundingsKeys.CELL_TYPE))
        if (!CellHeuristics.isLegacy(tech)) return@perSubject null
        "The phone is on a 2G (GSM) cell. 2G does not verify the network and barely encrypts, which fake base stations exploit. " +
            "GrapheneOS can forbid 2G: Mobile network settings → Allow 2G off, or the LTE-only mode."
    }

    /** Sticky WARN: between two scans the phone dropped from 4G/5G to 2G/3G. */
    val cellDowngraded: FindingRule = Rules.onChange(CELL_DOWNGRADED, Severity.WARN) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        if (c.key.key != SurroundingsKeys.CELL_TYPE) return@onChange null
        val before = CellTech.bySlug(c.before.value)
        val after = CellTech.bySlug(c.after.value)
        if (!CellHeuristics.isDowngrade(before, after)) return@onChange null
        "Since the last scan the phone fell from ${before.generation} (${before.slug}) to ${after.generation} (${after.slug}). " +
            "That happens in poor coverage, but a fake base station forces it on purpose. GrapheneOS's LTE-only option in Mobile network settings prevents it."
    }

    val all: List<FindingRule> = listOf(trackerFollowing, newTrackerType, openWifiConnected, evilTwinSuspect, cellDowngrade, cellDowngraded)

    /** Minutes as a short phrase: "45 minutes", "1 hour 20 minutes", "3 days". */
    fun duration(minutes: Long): String {
        if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
        val hours = minutes / 60
        if (hours < 48) {
            val rest = minutes % 60
            return "$hours hour${if (hours == 1L) "" else "s"}" + if (rest > 0) " $rest minute${if (rest == 1L) "" else "s"}" else ""
        }
        val days = hours / 24
        return "$days day${if (days == 1L) "" else "s"}"
    }
}
