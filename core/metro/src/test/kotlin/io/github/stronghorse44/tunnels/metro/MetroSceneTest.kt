package io.github.stronghorse44.tunnels.metro

import io.github.stronghorse44.tunnels.model.TunnelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real map with label sizes estimated the way Compose measures them (Roboto 13 sp titles, 10 sp monospace
 * statuses, both with fixed line heights), across phone widths, font scales and how much each label says.
 * The phone measures the real text; these estimates err wide so the layout has headroom.
 */
class MetroSceneTest {
    private val ready: (String) -> String = { "ready" }

    /** The statuses in the screenshot that showed the overlaps. */
    private val screenshot: (String) -> String = { id ->
        when (id) {
            "hardening" -> "20 findings · 2 NOTICE"
            "doors" -> "31 findings · 8 WARN"
            "apk_excavation" -> "15 findings · 8 WARN"
            "permissions" -> "21 findings · 21 NOTICE"
            "system_packages" -> "13 findings · 13 INFO"
            else -> "ready"
        }
    }

    /** Every station says as much as it ever can. */
    private val worst: (String) -> String = { id ->
        when (id) {
            TunnelCatalog.INSTALLER -> "last: Signal 7.31.2 (151700)"
            TunnelCatalog.UNZIP -> "last: photos-2026-backup.tar.gz"
            "sensors", "cameras", "satellites", "radio" -> "ready"
            else -> "128 findings · 12 CRITICAL"
        }
    }

    @Test
    fun screenshotStatusesFitWithoutCompromiseOnTheUsersPhone() {
        // A Pixel 10 at the default display size: 411 dp wide, minus the home screen's 36 dp of padding.
        val solution = solve(widthDp = 375f, fontScale = 1f, status = screenshot)
        assertTrue(solution.allClear)
        assertTrue("every label at full detail: ${variants(solution)}", solution.labels.all { it.variant == 0 })
        assertTrue("on its own side", solution.labels.zip(MetroLayout.stations).all { (p, s) -> p.side == s.second.side })
    }

    @Test
    fun nothingOverlapsAcrossWidthsFontScalesAndStatuses() {
        for (widthDp in listOf(324f, 375f, 444f)) for (fontScale in listOf(1f, 1.15f, 1.3f)) for ((name, status) in listOf("ready" to ready, "screenshot" to screenshot, "worst" to worst)) {
            val what = "$name at $widthDp dp, font ×$fontScale"
            val solution = solve(widthDp, fontScale, status)
            assertTrue("$what: ${variants(solution)}", solution.allClear)
            assertNoOverlaps(what, solution)
        }
    }

    @Test
    fun labelsKeepTheirStatusesUnlessTheScreenIsTiny() {
        for (widthDp in listOf(375f, 444f)) for (fontScale in listOf(1f, 1.15f)) for ((name, status) in listOf("ready" to ready, "screenshot" to screenshot, "worst" to worst)) {
            val solution = solve(widthDp, fontScale, status)
            assertTrue("$name at $widthDp dp ×$fontScale: ${variants(solution)}", solution.labels.all { it.variant < LAST_VARIANT })
        }
    }

    @Test
    fun theMapGrowsOnlyWhenLargeTextNeedsIt() {
        val normal = solve(375f, 1f, ready)
        assertEquals("the grid's own height", MetroScene(375f * DP, DP).heightPx, normal.heightPx, 0.01f)
        val large = solve(324f, 1.3f, ready)
        assertTrue("taller than at the default text size", large.heightPx > solve(324f, 1f, ready).heightPx)
        assertTrue("every label inside the map", large.labels.all { it.box.bottom <= large.heightPx })
    }

    private fun variants(s: MetroScene.Solution): String =
        s.labels.zip(MetroLayout.stations).joinToString { (p, st) -> "${st.second.tunnelId}=v${p.variant}${if (p.clear) "" else "!"}" } +
            s.badges.zip(MetroLayout.lines).filter { !it.first.clear }.joinToString("") { (_, l) -> ", ${l.line} badge!" }

