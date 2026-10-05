package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.model.Observation
import java.time.LocalDate

/** The summaries the Backups tunnel stores: per app the newest date, the file count and a status. No names, no paths. */
object BackupKeys {
    const val TUNNEL_ID = "backups"

    // Subjects besides the app IDs.
    const val FOLDER = "folder"
    const val OTHER = "other"
    const val DRILL = "drill"

    // Folder
    const val STATE = "state"
    const val BUNDLES = "bundles"
    const val UNREADABLE = "unreadable"
    const val SKIPPED = "skipped"
    const val TRUNCATED = "truncated"
    const val FAULTS = "faults"

    // App (and Other)
    const val STATUS = "status"
    const val NEWEST_MS = "newest_ms"
    const val FILES = "files"
    const val SCHEMA = "schema"
    const val SOURCE = "source"
    const val TRACKED = "tracked"
    const val SUSPICIOUS = "suspicious"
    const val ITEMS = "items"
    const val UNDATED = "undated"

    /** The newest file is named for another registry app than the one it holds. */
    const val MISNAMED = "misnamed"

    /** Status `unreadable`: the failed file's own name says it is this app's (else it only may be). */
    const val FAILED_NAMED = "failed_named"
    const val THRESHOLD_DAYS = "threshold_days"

    /** Moves with the clock alone (also on the drill subject). */
    const val AGE_DAYS = "age_days"

    // Drill
    const val LAST = "last"

    const val SOURCE_HEADER = "header"
    const val SOURCE_FILE_DATE = "file-date"

    val SUBJECTS_APART = setOf(FOLDER, OTHER, DRILL)
    val VOLATILE = setOf(AGE_DAYS)
}

object BackupObservations {
    /**
     * [scan] is what the folder held (or [FolderScan.NONE] / [FolderScan.LOST]); [nowMs] and [today] fix the clock.
     * When the folder cannot be read no app is described at all: an unreadable folder is not a missing backup.
     */
    fun build(scan: FolderScan, settings: BackupSettings, nowMs: Long, today: LocalDate): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: Any) = out.add(Observation(BackupKeys.TUNNEL_ID, subject, key, value.toString()))

        add(BackupKeys.FOLDER, BackupKeys.STATE, scan.state.wire)
        if (scan.state == FolderState.OK) {
            add(BackupKeys.FOLDER, BackupKeys.BUNDLES, scan.bundleFiles)
            add(BackupKeys.FOLDER, BackupKeys.UNREADABLE, scan.unreadable)
            add(BackupKeys.FOLDER, BackupKeys.SKIPPED, scan.skipped)
            add(BackupKeys.FOLDER, BackupKeys.TRUNCATED, scan.truncated)
            add(BackupKeys.FOLDER, BackupKeys.FAULTS, scan.faults)
            for (app in BackupApps.all) {
                val found = scan.apps[app.id]
                val tracked = settings.isTracked(app.id, foundNow = found != null)
                if (!tracked && found == null) continue
                val status = Freshness.status(tracked, found, nowMs, settings.thresholdDays, hold = scan.holdOf(app.id))
                add(app.id, BackupKeys.STATUS, status.wire)
                add(app.id, BackupKeys.TRACKED, tracked)
                add(app.id, BackupKeys.FILES, found?.files ?: 0)
                add(app.id, BackupKeys.THRESHOLD_DAYS, settings.thresholdDays)
                if (found != null && found.suspicious > 0) add(app.id, BackupKeys.SUSPICIOUS, found.suspicious)
                if (found != null && found.items > 0) add(app.id, BackupKeys.ITEMS, found.items)
                if (found != null && found.undated > 0) add(app.id, BackupKeys.UNDATED, found.undated)
                if (found != null && found.newestMisnamed) add(app.id, BackupKeys.MISNAMED, true)
                if (status == AppStatus.UNREADABLE && app.id in scan.failedNamed) add(app.id, BackupKeys.FAILED_NAMED, true)
                if (found != null && found.newestMs > 0) {
                    add(app.id, BackupKeys.NEWEST_MS, found.newestMs)
                    add(app.id, BackupKeys.AGE_DAYS, Freshness.ageDays(found.newestMs, nowMs))
                    add(app.id, BackupKeys.SOURCE, if (found.fromFileDate) BackupKeys.SOURCE_FILE_DATE else BackupKeys.SOURCE_HEADER)
                    if (found.schema > 0) add(app.id, BackupKeys.SCHEMA, found.schema)
                }
            }
            if (scan.other.files > 0) {
                add(BackupKeys.OTHER, BackupKeys.FILES, scan.other.files)
                if (scan.other.newestMs > 0) add(BackupKeys.OTHER, BackupKeys.NEWEST_MS, scan.other.newestMs)
            }
        }

        val drillStatus = Freshness.drillStatus(settings.drill, today)
        add(BackupKeys.DRILL, BackupKeys.STATUS, drillStatus.wire)
        settings.drill?.let {
            add(BackupKeys.DRILL, BackupKeys.LAST, it.toString())
            add(BackupKeys.DRILL, BackupKeys.AGE_DAYS, Freshness.drillAgeDays(it, today))
        }
        return out
    }
}
