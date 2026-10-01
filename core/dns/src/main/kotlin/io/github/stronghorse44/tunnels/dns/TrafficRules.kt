package io.github.stronghorse44.tunnels.dns

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the traffic tunnel. Pure functions over observations, unit-tested here. */
object TrafficRules {
    const val TRACKER_DOMAINS = "TRACKER_DOMAINS"
    const val TALKATIVE_APP = "TALKATIVE_APP"
    const val NEW_TRACKER_DOMAIN = "NEW_TRACKER_DOMAIN"
    const val OTHER_VPN_ACTIVE = "OTHER_VPN_ACTIVE"

    /** This many distinct tracking domains raise TRACKER_DOMAINS from NOTICE to WARN. */
    const val MANY_TRACKERS = 5
    /** Distinct domains in one logging interval that make an app "talkative". */
    const val TALKATIVE_DOMAINS = 40

    /** State rule: the app contacted known tracking domains. NOTICE, WARN at [MANY_TRACKERS]. */
    val trackerDomains: FindingRule = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            if (subject == TrafficKeys.SUMMARY) return@mapNotNull null
            val count = TrafficKeys.intValue(obs, TrafficKeys.TRACKER_DOMAINS30) ?: 0
            if (count <= 0) return@mapNotNull null
            val names = TrafficKeys.list(TrafficKeys.value(obs, TrafficKeys.TRACKER_TOP))
            val severity = if (count >= MANY_TRACKERS) Severity.WARN else Severity.NOTICE
            FindingDraft(ctx.tunnelId, subject, TRACKER_DOMAINS, severity, "Contacted ${plural(count, "tracking domain")} in the last 30 days: ${describe(names, count)}.")
        }
    }

    /** State INFO: the app looked up a lot of different domains in one session. */
    val talkativeApp: FindingRule = Rules.perSubject(TALKATIVE_APP, Severity.INFO) { subject, obs ->
        if (subject == TrafficKeys.SUMMARY) return@perSubject null
        val domains = TrafficKeys.intValue(obs, TrafficKeys.DOMAINS30) ?: return@perSubject null
        if (domains < TALKATIVE_DOMAINS) return@perSubject null
        val queries = TrafficKeys.intValue(obs, TrafficKeys.QUERIES30) ?: 0
        "Looked up at least $domains different domains in one logging session ($queries queries in 30 days)."
    }

    /** Sticky NOTICE: the app contacts more tracking domains than at the previous scan. */
    val newTrackerDomain: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val topChanges = ctx.diff.filterIsInstance<DiffEntry.Changed>().filter { it.key.key == TrafficKeys.TRACKER_TOP }.associateBy { it.key.subject }
        ctx.diff.asSequence()
            .filterIsInstance<DiffEntry.Changed>()
            .filter { it.key.key == TrafficKeys.TRACKER_DOMAINS30 && it.key.subject != TrafficKeys.SUMMARY }
            .mapNotNull { c ->
                val before = c.before.value.toIntOrNull() ?: return@mapNotNull null
                val after = c.after.value.toIntOrNull() ?: return@mapNotNull null
                if (after <= before) return@mapNotNull null
                val added = topChanges[c.key.subject]?.let { TrafficKeys.list(it.after.value) - TrafficKeys.list(it.before.value).toSet() }.orEmpty()
                val detail = if (added.isEmpty()) "" else " New: ${added.joinToString(", ")}."
                FindingDraft(ctx.tunnelId, c.key.subject, NEW_TRACKER_DOMAIN, Severity.NOTICE, "Now contacts ${plural(after, "tracking domain")}, up from $before.$detail", sticky = true)
            }
            .toList()
    }

    /** State INFO on the summary subject: another VPN is connected, so no session can start. */
    val otherVpnActive: FindingRule = Rules.perSubject(OTHER_VPN_ACTIVE, Severity.INFO) { subject, obs ->
        if (subject != TrafficKeys.SUMMARY) return@perSubject null
        if (TrafficKeys.value(obs, TrafficKeys.VPN_OTHER_ACTIVE) != "true") return@perSubject null
        "Another VPN (e.g. Surfshark) is connected. Pause it before starting a Traffic session; Tunnels never disconnects it for you."
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(trackerDomains, talkativeApp, newTrackerDomain, otherVpnActive)

    /** "a.com, b.net and 3 more" from the listed names and the true total. */
    fun describe(names: List<String>, total: Int): String {
        if (names.isEmpty()) return "names not recorded"
        val rest = total - names.size
        return if (rest > 0) "${names.joinToString(", ")} and $rest more" else names.joinToString(", ")
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
