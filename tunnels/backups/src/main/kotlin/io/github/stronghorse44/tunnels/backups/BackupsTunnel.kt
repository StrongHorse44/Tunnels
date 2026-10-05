package io.github.stronghorse44.tunnels.backups

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Backups: manual exports get forgotten, so this watches them. It reads only the plaintext header of each bundle in
 * the export folder the user picked (never a passphrase, never a payload byte), notes per app when the newest bundle
 * says it was made, and raises a finding when a watched app's newest bundle is older than the threshold or gone.
 * Observations are summaries (app ID, newest time, file count); see [BackupObservations].
 *
 * [sourceFor] opens the stored folder as a [FolderSource] (null: no access any more); tests pass a plain directory.
 */
class BackupsTunnel internal constructor(
    private val context: Context,
    private val sourceFor: (Context, String) -> FolderSource?,
    private val clock: () -> Long,
    private val zone: ZoneId,
) : TunnelModule, TunnelUi {
    constructor(context: Context) : this(context, { c, uri -> SafFolderSource.open(c, uri) }, { System.currentTimeMillis() }, ZoneId.systemDefault())

    override val id: String = BackupKeys.TUNNEL_ID

    /** No permission: the folder is a read grant from the system picker. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = BackupRules.all(zone)

    override val volatileKeys: Set<String> = BackupKeys.VOLATILE

    /** The screen's and the actions' access to the stored choices. */
    internal val config = BackupConfig(context)

    private val findingActions = BackupActions(AndroidEnv())

    override suspend fun scan(progress: ScanProgress): List<Observation> = withContext(Dispatchers.IO) {
        val now = clock()
        progress.report(0, 1, "export folder")
        val settings = config.settings()
        val scan = readFolder(now)
        // Apps found now are watched from now on, so moving their newest bundle out later is noticed.
        if (scan.state == FolderState.OK && scan.apps.keys.any { it !in settings.seen }) {
            config.update { it.copy(seen = it.seen + scan.apps.keys) }
        }
        progress.report(1, 1, "done")
        BackupObservations.build(scan, settings, now, localDate(now))
    }

    private suspend fun readFolder(now: Long): FolderScan {
        val uri = config.folder() ?: return FolderScan.NONE
        return try {
            val source = sourceFor(context, uri) ?: return FolderScan.LOST
            FolderScanner.scan(source, now)
        } catch (_: Exception) {
            FolderScan.LOST
        }
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    override fun actionsFor(draft: FindingDraft): List<FindingAction> = findingActions.forDraft(draft)

    /** Keeps the picked folder across restarts (read only) and lets go of the one it replaces. */
    internal suspend fun chooseFolder(uri: Uri) {
        val resolver = context.contentResolver
        val previous = config.folder()
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (previous != null && previous != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        config.setFolder(uri.toString())
    }

    /** Forgets the folder and gives its grant back. */
    internal suspend fun clearFolder() {
        config.folder()?.let { runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        config.setFolder(null)
    }

    internal fun today(): LocalDate = localDate(clock())

    internal val zoneId: ZoneId get() = zone

    private suspend fun rescan() {
        TunnelsRuntime.get(context).engine.scan(listOf(id))
    }

    private inner class AndroidEnv : BackupEnv {
        override suspend fun openApp(app: BackupApp): String {
            val pm = context.packageManager
            // The merged app manifest already holds QUERY_ALL_PACKAGES, so every package name is visible to this lookup.
            val installed = app.packages.filter { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
            if (installed.isEmpty()) return "${app.name} is not installed on this phone."
            val launch = installed.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                ?: return "${app.name} is installed but has no screen to open."
            return try {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opening ${app.name}."
            } catch (_: Exception) {
                "Could not open ${app.name}."
            }
        }

        override suspend fun openSnapshots(): String = try {
            context.startActivity(Intent(SNAPSHOTS_ACTION).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening Snapshots."
        } catch (_: Exception) {
            "Snapshots could not be opened."
        }

        override suspend fun stopTracking(appId: String): String {
            config.update { it.withTracked(appId, false) }
            rescan()
            return "No longer watching ${BackupApps.nameOf(appId)}. Switch it on again in the Backups screen."
        }

        override suspend fun recordDrill(): String {
            val today = today()
            config.update { it.copy(drill = today) }
            rescan()
            return "Recorded $today as the day of your last restore drill."
        }
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        BackupsPanel(this, state, actions)
    }

    /** The panel presents the apps itself. */
    override val showObservations: Boolean = false

    companion object {
        /** Snapshots' own same-package action (declared by tunnels/snapshots, not exported). */
        const val SNAPSHOTS_ACTION = "io.github.stronghorse44.tunnels.action.SNAPSHOTS"
    }
}
