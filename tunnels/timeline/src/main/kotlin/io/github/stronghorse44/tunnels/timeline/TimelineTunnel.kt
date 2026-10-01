package io.github.stronghorse44.tunnels.timeline

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
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
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Timeline: which apps the user actually opens and how much data each one moves, from the system's
 * own usage and network counters (Usage access). Everything stored is a per-app count, a day or a
 * rounded megabyte figure over the last 7 or 30 days: no session times, no histograms.
 */
class TimelineTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = TimelineKeys.TUNNEL_ID

    /** PACKAGE_USAGE_STATS is granted from Settings, not a dialog: see [specialAccess]. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val specialAccess: List<SpecialAccess> = listOf(
        SpecialAccess(
            id = "usage_access",
            label = "Usage access",
            reason = "To see which apps you actually use and how much data they move, Android needs Usage access. Nothing leaves the phone.",
            settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
            isGranted = ::hasUsageAccess,
        ),
    )

    override val rules: List<FindingRule> = TimelineRules.all()

    private val systemFlag = ConcurrentHashMap<String, Boolean>()

    /**
     * System queries are blocking binder calls that cannot be interrupted. They run here so a slow one
     * is abandoned after [QUERY_TIMEOUT_MS] and the scan carries on without that data.
     */
    private val queries = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val pm = context.packageManager

        progress.report(0, 1, "apps")
        val apps = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .filter { it.packageName != context.packageName }
            .sortedBy { it.packageName }
            .map { info ->
                val ai = info.applicationInfo
                val system = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                systemFlag[info.packageName] = system
                val label = runCatching { ai?.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
                Triple(AppFacts(info.packageName, label, system, info.firstInstallTime), ai?.uid ?: -1, info.packageName)
            }
        systemFlag.keys.retainAll(apps.mapTo(HashSet()) { it.third })

        val granted = hasUsageAccess()
        if (!granted) {
            // The gate asks for access before the user can scan; "scan everything" may still land here.
            return TimelineObservations.summary(false, apps.size, 0, null, TimelineKeys.NET_ERROR)
        }

        val steps = FIXED_STEPS + apps.size
        progress.report(1, steps, "foreground time")
        val usage = bounded { readUsage(now, zone) }.orEmpty()
        yield()
        progress.report(2, steps, "launches")
        val launches = bounded { readLaunches(now) }.orEmpty()
        yield()
        progress.report(3, steps, "Wi-Fi data")
        val wifi = bounded { readTraffic(ConnectivityManager.TYPE_WIFI, now) }
        yield()
        progress.report(4, steps, "mobile data")
        val mobile = bounded { readTraffic(ConnectivityManager.TYPE_MOBILE, now) }
        yield()
        val netAvailable = when {
            wifi == null || mobile == null -> if (lastQueryFailed) TimelineKeys.NET_ERROR else TimelineKeys.NET_TIMEOUT
            else -> TimelineKeys.NET_YES
        }
        val traffic = if (wifi != null && mobile != null) UsageAggregation.traffic(wifi, mobile) else null

        val out = ArrayList<Observation>(apps.size * 12 + 8)
        val today = LocalDate.now(zone)
        var unused = 0
        apps.forEachIndexed { index, (facts, uid, pkg) ->
            progress.report(FIXED_STEPS + index, steps, pkg)
            try {
                val obs = TimelineObservations.forApp(facts, usage[pkg], launches[pkg] ?: 0, traffic?.get(uid), zone)
                if (TimelineKeys.isUnused(obs, today)) unused++
                out += obs
            } catch (e: Exception) {
                out += Observation(id, pkg, KEY_ERROR, e.javaClass.simpleName)
            }
            if (index % 16 == 0) yield()
        }
        val netTotal = traffic?.values?.sumOf { it.totalBytes }
        out += TimelineObservations.summary(true, apps.size, unused, netTotal, netAvailable)
        progress.report(steps, steps, "done")
        return out
    }

    @Volatile private var lastQueryFailed = false

    /** Runs a blocking system query off this coroutine; null when it threw or did not finish in time. */
    private suspend fun <T> bounded(block: () -> T): T? {
        lastQueryFailed = false
        val deferred = queries.async {
            try {
                block()
            } catch (e: Exception) {
                lastQueryFailed = true
                null
            }
        }
        return withTimeoutOrNull(QUERY_TIMEOUT_MS) { deferred.await() }
    }

    private fun readUsage(now: Long, zone: ZoneId): Map<String, PackageUsage> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        fun query(interval: Int, sinceMs: Long): List<UsageBucket> =
            usm.queryUsageStats(interval, now - sinceMs, now).orEmpty().mapNotNull(::toBucket)
        val daily = query(UsageStatsManager.INTERVAL_DAILY, UsageAggregation.DAYS_30_MS)
        val weekly = query(UsageStatsManager.INTERVAL_WEEKLY, UsageAggregation.DAYS_30_MS)
        val longTerm = query(UsageStatsManager.INTERVAL_YEARLY, LONG_TERM_MS)
        return UsageAggregation.usage(daily, weekly, longTerm, now, zone)
    }

    private fun toBucket(s: UsageStats): UsageBucket? {
        val pkg = s.packageName ?: return null
        return UsageBucket(pkg, s.firstTimeStamp, s.lastTimeStamp, s.lastTimeUsed, s.totalTimeInForeground)
    }

    /** ACTIVITY_RESUMED events per package over the last 7 days. Only the count is kept. */
    private fun readLaunches(now: Long): Map<String, Int> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = usm.queryEvents(now - UsageAggregation.DAYS_7_MS, now) ?: return emptyMap()
        val counts = HashMap<String, Int>()
        val event = UsageEvents.Event()
        var seen = 0
        while (events.hasNextEvent() && seen++ < MAX_EVENTS) {
            if (!events.getNextEvent(event)) break
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val pkg = event.packageName ?: continue
                counts.merge(pkg, 1, Int::plus)
            }
        }
        return counts
    }

    /** Per-uid buckets for one network type over the last 30 days; the system splits them by state. */
    private fun readTraffic(networkType: Int, now: Long): List<TrafficBucket> {
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val stats = nsm.querySummary(networkType, null, now - UsageAggregation.DAYS_30_MS, now) ?: return emptyList()
        val out = ArrayList<TrafficBucket>()
        val bucket = NetworkStats.Bucket()
        try {
            while (stats.hasNextBucket() && out.size < MAX_BUCKETS) {
                if (!stats.getNextBucket(bucket)) break
                val state = when (bucket.state) {
                    NetworkStats.Bucket.STATE_FOREGROUND -> TrafficState.FOREGROUND
                    NetworkStats.Bucket.STATE_DEFAULT -> TrafficState.BACKGROUND
                    else -> TrafficState.UNKNOWN
                }
                out += TrafficBucket(bucket.uid, state, bucket.rxBytes + bucket.txBytes)
            }
        } finally {
            stats.close()
        }
        return out
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val pkg = draft.subject
        if (TimelineKeys.isSummary(pkg)) return emptyList()
        val actions = mutableListOf<FindingAction>(FindingAction.OpenAppDetails(pkg))
        if (!isSystem(pkg)) actions += FindingAction.RequestUninstall(pkg)
        if (draft.kind in TimelineRules.dataKinds) actions += FindingAction.OpenSettings(Settings.ACTION_DATA_USAGE_SETTINGS, "Data usage")
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
        TimelinePanel(state)
    }

    companion object {
        /** Emitted instead of the normal keys when one app's summary threw. */
        const val KEY_ERROR = "scan:error"
        private const val FIXED_STEPS = 5
        private const val QUERY_TIMEOUT_MS = 15_000L
        private const val MAX_EVENTS = 200_000
        private const val MAX_BUCKETS = 50_000
        private val LONG_TERM_MS = TimeUnit.DAYS.toMillis(2 * 365)
    }
}
