package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.Stratum

/**
 * How far down the user is, and the light that reaches there. Home sits near the surface in dusk violet;
 * every step down (a tunnel, then a finding) sinks toward black, and tunnels in deeper strata start darker.
 * Colours are ARGB ints so this stays plain Kotlin.
 */
object Depth {
    const val HOME = 0f
    const val TUNNEL = 1f
    const val DETAIL = 2f
    const val MAX = 3f

    /** Background gradient at one depth: top of the screen, middle, bottom. */
    data class Water(val top: Int, val mid: Int, val bottom: Int)

    private val stops = listOf(
        Water(0xFF8B74FF.toInt(), 0xFF5A3DE6.toInt(), 0xFF2C1B9A.toInt()),
        Water(0xFF4B2FD6.toInt(), 0xFF2A178F.toInt(), 0xFF140B52.toInt()),
        Water(0xFF26147A.toInt(), 0xFF150B4A.toInt(), 0xFF08051F.toInt()),
        Water(0xFF110838.toInt(), 0xFF08041C.toInt(), 0xFF020108.toInt()),
    )

    /** A tunnel's own depth: one step down, plus a quarter step per stratum below Surface. Explore is a side shaft at Surface depth. */
    fun ofTunnel(stratum: Stratum?): Float = TUNNEL + when (stratum) {
        Stratum.TOPSOIL -> 0.25f
        Stratum.BEDROCK -> 0.5f
        Stratum.CORE -> 0.75f
        else -> 0f
    }

    fun water(depth: Float): Water {
        val d = depth.coerceIn(HOME, MAX)
        val i = d.toInt().coerceAtMost(stops.size - 2)
        val t = d - i
        val a = stops[i]
        val b = stops[i + 1]
        return Water(lerp(a.top, b.top, t), lerp(a.mid, b.mid, t), lerp(a.bottom, b.bottom, t))
    }

    /** How much light from the surface still glows at the top of the screen, 1 at home to 0 at [MAX]. */
    fun surfaceLight(depth: Float): Float = 1f - depth.coerceIn(HOME, MAX) / MAX

    fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** Relative luminance (0..1), for tests and for picking legible text. */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }
}
