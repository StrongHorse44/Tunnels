package io.github.stronghorse44.tunnels.watch

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.ScanResult
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import java.time.Instant

/**
 * One check: decide which tunnels to run ([WatchPolicy]), scan them through the snapshot engine without storing an
 * unchanged snapshot, remember what happened, and notify about new findings worth it. Used by the scheduled job and
 * by Check now in the inbox.
 */
object WatchRunner {
    data class Outcome(val plan: WatchPolicy.Plan, val result: ScanResult, val notified: Int)

    suspend fun run(context: Context, notify: Boolean, now: Instant = Instant.now()): Outcome {
        val app = context.applicationContext
        val runtime = TunnelsRuntime.get(app)
        val store = runtime.store
        val settings = WatchSettings.decode(store.setting(WatchSettings.KEY))
        val status = WatchStatus.decode(store.setting(WatchStatus.KEY))
        val packages = PackageChanges.read(app, status)
        val candidates = WatchPolicy.ALWAYS + WatchPolicy.APP_FILES + WatchPolicy.OPTIONAL
        val available = candidates.filter { id -> runtime.registry[id]?.let { accessGranted(app, it) } == true }.toSet()
        val plan = WatchPolicy.plan(available, settings, status, packages.changed, packages.bootCount, now)

        val result = runtime.engine.scan(plan.tunnels, now, storeIfUnchanged = false)

        store.putSetting(
            WatchStatus.KEY,
            WatchStatus(
                lastRunAt = now.toEpochMilli(),
                // Due and done, even when none of the app-file tunnels is in this build: no point retrying each time.
                lastAppFilesAt = if (plan.appFiles) now.toEpochMilli() else status.lastAppFilesAt,
                bootCount = packages.bootCount,
                packageSequence = packages.sequence,
                tunnels = plan.tunnels.size,
                added = result.added.size,
                stored = result.stored,
                failed = result.failures.keys.sorted(),
                reason = plan.reason,
            ).encode(),
        )
        val worth = WatchPolicy.toNotify(result.added, settings.notifyAt)
        val notified = if (notify) WatchNotifier.post(app, worth) else 0
        return Outcome(plan, result, notified)
    }

    /** A tunnel runs in the background only with everything it needs already granted: checks never ask. */
    fun accessGranted(context: Context, module: TunnelModule): Boolean =
        module.requiredPermissions.all { context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED } &&
            module.specialAccess.all { runCatching { it.isGranted() }.getOrDefault(false) }
}

/** Whether apps were installed, updated or removed since the last check, from PackageManager's change log. */
internal object PackageChanges {
    data class State(val changed: Boolean, val bootCount: Int, val sequence: Int)

    /** The change sequence restarts at every boot, so it is only compared within the same boot. */
    fun read(context: Context, status: WatchStatus): State {
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val sameBoot = boot >= 0 && boot == status.bootCount && status.packageSequence >= 0
        val since = if (sameBoot) status.packageSequence else 0
        val changes = runCatching { context.packageManager.getChangedPackages(since) }.getOrNull()
        val others = changes?.packageNames.orEmpty().filter { it != context.packageName }
        return State(changed = !sameBoot || others.isNotEmpty(), bootCount = boot, sequence = changes?.sequenceNumber ?: since)
    }
}
