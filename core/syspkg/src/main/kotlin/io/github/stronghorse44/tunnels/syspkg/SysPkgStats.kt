package io.github.stronghorse44.tunnels.syspkg

import io.github.stronghorse44.tunnels.model.Observation

/** One system package as the summary panel lists it. */
data class PackageLine(val packageName: String, val label: String, val detail: String)

/** Totals over one scan's observations, for the tunnel's summary panel. Pure, so it is tested here. */
data class SysPkgStats(
    val total: Int,
    val privileged: Int,
    val withLauncher: Int,
    val updated: Int,
    /** Namespace label to package count, most common first. */
    val byNamespace: List<Pair<String, Int>>,
    /** Category label to package count, most common first; unknown packages count under [SysPkgKeys.UNKNOWN_CATEGORY]. */
    val byCategory: List<Pair<String, Int>>,
    /** Packages whose enabled state is anything but enabled, sorted by label. */
    val disabled: List<PackageLine>,
    /** Packages not in the knowledge base, sorted by package name. */
    val unknown: List<PackageLine>,
    /** Packages left out of the scan because of the per-scan cap. */
    val skipped: Int,
) {
    companion object {
        val EMPTY = SysPkgStats(0, 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), 0)

        fun from(observations: List<Observation>): SysPkgStats {
            val packages = observations.filter { it.subject != SysPkgKeys.SUMMARY }.groupBy { it.subject }
            if (packages.isEmpty()) return EMPTY
            val summary = observations.filter { it.subject == SysPkgKeys.SUMMARY }
            val namespaces = HashMap<String, Int>()
            val categories = HashMap<String, Int>()
            val disabled = ArrayList<PackageLine>()
            val unknown = ArrayList<PackageLine>()
            var privileged = 0
            var launcher = 0
            var updated = 0
            for ((pkg, obs) in packages) {
                fun v(key: String) = SysPkgKeys.value(obs, key)
                val label = v(SysPkgKeys.LABEL) ?: pkg
                namespaces.merge(v(SysPkgKeys.NAMESPACE) ?: Namespace.OTHER.label, 1, Int::plus)
                categories.merge(v(SysPkgKeys.CATEGORY) ?: SysPkgKeys.UNKNOWN_CATEGORY, 1, Int::plus)
                if (v(SysPkgKeys.PRIVILEGED) == "true") privileged++
                if (v(SysPkgKeys.HAS_LAUNCHER) == "true") launcher++
                if (v(SysPkgKeys.UPDATED) == "true") updated++
                val enabled = v(SysPkgKeys.ENABLED)
                if (SysPkgKeys.isDisabled(enabled)) disabled += PackageLine(pkg, label, SysPkgKeys.describeEnabled(enabled!!))
                if (!SysPkgKeys.isKnown(obs)) unknown += PackageLine(pkg, label, v(SysPkgKeys.NAMESPACE) ?: Namespace.OTHER.label)
            }
            return SysPkgStats(
                total = packages.size,
                privileged = privileged,
                withLauncher = launcher,
                updated = updated,
                byNamespace = namespaces.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
                byCategory = categories.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
                disabled = disabled.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName })),
                unknown = unknown.sortedBy { it.packageName },
                skipped = SysPkgKeys.value(summary, SysPkgKeys.SKIPPED)?.toIntOrNull() ?: 0,
            )
        }
    }
}
