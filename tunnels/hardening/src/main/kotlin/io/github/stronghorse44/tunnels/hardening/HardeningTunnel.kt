package io.github.stronghorse44.tunnels.hardening

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.elf.HardeningKeys
import io.github.stronghorse44.tunnels.elf.HardeningRules
import io.github.stronghorse44.tunnels.elf.NativeLibScan
import io.github.stronghorse44.tunnels.elf.NativeLibs
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
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardening audit: for every installed app (system apps included), which exploit mitigations its native
 * libraries were built with. Base and split APKs are opened as zips, the first [NativeLibs.MAX_LIBS]
 * `lib/<abi>/<file>.so` entries (64-bit first) are read into memory and parsed; only flags and counts
 * leave the parser. Results are cached per (package, lastUpdateTime) for the life of the process.
 */
class HardeningTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = HardeningKeys.TUNNEL_ID

    /** QUERY_ALL_PACKAGES is granted at install time; nothing to ask the user for. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = HardeningRules.all

    private val cache = ConcurrentHashMap<String, CachedScan>()
    private val systemFlag = ConcurrentHashMap<String, Boolean>()

    private class CachedScan(val lastUpdateTime: Long, val scan: NativeLibScan)

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).sortedBy { it.packageName }
        val out = ArrayList<Observation>(packages.size * 8)
        packages.forEachIndexed { index, info ->
            progress.report(index, packages.size, info.packageName)
            try {
                out += inspect(pm, info)
            } catch (e: Exception) {
                out += Observation(id, info.packageName, HardeningKeys.ERROR, e.javaClass.simpleName)
            }
            yield()
        }
        cache.keys.retainAll(packages.mapTo(HashSet()) { it.packageName })
        progress.report(packages.size, packages.size, "done")
        return out
    }

    private fun inspect(pm: PackageManager, info: PackageInfo): List<Observation> {
        val pkg = info.packageName
        val app = info.applicationInfo
        val system = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        systemFlag[pkg] = system
        val obs = ArrayList<Observation>(8)
        fun add(key: String, value: String) = obs.add(Observation(id, pkg, key, value))

        add(HardeningKeys.LABEL, runCatching { app?.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg)
        add(HardeningKeys.SYSTEM, system.toString())

        val scan = scanOf(pkg, info.lastUpdateTime, app)
        add(HardeningKeys.LIBS, scan.total.toString())
        add(HardeningKeys.ABIS, if (scan.abis.isEmpty()) HardeningKeys.NONE else scan.abis.sorted().joinToString(","))
        add(
            HardeningKeys.BITS_64,
            when {
                scan.abis.isEmpty() -> HardeningKeys.NONE
                scan.has64Bit -> "true"
                else -> "false"
            },
        )
        if (scan.libs.isNotEmpty()) {
            val parsed = scan.parsed
            add(HardeningKeys.WEAK, parsed.count { it.report.weak }.toString())
            add(HardeningKeys.PARTIAL_RELRO, parsed.count { !it.report.bindNow }.toString())
            add(HardeningKeys.NO_CANARY, parsed.count { !it.report.canary }.toString())
            val unparsed = scan.libs.size - parsed.size
            if (unparsed > 0) add(HardeningKeys.UNPARSED, unparsed.toString())
        }
        if (scan.truncated) add(HardeningKeys.TRUNCATED, "true")
        if (scan.skipped > 0) add(HardeningKeys.SKIPPED, scan.skipped.toString())
        for (lib in scan.libs) {
            add(HardeningKeys.libKey(lib.abi, lib.name), if (lib.report.parseError == null) lib.report.flags else HardeningKeys.UNPARSED_VALUE)
        }
        return obs
    }

    private fun scanOf(pkg: String, lastUpdateTime: Long, app: ApplicationInfo?): NativeLibScan {
        cache[pkg]?.takeIf { it.lastUpdateTime == lastUpdateTime }?.let { return it.scan }
        val files = buildList {
            app?.sourceDir?.let { add(File(it)) }
            app?.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }
        val scan = if (files.isEmpty()) NativeLibScan.EMPTY else NativeLibs.read(files)
        cache[pkg] = CachedScan(lastUpdateTime, scan)
        return scan
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
        HardeningPanel(state)
    }
}
