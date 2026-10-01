package io.github.stronghorse44.tunnels.explore

import io.github.stronghorse44.tunnels.model.Observation

/** One satellite from one GnssStatus, copied into plain Kotlin. No position anywhere. */
data class SatelliteSample(
    /** `GnssStatus.CONSTELLATION_*` code. */
    val constellation: Int,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
)

/** Per-constellation summary of the best status seen during the listening window. */
data class ConstellationFacts(
    val name: String,
    val visible: Int,
    val usedInFix: Int,
    val cn0Avg: Float,
    val cn0Max: Float,
)

/** Everything the satellites scan keeps. */
data class GnssFacts(
    /** [Satellites.AVAILABLE_YES] or why nothing could be heard. */
    val available: String,
    val listenSeconds: Int,
    val statusUpdates: Int,
    val constellations: List<ConstellationFacts>,
    val hasMeasurements: Boolean? = null,
    val hasNavigationMessages: Boolean? = null,
    val yearOfHardware: Int? = null,
    val hardwareModel: String? = null,
)

/** Satellites tunnel: observation schema and pure builders. Explore line: no rules, no actions. */
object Satellites {
    const val TUNNEL_ID = "satellites"
    const val SUMMARY = "gnss"

    const val AVAILABLE = "gnss:available"
    const val LISTEN_SECONDS = "gnss:listenSeconds"
    const val STATUS_UPDATES = "gnss:statusUpdates"
    const val CAPABILITIES = "gnss:capabilities"
    const val YEAR_OF_HARDWARE = "gnss:yearOfHardware"
    const val HARDWARE_MODEL = "gnss:hardwareModel"
    const val SATS_VISIBLE = "sats:visible"
    const val SATS_USED = "sats:usedInFix"
    const val CN0_AVG = "cn0:avg"
    const val CN0_MAX = "cn0:max"

    const val AVAILABLE_YES = "yes"
    const val AVAILABLE_NO_PERMISSION = "no permission"
    const val AVAILABLE_LOCATION_OFF = "location off"
    const val AVAILABLE_NO_PROVIDER = "no GNSS provider"
    const val AVAILABLE_NO_STATUS = "no satellite status received"
    const val AVAILABLE_FAILED = "failed"

    /** Keys the summary subject may carry. */
    val summaryKeys = listOf(AVAILABLE, LISTEN_SECONDS, STATUS_UPDATES, CAPABILITIES, YEAR_OF_HARDWARE, HARDWARE_MODEL, SATS_VISIBLE, SATS_USED)
    /** Keys every constellation subject carries. */
    val perConstellation = listOf(SATS_VISIBLE, SATS_USED, CN0_AVG, CN0_MAX)

    /** The constellation names in the order the cards use; codes follow `GnssStatus.CONSTELLATION_*`. */
    val constellationNames: List<String> = listOf("GPS", "GLONASS", "Galileo", "BeiDou", "QZSS", "NavIC", "SBAS")

    fun constellationName(code: Int): String = when (code) {
        1 -> "GPS"
        2 -> "SBAS"
        3 -> "GLONASS"
        4 -> "QZSS"
        5 -> "BeiDou"
        6 -> "Galileo"
        7 -> "NavIC"
        else -> "Unknown"
    }

    /** Groups the satellites of one status by constellation. Constellations with no satellite are left out. */
    fun summarise(samples: List<SatelliteSample>): List<ConstellationFacts> =
        samples.groupBy { constellationName(it.constellation) }.map { (name, sats) ->
            val cn0 = sats.map { it.cn0DbHz }.filter { !it.isNaN() && it > 0f }
            ConstellationFacts(
                name = name,
                visible = sats.size,
                usedInFix = sats.count { it.usedInFix },
                cn0Avg = if (cn0.isEmpty()) 0f else (cn0.sum() / cn0.size),
                cn0Max = cn0.maxOrNull() ?: 0f,
            )
        }.sortedWith(compareBy({ constellationNames.indexOf(it.name).let { i -> if (i < 0) constellationNames.size else i } }, { it.name }))

    fun observations(facts: GnssFacts): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))
        add(SUMMARY, AVAILABLE, facts.available)
        add(SUMMARY, LISTEN_SECONDS, facts.listenSeconds.toString())
        add(SUMMARY, STATUS_UPDATES, facts.statusUpdates.toString())
        add(SUMMARY, SATS_VISIBLE, facts.constellations.sumOf { it.visible }.toString())
        add(SUMMARY, SATS_USED, facts.constellations.sumOf { it.usedInFix }.toString())
        if (facts.hasMeasurements != null || facts.hasNavigationMessages != null) {
            add(
                SUMMARY, CAPABILITIES,
                listOfNotNull(
                    facts.hasMeasurements?.let { "measurements=$it" },
                    facts.hasNavigationMessages?.let { "navigationMessages=$it" },
                ).joinToString(","),
            )
        }
        facts.yearOfHardware?.takeIf { it > 0 }?.let { add(SUMMARY, YEAR_OF_HARDWARE, it.toString()) }
        facts.hardwareModel?.takeIf { it.isNotBlank() }?.let { add(SUMMARY, HARDWARE_MODEL, it) }
        for (c in facts.constellations) {
            add(c.name, SATS_VISIBLE, c.visible.toString())
            add(c.name, SATS_USED, c.usedInFix.toString())
            add(c.name, CN0_AVG, ExploreFormat.num(Math.round(c.cn0Avg * 10) / 10.0))
            add(c.name, CN0_MAX, ExploreFormat.num(Math.round(c.cn0Max * 10) / 10.0))
        }
        return out
    }
}
