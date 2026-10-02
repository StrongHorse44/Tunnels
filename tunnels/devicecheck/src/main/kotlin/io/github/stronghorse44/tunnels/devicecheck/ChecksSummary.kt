package io.github.stronghorse44.tunnels.devicecheck

/** The line at the top of the screen. Pure. */
object ChecksSummary {
    /** "1 fail · 2 warn · 3 to do · 9 ok", worst first, statuses with no checks left out. */
    fun line(groups: List<CheckGroup>): String {
        val counts = groups.flatMap { it.results }.groupingBy { it.status }.eachCount()
        val order = listOf(CheckStatus.FAIL, CheckStatus.WARN, CheckStatus.TODO, CheckStatus.NOTE, CheckStatus.PASS)
        return order.mapNotNull { s -> counts[s]?.let { "$it ${s.label}" } }.joinToString(" · ")
    }

    /** The worst status among [groups], or null when there are none. */
    fun worst(groups: List<CheckGroup>): CheckStatus? =
        groups.flatMap { it.results }.map { it.status }.maxByOrNull { rank(it) }

    private fun rank(s: CheckStatus) = when (s) {
        CheckStatus.PASS -> 0
        CheckStatus.NOTE -> 1
        CheckStatus.TODO -> 2
        CheckStatus.WARN -> 3
        CheckStatus.FAIL -> 4
    }
}
