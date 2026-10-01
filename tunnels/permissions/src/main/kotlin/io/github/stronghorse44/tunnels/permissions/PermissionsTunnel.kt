package io.github.stronghorse44.tunnels.permissions

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityServiceInfo
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.permrules.PermissionCatalog
import io.github.stronghorse44.tunnels.permrules.PermissionGroup
import io.github.stronghorse44.tunnels.permrules.PermissionKeys
import io.github.stronghorse44.tunnels.permrules.PermissionRules
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

/**
 * Declared vs granted permissions for every installed app, read from PackageManager's
 * `requestedPermissionsFlags` (no hidden APIs). GrapheneOS's Network and Sensors toggles show up
 * as the INTERNET and OTHER_SENSORS permissions, so they are reported as toggles too.
 */
class PermissionsTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id = PermissionKeys.TUNNEL_ID

    /** QUERY_ALL_PACKAGES is install-time; nothing to ask the user for. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = PermissionRules.all

    /** Package -> system flag from the latest scan, so actions know when "Uninstall" makes sense. */
    private val systemApps = ConcurrentHashMap<String, Boolean>()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val pm = context.packageManager
        val self = context.packageName
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .map { it.packageName }
            .filter { it != self }
            .sorted()
        val accessibility = accessibilityServices()
        val out = ArrayList<Observation>(packages.size * 24)
        val seenSystem = HashMap<String, Boolean>()
        packages.forEachIndexed { index, pkg ->
            progress.report(index, packages.size, pkg)
            yield()
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                observe(pm, info, accessibility[pkg], out, seenSystem)
            } catch (_: Exception) {
                // One unreadable app must not sink the scan; it is simply absent from this snapshot.
            }
        }
        progress.report(packages.size, packages.size, "done")
        systemApps.clear()
        systemApps.putAll(seenSystem)
        return out
    }

    private fun observe(pm: PackageManager, info: PackageInfo, accessibility: String?, out: MutableList<Observation>, seenSystem: MutableMap<String, Boolean>) {
        val app = info.applicationInfo ?: return
        val pkg = info.packageName
        val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        seenSystem[pkg] = system
        fun obs(key: String, value: String) = out.add(Observation(id, pkg, key, value))

        obs(PermissionKeys.APP_LABEL, runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg))
        obs(PermissionKeys.APP_SYSTEM, system.toString())
        obs(PermissionKeys.APP_TARGET_SDK, app.targetSdkVersion.toString())
        obs(PermissionKeys.APP_VERSION, "${info.versionName ?: "?"} (${info.longVersionCode})")

        val requested = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)
        fun granted(i: Int) = i < flags.size && flags[i] and PackageManager.REQUESTED_PERMISSION_GRANTED != 0

        // Known groups first so the cap trims the long tail of vendor and signature permissions, not the sensitive ones.
        val order = requested.indices.sortedWith(compareBy<Int>({ PermissionCatalog.groupOf(requested[it]) == PermissionGroup.OTHER }, { requested[it] }))
        for (i in order.take(MAX_PERMISSIONS)) {
            obs(PermissionKeys.permKey(requested[i]), if (granted(i)) PermissionKeys.GRANTED else PermissionKeys.DENIED)
        }
        if (requested.size > MAX_PERMISSIONS) obs(PermissionKeys.APP_PERMS_OMITTED, (requested.size - MAX_PERMISSIONS).toString())

        val internet = requested.indexOf(PermissionCatalog.INTERNET)
        obs(
            PermissionKeys.TOGGLE_NETWORK,
            when {
                internet < 0 -> PermissionKeys.NA
                granted(internet) -> PermissionKeys.ON
                else -> PermissionKeys.OFF
            },
        )
        val sensors = requested.indexOf(PermissionCatalog.OTHER_SENSORS)
        obs(
            PermissionKeys.TOGGLE_SENSORS,
            when {
                sensors < 0 -> PermissionKeys.UNKNOWN
                granted(sensors) -> PermissionKeys.ON
                else -> PermissionKeys.OFF
            },
        )
        accessibility?.let { obs(PermissionKeys.ACCESS_ACCESSIBILITY, it) }
    }

    /** Package -> "enabled" | "installed" for apps that ship an accessibility service. Public API, no permission. */
    private fun accessibilityServices(): Map<String, String> = runCatching {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return emptyMap()
        val result = HashMap<String, String>()
        am.installedAccessibilityServiceList.orEmpty().forEach { s -> s.packageName()?.let { result[it] = PermissionKeys.INSTALLED } }
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty().forEach { s -> s.packageName()?.let { result[it] = PermissionKeys.ENABLED } }
        result
    }.getOrDefault(emptyMap())

    private fun AccessibilityServiceInfo.packageName(): String? = resolveInfo?.serviceInfo?.packageName

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>(FindingAction.OpenAppDetails(pkg))
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        return actions
    }

    /** From the last scan, or PackageManager when findings are shown before any scan this process. Unknown apps count as system (no uninstall). */
    private fun isSystem(pkg: String): Boolean = systemApps[pkg] ?: runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(true).also { systemApps[pkg] = it }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        PermissionsSummaryPanel(state.observations)
    }

    companion object {
        /** Per-app cap on `perm:` observations; the rest is counted in app:permsOmitted. */
        const val MAX_PERMISSIONS = 150
    }
}
