package io.github.stronghorse44.tunnels.backups

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How one tracked app's newest bundle compares with the threshold. */
enum class AppStatus(val wire: String) {
    FRESH("fresh"),
    STALE("stale"),

    /** Tracked, and no bundle in the folder. */
    MISSING("missing"),

    /** Tracked, and every dated bundle is dated in the future, so none counts as fresh. */
    SUSPICIOUS("suspicious"),

    /** Not tracked: shown with its date, never a finding. */
    UNTRACKED("untracked"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

enum class DrillStatus(val wire: String) {
    /** The user has not set a date. Nothing to remind about yet. */
    NONE("none"),
    OK("ok"),
    DUE("due"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

object Freshness {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole days from [newestMs] to [nowMs]; never negative. */
    fun ageDays(newestMs: Long, nowMs: Long): Long = if (nowMs <= newestMs) 0 else (nowMs - newestMs) / DAY_MS

    /** [found] is this scan's summary for the app, or null when the folder has none. */
    fun status(tracked: Boolean, found: AppSummary?, nowMs: Long, thresholdDays: Int): AppStatus {
        val newest = found?.newestMs ?: 0L
        return when {
            !tracked -> AppStatus.UNTRACKED
            newest > 0 -> if (nowMs - newest > thresholdDays * DAY_MS) AppStatus.STALE else AppStatus.FRESH
            found != null && found.suspicious > 0 -> AppStatus.SUSPICIOUS
            else -> AppStatus.MISSING
        }
    }

    fun drillAgeDays(last: LocalDate, today: LocalDate): Long = maxOf(0L, ChronoUnit.DAYS.between(last, today))

    fun drillStatus(last: LocalDate?, today: LocalDate): DrillStatus = when {
        last == null -> DrillStatus.NONE
        drillAgeDays(last, today) >= BackupSettings.DRILL_EVERY_DAYS -> DrillStatus.DUE
        else -> DrillStatus.OK
    }
}
