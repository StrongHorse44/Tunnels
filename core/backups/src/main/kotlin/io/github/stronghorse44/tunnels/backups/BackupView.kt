package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.model.Observation
import java.time.LocalDate

/** One registry app as the screen shows it, read back from the stored observations. */
data class AppRow(
    val app: BackupApp,
    /** Null when the app has no observations: not tracked and no bundle seen in the last scan. */
    val status: AppStatus?,
    val tracked: Boolean,
    val newestMs: Long?,
    val ageDays: Long?,
    val files: Int,
    val fromFileDate: Boolean,
    val suspicious: Int,
    val items: Int,
    val undated: Int,
    /** Unreadable because a file named for this app failed; false when only a file that may be its own did. */
    val failedNamed: Boolean = false,
)

data class DrillView(val status: DrillStatus, val last: LocalDate?, val ageDays: Long?)

/** The last scan, for the tunnel's panel. Built from observations only, so it is exactly what a snapshot holds. */
data class BackupView(
    val folder: FolderState?,
    val bundles: Int,
    val unreadable: Int,
    val skipped: Int,
    val truncated: Boolean,
    val faults: Int,
    val apps: List<AppRow>,
    val otherFiles: Int,
    val otherNewestMs: Long?,
    val drill: DrillView,
    val thresholdDays: Int?,
) {
    companion object {
        fun from(observations: List<Observation>): BackupView {
            val by = observations.filter { it.tunnelId == BackupKeys.TUNNEL_ID }.groupBy { it.subject }
            fun v(subject: String, key: String) = by[subject]?.firstOrNull { it.key == key }?.value
            val rows = BackupApps.all.map { app ->
                val status = AppStatus.of(v(app.id, BackupKeys.STATUS))
                AppRow(
                    app = app,
                    status = status,
                    tracked = v(app.id, BackupKeys.TRACKED) == "true",
                    newestMs = v(app.id, BackupKeys.NEWEST_MS)?.toLongOrNull(),
                    ageDays = v(app.id, BackupKeys.AGE_DAYS)?.toLongOrNull(),
                    files = v(app.id, BackupKeys.FILES)?.toIntOrNull() ?: 0,
                    fromFileDate = v(app.id, BackupKeys.SOURCE) == BackupKeys.SOURCE_FILE_DATE,
                    suspicious = v(app.id, BackupKeys.SUSPICIOUS)?.toIntOrNull() ?: 0,
                    items = v(app.id, BackupKeys.ITEMS)?.toIntOrNull() ?: 0,
                    undated = v(app.id, BackupKeys.UNDATED)?.toIntOrNull() ?: 0,
                    failedNamed = v(app.id, BackupKeys.FAILED_NAMED) == "true",
                )
            }
            return BackupView(
                folder = FolderState.of(v(BackupKeys.FOLDER, BackupKeys.STATE)),
                bundles = v(BackupKeys.FOLDER, BackupKeys.BUNDLES)?.toIntOrNull() ?: 0,
                unreadable = v(BackupKeys.FOLDER, BackupKeys.UNREADABLE)?.toIntOrNull() ?: 0,
                skipped = v(BackupKeys.FOLDER, BackupKeys.SKIPPED)?.toIntOrNull() ?: 0,
                truncated = v(BackupKeys.FOLDER, BackupKeys.TRUNCATED) == "true",
                faults = v(BackupKeys.FOLDER, BackupKeys.FAULTS)?.toIntOrNull() ?: 0,
                apps = rows,
                otherFiles = v(BackupKeys.OTHER, BackupKeys.FILES)?.toIntOrNull() ?: 0,
                otherNewestMs = v(BackupKeys.OTHER, BackupKeys.NEWEST_MS)?.toLongOrNull(),
                drill = DrillView(
                    status = DrillStatus.of(v(BackupKeys.DRILL, BackupKeys.STATUS)) ?: DrillStatus.NONE,
                    last = v(BackupKeys.DRILL, BackupKeys.LAST)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    ageDays = v(BackupKeys.DRILL, BackupKeys.AGE_DAYS)?.toLongOrNull(),
                ),
                thresholdDays = rows.firstNotNullOfOrNull { r -> by[r.app.id]?.firstOrNull { it.key == BackupKeys.THRESHOLD_DAYS }?.value?.toIntOrNull() },
            )
        }
    }
}
