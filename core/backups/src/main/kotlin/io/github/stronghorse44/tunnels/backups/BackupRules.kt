package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
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

    /** The folder holds more files than one scan reads, so nothing is judged stale or missing. */
    const val SCAN_INCOMPLETE = "BACKUP_SCAN_INCOMPLETE"

    fun all(zone: ZoneId = ZoneId.systemDefault()): List<FindingRule> = listOf(
        Rules.perSubject(STALE, Severity.WARN) { subject, obs -> staleEvidence(subject, obs, zone) },
        Rules.perSubject(MISSING, Severity.WARN) { subject, obs -> missingEvidence(subject, obs) },
        Rules.perSubject(DATE_SUSPICIOUS, Severity.NOTICE) { subject, obs -> suspiciousEvidence(subject, obs) },
        Rules.perSubject(DRILL_DUE, Severity.NOTICE) { subject, obs -> drillEvidence(subject, obs) },
        Rules.perSubject(FOLDER_LOST, Severity.WARN) { subject, obs -> lostEvidence(subject, obs) },
        Rules.perSubject(SCAN_INCOMPLETE, Severity.NOTICE) { subject, obs -> incompleteEvidence(subject, obs) },
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
        if (appStatus(subject, obs) != AppStatus.MISSING) return null
        val name = BackupApps.nameOf(subject)
        return "No $name bundle in the export folder, and $name is on your list of apps to watch " +
            "(a bundle of it was found there before, or you added it). Export it again, or move the file back."
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

    private fun incompleteEvidence(subject: String, obs: List<Observation>): String? {
        if (subject != BackupKeys.FOLDER || value(obs, BackupKeys.TRUNCATED) != "true") return null
        return "The export folder holds more files than one scan reads (${FolderScanner.MAX_HEADERS} headers, newest names first), " +
            "so no app is judged stale or missing until it is tidied: move old exports into another folder, or pick a smaller one."
    }
}
