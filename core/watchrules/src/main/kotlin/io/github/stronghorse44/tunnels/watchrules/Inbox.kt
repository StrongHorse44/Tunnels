package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Instant

/** Order, filters and "new" for the findings inbox. Pure, so the inbox's behaviour is unit-tested. */
object Inbox {
    /** Settings key of the inbox's last visit (epoch millis). */
    const val LAST_VISIT_KEY = "inbox.lastVisit"

    enum class Filter(val label: String) {
        ALL("All"),
        URGENT("Warn and up"),
        NEW("New"),
    }

    /** Most severe first, then the most recently seen. */
    fun sort(findings: List<Finding>): List<Finding> =
        findings.sortedWith(compareByDescending<Finding> { it.severity }.thenByDescending { it.lastSeen }.thenBy { it.tunnelId }.thenBy { it.subject })

    /** First seen after the previous visit. Nothing is new on the very first visit, when [lastVisit] is null. */
    fun isNew(f: Finding, lastVisit: Instant?): Boolean = lastVisit != null && f.firstSeen.isAfter(lastVisit)

    fun filter(findings: List<Finding>, filter: Filter, lastVisit: Instant?, tunnel: String? = null): List<Finding> =
        findings.filter { f ->
            (tunnel == null || f.tunnelId == tunnel) && when (filter) {
                Filter.ALL -> true
                Filter.URGENT -> f.severity >= Severity.WARN
                Filter.NEW -> isNew(f, lastVisit)
            }
        }

    /** Open findings per severity, for the header. */
    fun counts(findings: List<Finding>): Map<Severity, Int> = findings.groupingBy { it.severity }.eachCount()
}
