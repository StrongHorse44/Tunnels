package io.github.stronghorse44.tunnels.ble

/** Radio access technology of the registered cell, ranked so a drop can be told from a change. */
enum class CellTech(val slug: String, val rank: Int, val generation: String) {
    NR("NR", 4, "5G"),
    LTE("LTE", 3, "4G"),
    UMTS("UMTS", 2, "3G"),
    GSM("GSM", 1, "2G"),
    UNKNOWN("unknown", 0, "?"),
    ;

    companion object {
        fun bySlug(slug: String?): CellTech = entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) } ?: UNKNOWN
    }
}

/** What a cell check keeps: the technology, the operator code, how many other cells were in view. */
data class CellSummary(
    val registered: CellTech,
    /** `MCC-MNC`, e.g. `262-01`; never a cell id. */
    val operator: String,
    val neighbours: Int,
) {
    /** The events-table summary: `type=LTE;op=262-01;n=5`. */
    fun encode(): String = "type=${registered.slug};op=$operator;n=$neighbours"

    companion object {
        const val UNKNOWN_OPERATOR = "?"

        fun parse(summary: String): CellSummary? {
            val fields = summary.split(';').mapNotNull { f ->
                val i = f.indexOf('=')
                if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
            }.toMap()
            val type = fields["type"] ?: return null
            return CellSummary(CellTech.bySlug(type), fields["op"] ?: UNKNOWN_OPERATOR, fields["n"]?.toIntOrNull() ?: 0)
        }
    }
}

object CellHeuristics {
    /** True when the phone fell from 4G/5G to 2G/3G, which is what a downgrade attack forces. */
    fun isDowngrade(before: CellTech, after: CellTech): Boolean =
        before.rank >= CellTech.LTE.rank && after != CellTech.UNKNOWN && after.rank < before.rank && after.rank <= CellTech.UMTS.rank

    /** Registered on 2G: no mutual authentication, weak or no encryption. */
    fun isLegacy(tech: CellTech): Boolean = tech == CellTech.GSM

    /** `26201` → `262-01`; anything that is not an MCC+MNC string becomes `?`. */
    fun operatorOf(networkOperator: String?): String {
        val s = networkOperator?.trim().orEmpty()
        if (s.length !in 5..6 || !s.all { it.isDigit() }) return CellSummary.UNKNOWN_OPERATOR
        return s.substring(0, 3) + "-" + s.substring(3)
    }
}
