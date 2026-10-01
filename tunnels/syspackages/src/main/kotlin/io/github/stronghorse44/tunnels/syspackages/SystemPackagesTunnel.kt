package io.github.stronghorse44.tunnels.syspackages

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
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
import io.github.stronghorse44.tunnels.syspkg.SysPkgKeys
import io.github.stronghorse44.tunnels.syspkg.SysPkgRules
import io.github.stronghorse44.tunnels.syspkg.SystemPackageKb
import kotlinx.coroutines.yield

/**
 * System packages: every preinstalled package, whether it is enabled, whether it is known and what
 * it does. Rules flag unknown vendor packages, packages that appear after the first scan, enabled
 * state changes, and a single summary finding when an OS update bumps many versions at once.
 */
class SystemPackagesTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = SysPkgKeys.TUNNEL_ID

    /** QUERY_ALL_PACKAGES is granted at install time; nothing to ask the user for. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = SysPkgRules.all

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS.toLong() or PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        val all = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
            .filter { info -> info.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } == true }
            .sortedBy { it.packageName }
        val packages = all.take(SysPkgKeys.MAX_PACKAGES)
        val out = ArrayList<Observation>(packages.size * 11 + 4)
        var disabled = 0
        var unknown = 0
        packages.forEachIndexed { index, info ->
            progress.report(index, packages.size, info.packageName)
            try {
                val obs = inspect(pm, info)
                if (SysPkgKeys.isDisabled(SysPkgKeys.value(obs, SysPkgKeys.ENABLED))) disabled++
                if (!SysPkgKeys.isKnown(obs)) unknown++
                out += obs
            } catch (e: Exception) {
                out += Observation(id, info.packageName, KEY_ERROR, e.javaClass.simpleName)
            }
            yield()
        }
        fun summary(key: String, value: Int) = out.add(Observation(id, SysPkgKeys.SUMMARY, key, value.toString()))
        summary(SysPkgKeys.TOTAL, packages.size)
        summary(SysPkgKeys.DISABLED, disabled)
        summary(SysPkgKeys.UNKNOWN, unknown)
        if (all.size > packages.size) summary(SysPkgKeys.SKIPPED, all.size - packages.size)
        progress.report(packages.size, packages.size, "done")
        return out
    }

    private fun inspect(pm: PackageManager, info: PackageInfo): List<Observation> {
        val pkg = info.packageName
        val app = info.applicationInfo ?: error("no ApplicationInfo")
        val known = SystemPackageKb.lookup(pkg)
        val obs = ArrayList<Observation>(12)
        fun add(key: String, value: String) = obs.add(Observation(id, pkg, key, value))

        add(SysPkgKeys.LABEL, runCatching { app.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg)
        add(SysPkgKeys.ENABLED, enabledValue(pm, pkg, app))
        add(SysPkgKeys.UPDATED, ((app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0).toString())
        add(SysPkgKeys.VERSION, "${info.versionName ?: "?"} (${info.longVersionCode})")
        add(SysPkgKeys.KNOWN, (known != null).toString())
        add(SysPkgKeys.CATEGORY, known?.category?.label ?: SysPkgKeys.UNKNOWN_CATEGORY)
        add(SysPkgKeys.NAMESPACE, SystemPackageKb.namespaceOf(pkg).label)
        add(SysPkgKeys.PRIVILEGED, SysPkgKeys.isPrivilegedPath(app.sourceDir).toString())
        add(SysPkgKeys.HAS_LAUNCHER, runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false).toString())
        if (known != null) {
            add(SysPkgKeys.PURPOSE, known.purpose)
            add(SysPkgKeys.DISABLE_RISK, known.disableRisk.label)
        }
        return obs
    }

    /** The user-visible enabled state; falls back to ApplicationInfo.enabled when the setting cannot be read. */
    private fun enabledValue(pm: PackageManager, pkg: String, app: ApplicationInfo): String {
        val installed = (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        val state = try {
            pm.getApplicationEnabledSetting(pkg)
        } catch (_: Exception) {
            if (app.enabled) SysPkgKeys.STATE_ENABLED else SysPkgKeys.STATE_DISABLED
        }
        return SysPkgKeys.enabledValue(state, installedForUser = installed)
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        if (draft.subject == SysPkgKeys.SUMMARY) {
            // About phone shows the installed Android version and build the update brought.
            return listOf(FindingAction.OpenSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, "About phone"))
        }
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>(FindingAction.OpenAppDetails(pkg))
        SystemPackageKb.lookup(pkg)?.let { known ->
            actions += FindingAction.Perform("What is this?") { "${known.purpose} ${known.disableRisk.explanation}" }
        }
        return actions
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        SysPkgSummaryPanel(state)
    }

    companion object {
        /** Emitted instead of the normal keys when one package's inspection threw. */
        const val KEY_ERROR = "scan:error"
    }
}
