package io.github.stronghorse44.tunnels.elf

import io.github.stronghorse44.tunnels.model.Observation

/** One app's hardening totals, for ranking. */
data class AppHardening(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val libs: Int,
    val parsed: Int,
    val weak: Int,
    val partialRelro: Int,
    val noCanary: Int,
    val legacy32: Boolean,
) {
    /** Higher is worse: weak libraries dominate, then 32-bit-only code, then lazy binding and canaries. */
    val score: Int get() = weak * 100 + (if (legacy32) 50 else 0) + partialRelro * 3 + noCanary
}

/** Aggregates for the tunnel's summary panel, computed from one snapshot's observations. */
data class HardeningStats(
    val apps: Int,
    val appsWithNative: Int,
    val libsSeen: Int,
    val libsParsed: Int,
    val weakApps: Int,
    val weakLibs: Int,
    val legacy32Apps: Int,
    /** Apps with any shortcoming, worst first. */
    val weakest: List<AppHardening>,
) {
    companion object {
        val EMPTY = HardeningStats(0, 0, 0, 0, 0, 0, 0, emptyList())

        fun from(observations: List<Observation>, top: Int = 3): HardeningStats {
            val bySubject = observations.groupBy { it.subject }
            if (bySubject.isEmpty()) return EMPTY
            val apps = bySubject.map { (pkg, obs) ->
                AppHardening(
                    packageName = pkg,
                    label = HardeningKeys.value(obs, HardeningKeys.LABEL) ?: pkg,
                    system = HardeningKeys.isSystem(obs),
                    libs = HardeningKeys.count(obs, HardeningKeys.LIBS),
                    parsed = HardeningKeys.libs(obs).size,
                    weak = HardeningKeys.count(obs, HardeningKeys.WEAK),
                    partialRelro = HardeningKeys.count(obs, HardeningKeys.PARTIAL_RELRO),
                    noCanary = HardeningKeys.count(obs, HardeningKeys.NO_CANARY),
                    legacy32 = HardeningKeys.value(obs, HardeningKeys.BITS_64) == "false",
                )
            }
            return HardeningStats(
                apps = apps.size,
                appsWithNative = apps.count { it.libs > 0 },
                libsSeen = apps.sumOf { it.libs },
                libsParsed = apps.sumOf { it.parsed },
                weakApps = apps.count { it.weak > 0 },
                weakLibs = apps.sumOf { it.weak },
                legacy32Apps = apps.count { it.legacy32 },
                weakest = apps.filter { it.score > 0 }
                    .sortedWith(compareByDescending<AppHardening> { it.score }.thenBy { it.label.lowercase() })
                    .take(top),
            )
        }
    }
}
