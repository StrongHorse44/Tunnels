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

    /** Files are there but none says when it was made (an old-format export with no file date). */
    UNKNOWN_DATE("unknown-date"),

    /** Lumen item files with no manifest bundle: the export is incomplete and cannot be imported (Lumen's export spec). */
    NO_MANIFEST("no-manifest"),

    /** The scan left files unread that might be this app's, so it is not judged stale or missing. */
    INCOMPLETE("incomplete"),

    /** The storage provider failed on a file that might be this app's, so it is not judged stale or missing. */
    UNREADABLE("unreadable"),
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
     * [found] is this scan's summary for the app, or null when the folder has none. [hold] says the scan could not see
     * everything that might be this app's ([FolderScan.holdOf]): an app that would then be stale, missing or suspicious
     * is not judged, since a file that was not read may be the fresh one. A fresh app stays fresh: the fresh header was read.
     *
     * An old-format file with no usable date ([AppSummary.undated]) may be newer than an old-format file that has one, so
     * it keeps that one from being called stale. It cannot be newer than an FWX header's date: the old format is no longer
     * written, so an FWX bundle is always the later export. So the override applies only when the app has no FWX header
     * date at all ([AppSummary.headerMs]); with one, the newest dated file is judged as it is.
     */
    fun status(tracked: Boolean, found: AppSummary?, nowMs: Long, thresholdDays: Int, hold: Hold? = null): AppStatus {
        val newest = found?.newestMs ?: 0L
        val undated = (found?.undated ?: 0) > 0
        val onlyItems = found != null && found.items > 0 && found.files == 0
        val notJudged = if (hold == Hold.FAILED) AppStatus.UNREADABLE else AppStatus.INCOMPLETE
        return when {
            !tracked -> AppStatus.UNTRACKED
            newest > 0 && nowMs - newest <= thresholdDays * DAY_MS -> AppStatus.FRESH
            newest > 0 && undated && found != null && found.fromFileDate && found.headerMs == 0L -> AppStatus.UNKNOWN_DATE
            newest > 0 -> if (hold != null) notJudged else AppStatus.STALE
            undated -> AppStatus.UNKNOWN_DATE
            onlyItems -> if (hold != null) notJudged else AppStatus.NO_MANIFEST
            found != null && found.suspicious > 0 -> if (hold != null) notJudged else AppStatus.SUSPICIOUS
            else -> if (hold != null) notJudged else AppStatus.MISSING
        }
    }

    fun drillAgeDays(last: LocalDate, today: LocalDate): Long = maxOf(0L, ChronoUnit.DAYS.between(last, today))

    fun drillStatus(last: LocalDate?, today: LocalDate): DrillStatus = when {
        last == null -> DrillStatus.NONE
        drillAgeDays(last, today) >= BackupSettings.DRILL_EVERY_DAYS -> DrillStatus.DUE
        else -> DrillStatus.OK
    }
}
