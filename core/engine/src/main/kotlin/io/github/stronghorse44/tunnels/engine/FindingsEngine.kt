package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Instant

/** What a tunnel's rule says about one diff entry, before timestamps and actions are attached. */
data class FindingDraft(
    val tunnelId: String,
    val subject: String,
    val kind: String,
    val severity: Severity,
    val evidence: String,
)

/** Per-tunnel rule turning diff entries into finding drafts. */
fun interface FindingRule {
    fun evaluate(entry: DiffEntry): FindingDraft?
}

object FindingsEngine {
    /**
     * Applies [rules] to [diff], attaches actions, and merges with [existing] findings so recurring
     * findings keep their firstSeen. Drafts with no actions are dropped: those belong in Explore.
     */
    fun derive(
        diff: List<DiffEntry>,
        rules: List<FindingRule>,
        actionsFor: (FindingDraft) -> List<FindingAction>,
        existing: Map<String, Finding>,
        now: Instant,
    ): List<Finding> {
        val drafts = diff.flatMap { entry -> rules.mapNotNull { it.evaluate(entry) } }
        return drafts.mapNotNull { draft ->
            val actions = actionsFor(draft)
            if (actions.isEmpty()) return@mapNotNull null
            val id = Finding.findingId(draft.tunnelId, draft.subject, draft.kind)
            Finding(
                tunnelId = draft.tunnelId,
                subject = draft.subject,
                kind = draft.kind,
                severity = draft.severity,
                firstSeen = existing[id]?.firstSeen ?: now,
                lastSeen = now,
                evidence = draft.evidence,
                actions = actions,
            )
        }.distinctBy { it.id }
    }
}
