package io.github.stronghorse44.tunnels.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
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
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import io.github.stronghorse44.tunnels.trackers.ApkRules
import io.github.stronghorse44.tunnels.trackers.ExportedCounts
import io.github.stronghorse44.tunnels.trackers.TrackerMatcher
import kotlinx.coroutines.yield
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * APK excavation: for every installed app, which known SDKs its dex files embed, who signed it, where
 * it was installed from, which Android it targets and what native code it ships. Dex and native-lib
 * results are cached per (package, lastUpdateTime) for the life of the process, so a repeat scan
 * only re-reads apps that were updated.
 */
class ApkExcavationTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = ApkKeys.TUNNEL_ID

    /** QUERY_ALL_PACKAGES is granted at install time; nothing to ask the user for. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = ApkRules.all

    private val matcher = TrackerMatcher.DEFAULT
    private val cache = ConcurrentHashMap<String, CachedContents>()
    private val systemFlag = ConcurrentHashMap<String, Boolean>()
    private val exportedCache = ConcurrentHashMap<String, Pair<Long, Pair<ExportedCounts, List<String>>>>()

    private class CachedContents(val lastUpdateTime: Long, val contents: ApkContents)

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .filter { it.packageName != context.packageName }
            .sortedBy { it.packageName }
        val out = ArrayList<Observation>(packages.size * 14)
        packages.forEachIndexed { index, info ->
            progress.report(index, packages.size, info.packageName)
            try {
                out += inspect(pm, info)
            } catch (e: Exception) {
                out += Observation(id, info.packageName, KEY_ERROR, e.javaClass.simpleName)
            }
            yield()
        }
        val present = packages.mapTo(HashSet()) { it.packageName }
        cache.keys.retainAll(present)
        exportedCache.keys.retainAll(present)
        progress.report(packages.size, packages.size, "done")
        return out
    }

    private fun inspect(pm: PackageManager, info: PackageInfo): List<Observation> {
        val pkg = info.packageName
        val app = info.applicationInfo
        val system = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        systemFlag[pkg] = system
        val obs = ArrayList<Observation>(16)
        fun add(key: String, value: String) = obs.add(Observation(id, pkg, key, value))

        add(ApkKeys.LABEL, runCatching { app?.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg)
        add(ApkKeys.SYSTEM, system.toString())
        add(ApkKeys.VERSION, "${info.versionName ?: "?"} (${info.longVersionCode})")

        val contents = contentsOf(pkg, info.lastUpdateTime, app)
        for ((trackerId, _) in contents.hits.entries.sortedBy { it.key }) {
            val tracker = matcher.tracker(trackerId) ?: continue
            add(ApkKeys.sdkKey(trackerId), ApkKeys.categoriesValue(tracker.categories))
        }
        add(ApkKeys.SDK_COUNT, contents.hits.size.toString())
        contents.dexSkipped?.let { add(ApkKeys.SDK_SKIPPED, it) }

        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        add(ApkKeys.CERT_SHA256, signers.firstOrNull()?.let(::sha256) ?: "none")
        add(ApkKeys.CERT_COUNT, signers.size.toString())
        // Android keeps a rotation history only for single-signer apps; multi-signer apps cannot rotate.
        val history = info.signingInfo?.takeIf { !it.hasMultipleSigners() }?.signingCertificateHistory?.size ?: 0
        add(ApkKeys.CERT_LINEAGE, maxOf(history - 1, 0).toString())

        add(ApkKeys.INSTALLER, runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull() ?: ApkKeys.UNKNOWN_INSTALLER)
        add(ApkKeys.TARGET_SDK, (app?.targetSdkVersion ?: 0).toString())
        add(ApkKeys.MIN_SDK, (app?.minSdkVersion ?: 0).toString())
        add(ApkKeys.NATIVE_ABIS, if (contents.abis.isEmpty()) ApkKeys.NO_ABIS else contents.abis.sorted().joinToString(","))
        add(ApkKeys.NATIVE_LIBS, contents.nativeLibs.toString())
        add(ApkKeys.SIZE_MB, Math.round(contents.bytes / (1024.0 * 1024.0)).toString())
        if (app != null && (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) add(ApkKeys.DEBUGGABLE, "true")
        add(ApkKeys.CLEARTEXT, (app != null && (app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0).toString())
        // System apps export what the OS needs; only user apps' unguarded components are worth reading.
        if (!system) exportedOf(pm, pkg, info.lastUpdateTime)?.let { (counts, providers) ->
            add(ApkKeys.EXPORTED_OPEN, counts.encode())
            if (providers.isNotEmpty()) add(ApkKeys.OPEN_PROVIDERS, providers.joinToString(","))
        }
        return obs
    }

    /**
     * Exported components with no permission on them, per package version. One PackageManager call per app: asking for
     * every app's components at once can exceed the binder transaction limit. Null when Android would not say.
     */
    private fun exportedOf(pm: PackageManager, pkg: String, lastUpdateTime: Long): Pair<ExportedCounts, List<String>>? {
        exportedCache[pkg]?.takeIf { it.first == lastUpdateTime }?.let { return it.second }
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val result = runCatching {
            val p = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            val providers = p.providers.orEmpty().filter { it.exported && it.readPermission == null && it.writePermission == null }
            ExportedCounts(
                activities = p.activities.orEmpty().count { it.exported && it.permission == null },
                services = p.services.orEmpty().count { it.exported && it.permission == null },
                receivers = p.receivers.orEmpty().count { it.exported && it.permission == null },
                providers = providers.size,
            ) to providers.mapNotNull { it.authority?.substringBefore(';') }.sorted().take(ApkKeys.OPEN_PROVIDERS_MAX)
        }.getOrNull() ?: return null
        exportedCache[pkg] = lastUpdateTime to result
        return result
    }

    private fun contentsOf(pkg: String, lastUpdateTime: Long, app: ApplicationInfo?): ApkContents {
        cache[pkg]?.takeIf { it.lastUpdateTime == lastUpdateTime }?.let { return it.contents }
        val files = buildList {
            app?.sourceDir?.let { add(File(it)) }
            app?.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }
        val contents = ApkContents.read(files, matcher)
        cache[pkg] = CachedContents(lastUpdateTime, contents)
        return contents
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val pkg = draft.subject
        val actions = mutableListOf<FindingAction>(FindingAction.OpenAppDetails(pkg))
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        return actions
    }

    private fun isSystem(pkg: String): Boolean = systemFlag[pkg] ?: run {
        val system = try {
            (context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            // Gone: offering "Uninstall" would only fail.
            true
        }
        systemFlag[pkg] = system
        system
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        ApkSummaryPanel(state)
    }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }

    companion object {
        /** Emitted instead of the normal keys when one app's inspection threw. */
        const val KEY_ERROR = "scan:error"
    }
}
