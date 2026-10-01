package io.github.stronghorse44.tunnels.traffic

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.dns.DnsAggregator
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.dns.TrafficRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.SpecialAccess
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Traffic: which apps talk to which domains. A user-started DNS-only VPN session ([DnsVpnService])
 * counts each app's lookups by registrable domain and writes summaries to the events table; a scan
 * folds the last 30 days of those into per-app observations. Nothing runs without a session.
 */
class TrafficTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = TrafficKeys.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = listOf(
        PermissionSpec(Manifest.permission.POST_NOTIFICATIONS, "Shows the session in the notification shade so you always see when it is running"),
    )

    /** VPN consent is a system dialog, not a Settings screen: the gate opens [VpnConsentActivity] for it. */
    override val specialAccess: List<SpecialAccess> = listOf(
        SpecialAccess(
            id = ACCESS_ID,
            label = "VPN consent",
            reason = "Android asks once before an app may open a VPN tunnel. Tunnels routes only DNS lookups through it, during sessions you start.",
            settingsAction = VpnConsentActivity.ACTION,
            isGranted = { !VpnStatus.consentNeeded(context) },
        ),
    )

    override val rules: List<FindingRule> = TrafficRules.all

    override val showObservations: Boolean = false

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        progress.report(0, STEPS, "reading sessions")
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val events: List<EventEntity> = try {
            val store = TunnelsStore.get(context)
            withTimeoutOrNull(EVENTS_TIMEOUT_MS) { store.dao.events(id, MAX_EVENTS).first() }.orEmpty()
        } catch (_: Exception) {
            emptyList() // store unavailable: report an empty history rather than fail the scan
        }
        val rows = events.filter { it.kind == TrafficKeys.EVENT_KIND && it.at >= cutoff }.map { it.subject to it.summary }

        progress.report(1, STEPS, "summarising ${rows.size} rows")
        val aggregate = DnsAggregator.aggregate(rows)

        progress.report(2, STEPS, "checking VPN state")
        val active = DnsVpnService.isRunning
        val other = !active && VpnStatus.anyVpnActive(context)
        progress.report(STEPS, STEPS, "done")
        return DnsAggregator.observations(aggregate, sessionActive = active, otherVpnActive = other)
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        if (draft.subject == TrafficKeys.SUMMARY) {
            return listOf(FindingAction.OpenSettings(Settings.ACTION_VPN_SETTINGS, "VPN settings"))
        }
        if (!TrafficKeys.isPackageSubject(draft.subject)) {
            // A uid without a package, or an unresolved owner: the app list is the closest useful screen.
            return listOf(FindingAction.OpenSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, "All apps"))
        }
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>(FindingAction.OpenAppDetails(pkg)) // the GrapheneOS Network toggle lives there
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        return actions
    }

    private fun isSystem(pkg: String): Boolean = try {
        (context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        true // gone: offering "Uninstall" would only fail
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        TrafficPanel(state, actions)
    }

    companion object {
        const val ACCESS_ID = "vpn_consent"
        private const val STEPS = 3
        private const val RETENTION_MS = 30L * 24 * 60 * 60_000
        private const val EVENTS_TIMEOUT_MS = 30_000L
        /** At most a few hundred rows per hour-long session; this covers a month of daily sessions. */
        private const val MAX_EVENTS = 20_000
    }
}