    private fun assertNoOverlaps(what: String, s: MetroScene.Solution) {
        val boxes = s.labels.map { it.box } + s.badges.map { it.box }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            assertTrue("$what: boxes $i and $j overlap", !boxes[i].intersects(boxes[j]))
        }
    }

    private fun solve(widthDp: Float, fontScale: Float, status: (String) -> String): MetroScene.Solution {
        val scene = MetroScene(widthDp * DP, DP, fontScale)
        val labelSizes = MetroLayout.stations.map { (_, s) ->
            val title = TunnelCatalog.byId(s.tunnelId)!!.title
            LabelSizes.of(title, status(s.tunnelId), fontScale).map { Dim(it.width * DP, it.height * DP) }
        }
        val badgeSizes = MetroLayout.lines.map { l -> LabelSizes.badge(l.line.label, fontScale).let { Dim(it.width * DP, it.height * DP) } }
        return scene.solve(labelSizes, badgeSizes)
    }

    /** Size estimates in dp for the four renderings of MetroMap's station label and for a line badge. */
    private object LabelSizes {
        /**
         * Titles in Roboto SemiBold 13 sp, measured with Compose's text layout at font scale 1. The phone's Roboto
         * can differ a little, so [TITLE_MARGIN] adds 10 %.
         */
        val TITLES = mapOf(
            "Installer" to 48f, "Unzip" to 34f, "Permissions" to 73f, "APK excavation" to 92f, "Doors" to 35f,
            "Hardening audit" to 93f, "System packages" to 103f, "Trust store" to 64f, "Silicon" to 40f, "Deep mode" to 66f,
            "Notifications" to 75f, "Timeline" to 51f, "Traffic" to 38f, "Surroundings" to 79f, "Home network" to 86f,
            "Sensors" to 48f, "Cameras" to 52f, "Satellites" to 55f, "Radio" to 34f,
        )
        const val TITLE_MARGIN = 1.1f
        /** Monospace at 10 sp is 0.6 em; measured 6.1. */
        const val STATUS_CHAR = 6.1f
        const val TITLE_LINE = MetroScene.TITLE_LINE_SP
        const val STATUS_LINE = MetroScene.STATUS_LINE_SP
        const val PAD_X = 8f
        const val PAD_Y = MetroScene.LABEL_PADDING_DP

        fun of(title: String, status: String, scale: Float): List<Dim> {
            val titleW = (TITLES[title] ?: error("measure \"$title\" and add it")) * TITLE_MARGIN * scale
            val parts = status.split(" · ")
            fun width(text: String, cap: Float) = minOf(text.length * STATUS_CHAR * scale, cap)
            fun dim(lines: List<String>, cap: Float) = Dim(
                maxOf(titleW, lines.maxOf { width(it, cap) }) + PAD_X,
                (TITLE_LINE + STATUS_LINE * lines.size) * scale + PAD_Y,
            )
            val oneLine = dim(listOf(status), 200f)
            val wrapped = if (parts.size > 1) dim(parts, 124f) else {
                dim(if (status.length * STATUS_CHAR * scale > 124f) listOf(status, status) else listOf(status), 124f)
            }
            val short = dim(listOf(parts.last()), 108f)
            val titleOnly = Dim(titleW + PAD_X, TITLE_LINE * scale + PAD_Y)
            return listOf(oneLine, wrapped, short, titleOnly)
        }

        /** The code in its bordered box, 4 dp, then the name in 10 sp monospace with 1.5 sp letter spacing. */
        fun badge(label: String, scale: Float) = Dim(
            (7f * scale + 8f + 2.4f) + 4f + label.length * (STATUS_CHAR + 1.5f) * scale,
            14f * scale + 2.4f,
        )
    }

    companion object {
        /** A Pixel 10's density, so pixel rounding is realistic. */
        const val DP = 2.625f
        const val LAST_VARIANT = 3
    }
}
