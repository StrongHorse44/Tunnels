package io.github.stronghorse44.tunnels.runtime

import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.store.SeverityCount

/** Per-tunnel finding counts for station labels. */
data class TunnelSummary(val counts: Map<Severity, Int>) {
    val total: Int get() = counts.values.sum()
    val worst: Severity? get() = Severity.entries.lastOrNull { (counts[it] ?: 0) > 0 }

    /** e.g. "3 findings · 1 WARN" */
    fun label(): String? {
        if (total == 0) return null
        val worst = worst ?: return null
        return "$total finding${if (total == 1) "" else "s"} · ${counts[worst]} ${worst.name}"
    }

    companion object {
        fun from(rows: List<SeverityCount>): Map<String, TunnelSummary> = rows.groupBy { it.tunnelId }.mapValues { (_, list) ->
            TunnelSummary(list.mapNotNull { r -> runCatching { Severity.valueOf(r.severity) }.getOrNull()?.let { it to r.count } }.toMap())
        }
    }
}
