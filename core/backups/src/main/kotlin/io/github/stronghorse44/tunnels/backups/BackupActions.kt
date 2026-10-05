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
        val app = BackupApps.byId(draft.subject) ?: return emptyList()
        val open = when {
            app.isThisApp -> FindingAction.Perform("Open Snapshots to export") { env.openSnapshots() }
            app.packages.isNotEmpty() -> FindingAction.Perform("Open ${app.name}") { env.openApp(app) }
            else -> FindingAction.Perform("How to refresh") { HOW_TO_REFRESH_SERVER }
        }
        return listOf(open, FindingAction.Perform("Stop watching ${app.name}") { env.stopTracking(app.id) })
    }

    companion object {
        const val HOW_TO_REFRESH_SERVER =
            "Pusher server backups are made on the Chromebook, not by an app on this phone: run backup.sh there, " +
                "then copy the archive into the export folder and scan again."
    }
}
