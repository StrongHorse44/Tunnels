package io.github.stronghorse44.tunnels.crossroads

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.crossrules.CrossJoin
import io.github.stronghorse44.tunnels.crossrules.CrossKeys
import io.github.stronghorse44.tunnels.crossrules.CrossRules
import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import java.util.concurrent.ConcurrentHashMap

/**
 * Crossroads: findings that take two tunnels to see. It reads no system state of its own: the snapshot engine runs
 * it after Permissions, APK excavation, Traffic, Timeline or Deep mode is scanned, with their latest data
 * ([CrossJoin]), and [CrossRules] turn the joined facts into findings. Every finding points at the app.
 */
class CrossroadsTunnel(private val context: Context) : DerivedTunnel, TunnelUi {
    override val id: String = CrossKeys.TUNNEL_ID
    override val sources: Set<String> = CrossKeys.Sources.ALL
    override val requiredPermissions: List<PermissionSpec> = emptyList()
    override val rules: List<FindingRule> = CrossRules.all

    private val systemApps = ConcurrentHashMap<String, Boolean>()

    override fun derive(input: DerivedInput): List<Observation> = CrossJoin.observe(input)

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        if (draft.subject == CrossKeys.SUMMARY) return emptyList()
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>()
        if (draft.kind in CrossRules.ACCESSIBILITY_KINDS) {
            actions += FindingAction.OpenSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings")
        }
        actions += FindingAction.OpenAppDetails(pkg) // permissions, the GrapheneOS Network toggle, "pause if unused"
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        return actions
    }

    /** Unknown or vanished apps count as system: offering Uninstall would only fail. */
    private fun isSystem(pkg: String): Boolean = systemApps.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            true
        }
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        CrossroadsPanel(state)
    }
}
