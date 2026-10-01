package io.github.stronghorse44.tunnels.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.SpecialAccess
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.notifrules.NotifAggregator
import io.github.stronghorse44.tunnels.notifrules.NotifEvent
import io.github.stronghorse44.tunnels.notifrules.NotifKeys
import io.github.stronghorse44.tunnels.notifrules.NotifRecord
import io.github.stronghorse44.tunnels.notifrules.NotifRules
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notifications: who notifies, how often, what shows on the lock screen. The listener service records one
 * line of flags per post; a scan aggregates those lines over 7 and 30 days into per-app counts.
 */
class NotificationsTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = NotifKeys.TUNNEL_ID

    /** Notification access is special access from Settings, not a runtime permission. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val specialAccess: List<SpecialAccess> = listOf(
        SpecialAccess(
            id = ACCESS_ID,
            label = "Notification access",
            reason = "To count who notifies you, how often, and what shows on the lock screen. Notification text is never stored.",
            settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            isGranted = { NotifListenerService.isAccessGranted(context) },
        ),
    )

    override val rules: List<FindingRule> = NotifRules.all

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        progress.report(0, STEPS, "reading events")
        val granted = NotifListenerService.isAccessGranted(context)
        val connected = NotifListenerService.connected
        val store = TunnelsStore.get(context)
        val rows = withTimeoutOrNull(EVENTS_TIMEOUT_MS) { store.dao.events(id, MAX_EVENTS).first() }.orEmpty()

        progress.report(1, STEPS, "counting ${rows.size} events")
        val events = rows.mapNotNull { row ->
            if (row.kind != NotifKeys.EVENT_POSTED) return@mapNotNull null
            val record = runCatching { NotifRecord.parse(row.summary) }.getOrNull() ?: return@mapNotNull null
            NotifEvent(row.subject, row.at, record)
        }
        val aggregate = NotifAggregator.aggregate(events, System.currentTimeMillis())

        progress.report(2, STEPS, "naming ${aggregate.perPackage.size} apps")
        val pm = context.packageManager
        val labels = HashMap<String, String?>()
        val observations = NotifAggregator.observations(aggregate, connected, granted) { pkg -> labels.getOrPut(pkg) { labelOf(pm, pkg) } }
        progress.report(STEPS, STEPS, "done")
        return observations
    }

    private fun labelOf(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    } catch (_: Exception) {
        null // uninstalled since it notified (NameNotFoundException): the package name stands in
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        if (draft.subject == NotifKeys.SUMMARY) {
            return listOf(
                FindingAction.OpenSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Notification access"),
                FindingAction.Perform("Reconnect listener") {
                    NotifListenerService.requestReconnect(context)
                    "Asked the system to reconnect the listener."
                },
            )
        }
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>(
            FindingAction.Perform("Notification settings") { openAppNotificationSettings(pkg) },
            FindingAction.OpenAppDetails(pkg),
        )
        if (draft.kind == NotifRules.LOCK_SCREEN_EXPOSURE) {
            actions += FindingAction.OpenSettings(ACTION_NOTIFICATION_SETTINGS, "System notification settings")
        }
        return actions
    }

    /** The system's per-app notification screen, where channels can be silenced, hidden from the lock screen or turned off. */
    private fun openAppNotificationSettings(pkg: String): String {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opening notification settings…"
        } catch (_: ActivityNotFoundException) {
            "No screen on this phone handles that."
        }
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        NotificationsPanel(state)
    }

    companion object {
        const val ACCESS_ID = "notification_access"
        /** The system-wide notification screen (lock screen notifications, DND). `Settings.ACTION_NOTIFICATION_SETTINGS` is not public API. */
        const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"
        /** Most events a scan reads; at one row per post this covers well over a month of heavy use. */
        const val MAX_EVENTS = 20_000
        private const val EVENTS_TIMEOUT_MS = 30_000L
        private const val STEPS = 3
    }
}
