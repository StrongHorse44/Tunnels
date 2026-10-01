package io.github.stronghorse44.tunnels.doors

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.provider.Settings
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

/**
 * The doors into each app: exported activities, services, receivers and providers, which of them
 * any app may knock on, verified web links, and share and browser handlers. Counts only.
 */
class DoorsTunnel(private val context: Context) : TunnelModule {
    override val id = DoorsKeys.TUNNEL_ID

    /** QUERY_ALL_PACKAGES is install-time; nothing to ask the user for. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = DoorsRules.all

    /** Package -> system flag from the latest scan, so actions know when "Uninstall" makes sense. */
    private val systemApps = ConcurrentHashMap<String, Boolean>()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val pm = context.packageManager
        val self = context.packageName
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .map { it.packageName }
            .filter { it != self }
            .sorted()
        val shareTargets = handlers(Intent(Intent.ACTION_SEND).setType("text/plain"), 0) + handlers(Intent(Intent.ACTION_SEND).setType("image/*"), 0)
        val browsers = handlers(browseIntent("http://example.com/"), PackageManager.MATCH_ALL) + handlers(browseIntent("https://example.com/"), PackageManager.MATCH_ALL)
        val verification = runCatching { context.getSystemService(DomainVerificationManager::class.java) }.getOrNull()

        val out = ArrayList<Observation>(packages.size * 16)
        val seenSystem = HashMap<String, Boolean>()
        packages.forEachIndexed { index, pkg ->
            progress.report(index, packages.size, pkg)
            yield()
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(COMPONENT_FLAGS))
                observe(pm, info, verification, pkg in shareTargets, pkg in browsers, out, seenSystem)
            } catch (_: Exception) {
                // One unreadable app must not sink the scan; it is simply absent from this snapshot.
            }
        }
        progress.report(packages.size, packages.size, "done")
        systemApps.clear()
        systemApps.putAll(seenSystem)
        return out
    }

    private fun observe(
        pm: PackageManager,
        info: PackageInfo,
        verification: DomainVerificationManager?,
        share: Boolean,
        browser: Boolean,
        out: MutableList<Observation>,
        seenSystem: MutableMap<String, Boolean>,
    ) {
        val app = info.applicationInfo ?: return
        val pkg = info.packageName
        val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        seenSystem[pkg] = system
        fun obs(key: String, value: String) = out.add(Observation(id, pkg, key, value))
        fun num(key: String, value: Int) = obs(key, value.toString())

        obs(DoorsKeys.APP_LABEL, runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg))
        obs(DoorsKeys.APP_SYSTEM, system.toString())

        val activities = info.activities.orEmpty().filter { it.exported }
        val services = info.services.orEmpty().filter { it.exported }
        val receivers = info.receivers.orEmpty().filter { it.exported }
        val providers = info.providers.orEmpty().filter { it.exported }
        val openActivities = activities.count { it.permission == null }
        val openServices = services.count { it.permission == null }
        val openReceivers = receivers.count { it.permission == null }
        val openProviders = providers.count { it.readPermission == null && it.writePermission == null }

        num(DoorsKeys.EXPORTED_ACTIVITIES, activities.size)
        num(DoorsKeys.EXPORTED_SERVICES, services.size)
        num(DoorsKeys.EXPORTED_RECEIVERS, receivers.size)
        num(DoorsKeys.EXPORTED_PROVIDERS, providers.size)
        num(DoorsKeys.UNPROTECTED_ACTIVITIES, openActivities)
        num(DoorsKeys.UNPROTECTED_SERVICES, openServices)
        num(DoorsKeys.UNPROTECTED_RECEIVERS, openReceivers)
        num(DoorsKeys.UNPROTECTED_PROVIDERS, openProviders)
        num(DoorsKeys.EXPORTED_UNPROTECTED, openActivities + openServices + openReceivers + openProviders)
        num(DoorsKeys.PROVIDER_GRANT_URI, providers.count { it.grantUriPermissions })

        val (verified, selected) = linkDomains(verification, pkg)
        num(DoorsKeys.LINKS_VERIFIED, verified)
        num(DoorsKeys.LINKS_SELECTED, selected)
        if (share) obs(DoorsKeys.HANDLER_SHARE, DoorsKeys.TRUE)
        if (browser) obs(DoorsKeys.HANDLER_BROWSER, DoorsKeys.TRUE)
    }

    /** Verified and user-selected domain counts; zeros when the app declares no web domains or cannot be queried. */
    private fun linkDomains(manager: DomainVerificationManager?, pkg: String): Pair<Int, Int> {
        if (manager == null) return 0 to 0
        val state = try {
            manager.getDomainVerificationUserState(pkg) ?: return 0 to 0
        } catch (_: PackageManager.NameNotFoundException) {
            return 0 to 0
        } catch (_: Exception) {
            return 0 to 0
        }
        val states = state.hostToStateMap.values
        return states.count { it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED } to states.count { it == DomainVerificationUserState.DOMAIN_STATE_SELECTED }
    }

    private fun browseIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Packages with an activity that handles [intent]. */
    private fun handlers(intent: Intent, flags: Int): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
    }.getOrDefault(emptySet())

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val pkg = draft.subject
        val details = FindingAction.OpenAppDetails(pkg)
        val openByDefault = FindingAction.Perform("Open-by-default settings") { openByDefault(pkg) }
        val linkKinds = draft.kind == DoorsRules.LINKS_CHANGED || draft.kind == DoorsRules.NEW_LINK_HANDLER
        val actions = if (linkKinds) mutableListOf(openByDefault, details) else mutableListOf(details, openByDefault)
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        return actions
    }

    /** Settings > Apps > [pkg] > Open by default. */
    private fun openByDefault(pkg: String): String = try {
        context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened"
    } catch (_: ActivityNotFoundException) {
        "No screen on this phone handles that."
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** From the last scan, or PackageManager when findings are shown before any scan this process. Unknown apps count as system (no uninstall). */
    private fun isSystem(pkg: String): Boolean = systemApps[pkg] ?: runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(true).also { systemApps[pkg] = it }

    companion object {
        private const val COMPONENT_FLAGS: Long =
            (PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS).toLong()
    }
}
