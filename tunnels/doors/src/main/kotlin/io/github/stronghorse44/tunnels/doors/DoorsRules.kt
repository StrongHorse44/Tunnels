package io.github.stronghorse44.tunnels.doors

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Findings of the doors tunnel. Plain Kotlin so the rules run under JUnit without a device. */
object DoorsRules {
    const val UNPROTECTED_EXPORTS = "UNPROTECTED_EXPORTS"
    const val EXPORTED_PROVIDER_GRANT_URI = "EXPORTED_PROVIDER_GRANT_URI"
    const val LINKS_CHANGED = "LINKS_CHANGED"
    const val NEW_LINK_HANDLER = "NEW_LINK_HANDLER"

    /** Above this many unprotected exported components a non-system app is a WARN. */
    const val WARN_THRESHOLD = 10

    /**
     * Exported components reachable by any app. One unprotected component is normal (the launcher
     * activity), so a user app is a NOTICE from two up and a WARN past [WARN_THRESHOLD]; system apps
     * only past the threshold, to keep the list readable.
     */
    val unprotectedExports = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            val total = obs.count(DoorsKeys.EXPORTED_UNPROTECTED)
            val system = obs.isSystem()
            val severity = when {
                total > WARN_THRESHOLD && !system -> Severity.WARN
                total > WARN_THRESHOLD || (total > 1 && !system) -> Severity.NOTICE
                else -> return@mapNotNull null
            }
            FindingDraft(ctx.tunnelId, subject, UNPROTECTED_EXPORTS, severity, describeUnprotected(total, obs))
        }
    }

    /** "14 exported components without a permission (5 activities, 3 services, 6 receivers)". */
    fun describeUnprotected(total: Int, obs: List<Observation>): String {
        val parts = listOf(
            obs.count(DoorsKeys.UNPROTECTED_ACTIVITIES) to "activit",
            obs.count(DoorsKeys.UNPROTECTED_SERVICES) to "service",
            obs.count(DoorsKeys.UNPROTECTED_RECEIVERS) to "receiver",
            obs.count(DoorsKeys.UNPROTECTED_PROVIDERS) to "provider",
        ).filter { it.first > 0 }.map { (n, word) -> "$n ${plural(n, word)}" }
        val head = "$total exported ${if (total == 1) "component" else "components"} without a permission"
        return if (parts.isEmpty()) head else "$head (${parts.joinToString(", ")})"
    }

    /** A user app's exported provider can hand out URI access to its data. */
    val exportedProviderGrantUri = Rules.perSubject(EXPORTED_PROVIDER_GRANT_URI, Severity.WARN) { _, obs ->
        val n = obs.count(DoorsKeys.PROVIDER_GRANT_URI)
        if (n == 0 || obs.isSystem()) null
        else "$n exported content ${if (n == 1) "provider" else "providers"} can grant other apps access to ${if (n == 1) "its" else "their"} data (grantUriPermissions)"
    }

    /** The set of verified or chosen link domains changed size. */
    val linksChanged = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        ctx.diff.filterIsInstance<DiffEntry.Changed>()
            .filter { it.after.key == DoorsKeys.LINKS_VERIFIED || it.after.key == DoorsKeys.LINKS_SELECTED }
            .groupBy { it.after.subject }
            .map { (subject, changes) ->
                val text = changes.sortedBy { it.after.key }.joinToString("; ") { c ->
                    val what = if (c.after.key == DoorsKeys.LINKS_VERIFIED) "Verified link domains" else "Chosen link domains"
                    "$what ${c.before.value} → ${c.after.value}"
                }
                FindingDraft(ctx.tunnelId, subject, LINKS_CHANGED, Severity.INFO, text, sticky = true)
            }
    }

    /** An app started offering to open http/https links. */
    val newLinkHandler = Rules.onAdded(DoorsKeys.HANDLER_BROWSER, NEW_LINK_HANDLER, Severity.NOTICE) { "Now offers to open http and https links" }

    val all: List<FindingRule> = listOf(unprotectedExports, exportedProviderGrantUri, linksChanged, newLinkHandler)

    private fun List<Observation>.count(key: String): Int = firstOrNull { it.key == key }?.value?.toIntOrNull() ?: 0

    private fun List<Observation>.isSystem(): Boolean = any { it.key == DoorsKeys.APP_SYSTEM && it.value == DoorsKeys.TRUE }

    private fun plural(n: Int, stem: String) = when {
        stem == "activit" -> if (n == 1) "activity" else "activities"
        n == 1 -> stem
        else -> stem + "s"
    }
}
