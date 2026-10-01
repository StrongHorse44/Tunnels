package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.RuleContext
import java.time.Instant

/** Result of re-deriving one tunnel's findings after a scan. */
data class FindingsUpdate(
    /** Findings to insert or refresh. */
    val upserts: List<Finding>,
    /** Ids of findings that no longer hold (state findings whose state cleared). */
    val removals: List<String>,
)

object FindingsEngine {
    /**
     * Applies [rules] to [context], attaches actions, and merges with [existing] findings for the same
     * tunnel so recurring findings keep their firstSeen. Drafts with no actions are dropped: those belong
     * in Explore. Existing non-sticky findings that no rule re-produced are removed; sticky ones stay.
     */
    fun derive(
        context: RuleContext,
        rules: List<FindingRule>,
        actionsFor: (FindingDraft) -> List<FindingAction>,
        existing: Collection<Finding>,
        now: Instant,
    ): FindingsUpdate {
        val byId = existing.filter { it.tunnelId == context.tunnelId }.associateBy { it.id }
        val drafts = rules.flatMap { it.evaluate(context) }.distinctBy { Finding.findingId(it.tunnelId, it.subject, it.kind) }
        val upserts = drafts.mapNotNull { draft ->
            val actions = actionsFor(draft)
            if (actions.isEmpty()) return@mapNotNull null
            val id = Finding.findingId(draft.tunnelId, draft.subject, draft.kind)
            Finding(
                tunnelId = draft.tunnelId,
                subject = draft.subject,
                kind = draft.kind,
                severity = draft.severity,
                firstSeen = byId[id]?.firstSeen ?: now,
                lastSeen = now,
                evidence = draft.evidence,
                actions = actions,
                sticky = draft.sticky,
            )
        }
        val kept = upserts.map { it.id }.toSet()
        val removals = byId.values.filter { !it.sticky && it.id !in kept }.map { it.id }
        return FindingsUpdate(upserts, removals)
    }
}
