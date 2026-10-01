package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Building blocks for tunnel rules. */
object Rules {
    /** A state rule: one draft per subject whose observations satisfy [test]. */
    fun perSubject(kind: String, severity: Severity, test: (subject: String, obs: List<Observation>) -> String?): FindingRule =
        FindingRule { ctx ->
            ctx.bySubject().mapNotNull { (subject, obs) ->
                test(subject, obs)?.let { FindingDraft(ctx.tunnelId, subject, kind, severity, it) }
            }
        }

    /** A change rule: a sticky draft for each diff entry [test] accepts. Never fires on the first scan. */
    fun onChange(kind: String, severity: Severity, test: (DiffEntry) -> String?): FindingRule =
        FindingRule { ctx ->
            if (ctx.isFirstScan) emptyList()
            else ctx.diff.mapNotNull { e -> test(e)?.let { FindingDraft(ctx.tunnelId, e.key.subject, kind, severity, it, sticky = true) } }
        }

    /** A change rule limited to observations whose key starts with [keyPrefix]. */
    fun onAdded(keyPrefix: String, kind: String, severity: Severity, evidence: (Observation) -> String): FindingRule =
        onChange(kind, severity) { e -> (e as? DiffEntry.Added)?.observation?.takeIf { it.key.startsWith(keyPrefix) }?.let(evidence) }
}
