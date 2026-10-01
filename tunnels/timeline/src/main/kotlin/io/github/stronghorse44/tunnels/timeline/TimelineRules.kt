package io.github.stronghorse44.tunnels.timeline

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity
import java.time.LocalDate

/**
 * Finding rules of the timeline tunnel. Pure functions over observations; the clock is injected so the
 * unused-app rule can be tested with a fixed day. All rules skip system apps: their traffic (updates,
 * sync) is not the user's to uninstall, and shared system uids would repeat one event per package.
 */
object TimelineRules {
    const val UNUSED_APP = "UNUSED_APP"
    const val HEAVY_BACKGROUND_DATA = "HEAVY_BACKGROUND_DATA"
    const val DATA_SPIKE = "DATA_SPIKE"
    const val USAGE_WITHOUT_LAUNCH = "USAGE_WITHOUT_LAUNCH"

    /** Background megabytes in 30 days above which an app is noticed, and above which it is a WARN. */
    const val HEAVY_BG_MB = 50L
    const val HEAVY_BG_WARN_MB = 500L
    /** Growth of the 30-day mobile figure between two scans that counts as a spike. */
    const val SPIKE_MB = 200L
    /** Megabytes in 30 days that are suspicious for an app the user never opened. */
    const val SILENT_TRAFFIC_MB = 20L

    /** Kinds whose findings also get the Data usage settings action. */
    val dataKinds: Set<String> = setOf(HEAVY_BACKGROUND_DATA, DATA_SPIKE, USAGE_WITHOUT_LAUNCH)

    /** State INFO: a user app not opened in 60+ days. */
    fun unusedApp(today: () -> LocalDate): FindingRule = Rules.perSubject(UNUSED_APP, Severity.INFO) { _, obs ->
        val day = today()
        if (!TimelineKeys.isUnused(obs, day)) return@perSubject null
        val last = TimelineKeys.value(obs, TimelineKeys.LAST_USED)
        val detail = if (last == null || last == TimelineKeys.NEVER) "never opened" else "last opened $last"
        "Not opened in ${TimelineKeys.UNUSED_DAYS}+ days; still holds its permissions ($detail)."
    }

    /** State NOTICE (WARN above [HEAVY_BG_WARN_MB]): a user app moved more than [HEAVY_BG_MB] MB in the background. */
    val heavyBackgroundData: FindingRule = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            if (!TimelineKeys.isApp(obs) || TimelineKeys.isSystem(obs)) return@mapNotNull null
            val bg = TimelineKeys.longValue(obs, TimelineKeys.BG_MB_30) ?: return@mapNotNull null
            if (bg <= HEAVY_BG_MB) return@mapNotNull null
            val fg = TimelineKeys.longValue(obs, TimelineKeys.FG_MB_30) ?: 0L
            val severity = if (bg > HEAVY_BG_WARN_MB) Severity.WARN else Severity.NOTICE
            FindingDraft(
                ctx.tunnelId, subject, HEAVY_BACKGROUND_DATA, severity,
                "Moved $bg MB in the background over the last 30 days (while open: $fg MB).",
            )
        }
    }

    /** Sticky NOTICE: the 30-day mobile figure of a user app grew by more than [SPIKE_MB] MB since the previous scan. */
    val dataSpike: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val systemSubjects = ctx.bySubject().filterValues { TimelineKeys.isSystem(it) }.keys
        ctx.diff.mapNotNull { e ->
            val c = e as? DiffEntry.Changed ?: return@mapNotNull null
            if (c.key.key != TimelineKeys.MOBILE_MB_30 || c.key.subject in systemSubjects) return@mapNotNull null
            val before = c.before.value.toLongOrNull() ?: return@mapNotNull null
            val after = c.after.value.toLongOrNull() ?: return@mapNotNull null
            val growth = after - before
            if (growth <= SPIKE_MB) return@mapNotNull null
            FindingDraft(
                ctx.tunnelId, c.key.subject, DATA_SPIKE, Severity.NOTICE,
                "Mobile data over the last 30 days jumped from $before MB to $after MB since the previous scan (+$growth MB).",
                sticky = true,
            )
        }
    }

    /** State NOTICE: a user app moved more than [SILENT_TRAFFIC_MB] MB in 30 days without ever being in the foreground. */
    val usageWithoutLaunch: FindingRule = Rules.perSubject(USAGE_WITHOUT_LAUNCH, Severity.NOTICE) { _, obs ->
        if (!TimelineKeys.isApp(obs) || TimelineKeys.isSystem(obs)) return@perSubject null
        val minutes = TimelineKeys.longValue(obs, TimelineKeys.FG_MINUTES_30) ?: return@perSubject null
        if (minutes != 0L) return@perSubject null
        val wifi = TimelineKeys.longValue(obs, TimelineKeys.WIFI_MB_30) ?: return@perSubject null
        val mobile = TimelineKeys.longValue(obs, TimelineKeys.MOBILE_MB_30) ?: return@perSubject null
        val total = wifi + mobile
        if (total <= SILENT_TRAFFIC_MB) return@perSubject null
        "Moved $total MB over the last 30 days (Wi-Fi $wifi MB, mobile $mobile MB) without being opened once."
    }

    /** Every rule of the tunnel, in display order. */
    fun all(today: () -> LocalDate = { LocalDate.now() }): List<FindingRule> =
        listOf(unusedApp(today), heavyBackgroundData, dataSpike, usageWithoutLaunch)
}
