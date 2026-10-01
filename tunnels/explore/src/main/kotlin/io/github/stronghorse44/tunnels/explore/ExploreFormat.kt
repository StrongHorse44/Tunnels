package io.github.stronghorse44.tunnels.explore

import io.github.stronghorse44.tunnels.model.Observation
import java.math.BigDecimal
import java.math.MathContext

/** Small formatting helpers shared by the explore tunnels' scanners and their cards. Plain Kotlin. */
object ExploreFormat {
    /** The line every explore screen opens with: nothing here is a security finding. */
    const val NOTE = "Explore: curiosity only, nothing here is a security finding."

    /** A float as a short decimal: 0.15, 39.2, 65536; "?" for NaN and infinities. At most six significant digits. */
    fun num(value: Float): String = num(value.toDouble())

    fun num(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "?"
        if (value == 0.0) return "0"
        return BigDecimal(value.toString()).round(MathContext(6)).stripTrailingZeros().toPlainString()
    }

    /** A list of floats as "a, b, c" with [num] formatting; empty list → "none". */
    fun nums(values: Collection<Float>): String = if (values.isEmpty()) "none" else values.joinToString(", ") { num(it) }

    /** Pixel count as megapixels with one decimal: 12192768 → "12.2 MP". */
    fun megapixels(width: Int, height: Int): String {
        val mp = width.toLong() * height.toLong() / 1_000_000.0
        return if (mp >= 10) "${mp.toLong()} MP" else "${(Math.round(mp * 10) / 10.0)} MP"
    }

    /** Observations grouped by subject as key → value maps, for the cards. */
    fun bySubject(observations: List<Observation>): Map<String, Map<String, String>> =
        observations.groupBy { it.subject }.mapValues { (_, obs) -> obs.associate { it.key to it.value } }
}
