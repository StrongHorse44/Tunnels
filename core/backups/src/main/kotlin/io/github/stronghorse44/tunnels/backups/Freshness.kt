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

    /** Files are there but none says when it was made (an old-format export with no file date, or only Lumen per-item files). */
    UNKNOWN_DATE("unknown-date"),

    /** The scan did not read every file, so this app cannot be judged stale or missing. */
    INCOMPLETE("incomplete"),
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

    /**
     * [found] is this scan's summary for the app, or null when the folder has none. When the scan was [incomplete] an
     * app that would be stale, missing or suspicious is not judged: a file that was not read may be the fresh one.
     * A file with no usable date ([AppSummary.undated]) may be the newest, so it keeps an old dated bundle from being stale.
     */
    fun status(tracked: Boolean, found: AppSummary?, nowMs: Long, thresholdDays: Int, incomplete: Boolean = false): AppStatus {
        val newest = found?.newestMs ?: 0L
        val undated = (found?.undated ?: 0) > 0
        val onlyItems = found != null && found.items > 0 && found.files == 0
        val raw = when {
            !tracked -> return AppStatus.UNTRACKED
            newest > 0 && nowMs - newest <= thresholdDays * DAY_MS -> return AppStatus.FRESH
            newest > 0 -> AppStatus.STALE
            undated || onlyItems -> return AppStatus.UNKNOWN_DATE
            found != null && found.suspicious > 0 -> AppStatus.SUSPICIOUS
            else -> AppStatus.MISSING
        }
        return when {
            raw == AppStatus.STALE && undated -> AppStatus.UNKNOWN_DATE
            incomplete -> AppStatus.INCOMPLETE
            else -> raw
        }
    }

    fun drillAgeDays(last: LocalDate, today: LocalDate): Long = maxOf(0L, ChronoUnit.DAYS.between(last, today))

    fun drillStatus(last: LocalDate?, today: LocalDate): DrillStatus = when {
        last == null -> DrillStatus.NONE
        drillAgeDays(last, today) >= BackupSettings.DRILL_EVERY_DAYS -> DrillStatus.DUE
        else -> DrillStatus.OK
    }
}
