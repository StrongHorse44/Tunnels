package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Instant
import java.time.ZoneId

/**
 * Findings are state findings: they clear when the state does (a fresh export is put in the folder, the app is no
 * longer tracked, a drill is recorded). The wording says what the header says, never that a backup is verified: the
 * header MAC needs the passphrase, so only a restore drill proves a bundle (export container spec section 6).
 */
object BackupRules {
    const val STALE = "BACKUP_STALE"
    const val MISSING = "BACKUP_MISSING"
    const val DATE_SUSPICIOUS = "BACKUP_DATE_SUSPICIOUS"
    const val DRILL_DUE = "RESTORE_DRILL_DUE"

    /** The picked folder cannot be read now: deleted, moved, or access removed. Without this the stale findings would clear unseen. */
    const val FOLDER_LOST = "BACKUP_FOLDER_LOST"

    /**
     * The scan left files unread or a file could not be read. A warning when that keeps a watched app from being judged
     * (a stale or missing backup could be hiding), a notice when every watched app was still judged.
     */
    const val SCAN_INCOMPLETE = "BACKUP_SCAN_INCOMPLETE"

    fun all(zone: ZoneId = ZoneId.systemDefault()): List<FindingRule> = listOf(
        Rules.perSubject(STALE, Severity.WARN) { subject, obs -> staleEvidence(subject, obs, zone) },
        Rules.perSubject(MISSING, Severity.WARN) { subject, obs -> missingEvidence(subject, obs) },
        Rules.perSubject(DATE_SUSPICIOUS, Severity.NOTICE) { subject, obs -> suspiciousEvidence(subject, obs) },
        Rules.perSubject(DRILL_DUE, Severity.NOTICE) { subject, obs -> drillEvidence(subject, obs) },
        Rules.perSubject(FOLDER_LOST, Severity.WARN) { subject, obs -> lostEvidence(subject, obs) },
        FindingRule { incomplete(it) },
    )

    fun dateOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    private fun value(obs: List<Observation>, key: String) = obs.firstOrNull { it.key == key }?.value

    private fun appStatus(subject: String, obs: List<Observation>) =
        if (subject in BackupKeys.SUBJECTS_APART) null else AppStatus.of(value(obs, BackupKeys.STATUS))

    private fun staleEvidence(subject: String, obs: List<Observation>, zone: ZoneId): String? {
        if (appStatus(subject, obs) != AppStatus.STALE) return null
        val name = BackupApps.nameOf(subject)
        val newest = value(obs, BackupKeys.NEWEST_MS)?.toLongOrNull() ?: return null
        val age = value(obs, BackupKeys.AGE_DAYS) ?: "?"
        val limit = value(obs, BackupKeys.THRESHOLD_DAYS) ?: "?"
        val what = if (value(obs, BackupKeys.SOURCE) == BackupKeys.SOURCE_FILE_DATE) {
            "The newest $name export (old format, so the file's own date) is from ${dateOf(newest, zone)}"
        } else {
            "The newest $name bundle header says ${dateOf(newest, zone)}"
        }
        return "$what, $age days ago; you asked to hear after $limit. " +
            "A header date is not checked without the passphrase; only a restore proves a backup works."
    }

    private fun missingEvidence(subject: String, obs: List<Observation>): String? {
        val name = BackupApps.nameOf(subject)
        return when (appStatus(subject, obs)) {
            AppStatus.MISSING ->
                "No $name bundle in the export folder, and $name is on your list of apps to watch " +
                    "(a bundle of it was found there before, or you added it). Export it again, or move the file back."
            AppStatus.NO_MANIFEST -> {
                val items = value(obs, BackupKeys.ITEMS) ?: "?"
                "$name item files ($items) but no manifest: the export is incomplete and can't be imported. " +
                    "$name writes the manifest last, so an export is only complete once it exists. Export again, or move the manifest file back."
            }
            else -> null
        }
    }

    private fun suspiciousEvidence(subject: String, obs: List<Observation>): String? {
        if (appStatus(subject, obs) != AppStatus.SUSPICIOUS) return null
        val name = BackupApps.nameOf(subject)
        return "Every $name bundle in the folder has a header dated more than a day in the future, " +
            "so none counts as fresh. Check the phone's clock, or whether the file is what you exported."
    }

    private fun drillEvidence(subject: String, obs: List<Observation>): String? {
        if (subject != BackupKeys.DRILL || DrillStatus.of(value(obs, BackupKeys.STATUS)) != DrillStatus.DUE) return null
        val last = value(obs, BackupKeys.LAST) ?: return null
        val age = value(obs, BackupKeys.AGE_DAYS) ?: "?"
        return "Your last restore drill was on $last, $age days ago (a reminder comes ${BackupSettings.DRILL_EVERY_DAYS} days after the last). " +
            "A backup is only proven when you restore it: import one export into its app and check the data."
    }

    private fun lostEvidence(subject: String, obs: List<Observation>): String? {
        if (subject != BackupKeys.FOLDER || FolderState.of(value(obs, BackupKeys.STATE)) != FolderState.LOST) return null
        return "Tunnels cannot read the export folder now: it was moved or deleted, or its access was removed. " +
            "No app's backup can be checked until you choose the folder again, so nothing is reported stale or missing meanwhile."
    }

    /** Watched apps the scan could not judge, by name, with the reason each was held. */
    private fun hiddenApps(by: Map<String, List<Observation>>): List<Pair<String, AppStatus>> = BackupApps.all.mapNotNull { app ->
        val obs = by[app.id] ?: return@mapNotNull null
        val status = AppStatus.of(value(obs, BackupKeys.STATUS))
        if (value(obs, BackupKeys.TRACKED) == "true" && (status == AppStatus.INCOMPLETE || status == AppStatus.UNREADABLE)) app.name to status!! else null
    }

    private fun incomplete(ctx: RuleContext): List<FindingDraft> {
        val by = ctx.bySubject()
        val folder = by[BackupKeys.FOLDER] ?: return emptyList()
        val truncated = value(folder, BackupKeys.TRUNCATED) == "true"
        val faults = value(folder, BackupKeys.FAULTS)?.toIntOrNull() ?: 0
        if (FolderState.of(value(folder, BackupKeys.STATE)) != FolderState.OK || (!truncated && faults == 0)) return emptyList()
        val hidden = hiddenApps(by)
        val why = buildList {
            if (truncated) {
                add(
                    "The export folder holds more files than one scan reads (${FolderScanner.MAX_HEADERS} headers at most, " +
                        "${FolderScanner.MAX_PER_APP} per app name, newest names first).",
                )
            }
            if (faults > 0) add("$faults file${if (faults == 1) "" else "s"} or folder${if (faults == 1) "" else "s"} could not be opened (the storage provider failed on ${if (faults == 1) "it" else "them"}).")
        }.joinToString(" ")
        val effect = if (hidden.isEmpty()) {
            "Every app you watch was still judged from the newest file that was read."
        } else {
            hidden.joinToString("; ") { (name, status) ->
                if (status == AppStatus.UNREADABLE) "A $name file could not be read, so $name is not judged" else "$name is not judged: files that may be its own were not read"
            } + ". A stale or missing backup could be hiding there. " +
                "Move old exports into another folder, pick a folder on the phone itself, or fix the file, then scan again."
        }
        val severity = if (hidden.isEmpty()) Severity.NOTICE else Severity.WARN
        return listOf(FindingDraft(ctx.tunnelId, BackupKeys.FOLDER, SCAN_INCOMPLETE, severity, "$why $effect"))
    }
}
