package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft

/** What the actions do on the phone; the Android module implements it. Each returns a one-line result for the screen. */
interface BackupEnv {
    /** Starts [app]'s launcher screen, or says it is not installed. */
    suspend fun openApp(app: BackupApp): String

    /** Opens this app's Snapshots screen, where an export is made and an import is tried. */
    suspend fun openSnapshots(): String

    /** Stops watching [appId] (it can be switched on again in the tunnel). */
    suspend fun stopTracking(appId: String): String

    /** Opens this tunnel's own screen, where the folder is chosen. */
    suspend fun openBackups(): String

    /** Forgets the picked folder (and gives its grant back). The result line says that monitoring is now off ([BackupActions.FOLDER_FORGOTTEN]). */
    suspend fun forgetFolder(): String

    /** Records today as the day of the last restore drill. */
    suspend fun recordDrill(): String
}

/** Every Backups finding comes with an action (rule 7): open the app, or the way to refresh it, or stop watching it. */
class BackupActions(private val env: BackupEnv) {
    fun forDraft(draft: FindingDraft): List<FindingAction> {
        if (draft.kind == BackupRules.DRILL_DUE) {
            return listOf(
                FindingAction.Perform("Open Snapshots to try an import") { env.openSnapshots() },
                FindingAction.Perform("I did a drill today") { env.recordDrill() },
            )
        }
        if (draft.kind == BackupRules.FOLDER_LOST) {
            return listOf(
                FindingAction.Perform("Open Backups to choose the folder") { env.openBackups() },
                FindingAction.Perform("Forget the folder") { env.forgetFolder() },
            )
        }
        if (draft.kind == BackupRules.SCAN_INCOMPLETE) {
            return listOf(FindingAction.Perform("Open Backups") { env.openBackups() })
        }
        val app = BackupApps.byId(draft.subject) ?: return emptyList()
        val open = when {
            app.isThisApp -> FindingAction.Perform("Open Snapshots to export") { env.openSnapshots() }
            app.packages.isNotEmpty() -> FindingAction.Perform("Open ${app.name}") { env.openApp(app) }
            else -> FindingAction.Perform("How to refresh") { HOW_TO_REFRESH_SERVER }
        }
        return listOf(open, FindingAction.Perform("Stop watching ${app.name}") { env.stopTracking(app.id) })
    }

    companion object {
        const val FOLDER_FORGOTTEN =
            "Folder forgotten. Backup monitoring is now off: no export is checked and nothing is reported until you choose a folder again in the Backups screen."

        const val HOW_TO_REFRESH_SERVER =
            "Pusher server backups are made on the Chromebook, not by an app on this phone: run backup.sh there, " +
                "then copy the archive into the export folder and scan again."
    }
}
