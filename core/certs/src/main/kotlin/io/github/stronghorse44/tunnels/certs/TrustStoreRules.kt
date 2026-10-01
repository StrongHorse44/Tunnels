package io.github.stronghorse44.tunnels.certs

import io.github.stronghorse44.tunnels.certs.TrustStoreKeys.value
import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Rules of the trust store tunnel. Every finding they produce gets the "Trusted credentials" actions. */
object TrustStoreRules {
    const val USER_CA_INSTALLED = "USER_CA_INSTALLED"
    const val CA_ADDED = "CA_ADDED"
    const val CA_REMOVED = "CA_REMOVED"
    const val DISTRUSTED_CA = "DISTRUSTED_CA"

    /** State: one WARN per user-installed CA, cleared when the user removes it. */
    val userCaInstalled: FindingRule = Rules.perSubject(USER_CA_INSTALLED, Severity.WARN) { subject, obs ->
        if (subject == TrustStoreKeys.SUMMARY || obs.value(TrustStoreKeys.SOURCE) != TrustStoreKeys.SOURCE_USER) null
        else "User-installed certificate authority: ${label(obs)} — apps that trust user CAs can be intercepted"
    }

    /** State: CRITICAL for any CA on the [DistrustedRoots] list, system or user. */
    val distrustedCa: FindingRule = Rules.perSubject(DISTRUSTED_CA, Severity.CRITICAL) { subject, obs ->
        if (subject == TrustStoreKeys.SUMMARY) return@perSubject null
        val match = DistrustedRoots.match(obs.value(TrustStoreKeys.FINGERPRINT), obs.value(TrustStoreKeys.SUBJECT), obs.value(TrustStoreKeys.ORG))
            ?: return@perSubject null
        "Distrusted certificate authority: ${label(obs)} — ${match.reason}"
    }

    /** Change, sticky: a CA that was not in the previous snapshot. CRITICAL when user-installed, WARN for a system CA (OS update or tampering). */
    val caAdded: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val current = ctx.bySubject()
        ctx.diff.filterIsInstance<DiffEntry.Added>()
            .filter { it.observation.key == TrustStoreKeys.FINGERPRINT && it.key.subject != TrustStoreKeys.SUMMARY }
            .map { added ->
                val subject = added.key.subject
                val obs = current[subject].orEmpty()
                val user = TrustStoreKeys.isUserAlias(subject)
                FindingDraft(
                    tunnelId = ctx.tunnelId,
                    subject = subject,
                    kind = CA_ADDED,
                    severity = if (user) Severity.CRITICAL else Severity.WARN,
                    evidence = if (user) "New user-installed certificate authority since the last scan: ${label(obs)} — someone with access to this phone added it, or an app asked you to"
                    else "New system certificate authority since the last scan: ${label(obs)} — expected after an OS update, suspicious otherwise",
                    sticky = true,
                )
            }
    }

    /** Change, sticky INFO: a CA that disappeared since the previous snapshot. */
    val caRemoved: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val removed = ctx.diff.filterIsInstance<DiffEntry.Removed>().filter { it.key.subject != TrustStoreKeys.SUMMARY }
        val bySubject = removed.groupBy { it.key.subject }
        bySubject.filter { (_, entries) -> entries.any { it.observation.key == TrustStoreKeys.FINGERPRINT } }
            .map { (subject, entries) ->
                val before = entries.map { it.observation }
                val source = if (TrustStoreKeys.isUserAlias(subject)) "User-installed" else "System"
                FindingDraft(ctx.tunnelId, subject, CA_REMOVED, Severity.INFO, "$source certificate authority removed since the last scan: ${label(before)}", sticky = true)
            }
    }

    val all: List<FindingRule> = listOf(userCaInstalled, distrustedCa, caAdded, caRemoved)

    /** `Subject CN (E271773B…4275C265)` from one subject's observations. */
    fun label(obs: List<Observation>): String {
        val name = obs.value(TrustStoreKeys.SUBJECT)?.takeIf { it.isNotBlank() } ?: "unnamed certificate"
        val fp = obs.value(TrustStoreKeys.FINGERPRINT)?.let(CertSummary::shortForm)
        return if (fp != null) "$name ($fp)" else name
    }
}
