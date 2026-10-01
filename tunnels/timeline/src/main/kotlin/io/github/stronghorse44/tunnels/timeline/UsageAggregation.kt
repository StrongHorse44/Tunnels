package io.github.stronghorse44.tunnels.timeline

import io.github.stronghorse44.tunnels.model.Observation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One `UsageStats` bucket, Android-free so the aggregation can be unit-tested. */
data class UsageBucket(
    val packageName: String,
    val firstTimeStamp: Long,
    val lastTimeStamp: Long,
    /** Last time an activity of the package was in the foreground, epoch millis; 0 when never. */
    val lastTimeUsed: Long,
    val foregroundMs: Long,
)

/** Which state a traffic bucket was recorded in. UNKNOWN when the system did not split by state. */
enum class TrafficState { FOREGROUND, BACKGROUND, UNKNOWN }

/** One `NetworkStats.Bucket`, already summed (rx + tx). */
data class TrafficBucket(val uid: Int, val state: TrafficState, val bytes: Long)

/** Per-package foreground use derived from usage buckets. [lastUsedMs] is 0 when the package was never used. */
data class PackageUsage(
    val fgMs7: Long = 0,
    val fgMs30: Long = 0,
    val daysUsed30: Int = 0,
    val lastUsedMs: Long = 0,
)

/** Per-uid traffic over the window, by network and (when the system splits it) by state. */
data class UidTraffic(
    val wifiBytes: Long = 0,
    val mobileBytes: Long = 0,
    val fgBytes: Long = 0,
    val bgBytes: Long = 0,
    val unknownStateBytes: Long = 0,
) {
    val totalBytes: Long get() = wifiBytes + mobileBytes

    /** True when every byte was attributed to foreground or background. */
    val splitKnown: Boolean get() = unknownStateBytes == 0L && totalBytes > 0

    operator fun plus(other: UidTraffic) = UidTraffic(
        wifiBytes + other.wifiBytes,
        mobileBytes + other.mobileBytes,
        fgBytes + other.fgBytes,
        bgBytes + other.bgBytes,
        unknownStateBytes + other.unknownStateBytes,
    )
}

/** Pure aggregation of usage and traffic buckets into per-package and per-uid summaries. */
object UsageAggregation {
    val DAYS_7_MS: Long = TimeUnit.DAYS.toMillis(7)
    val DAYS_30_MS: Long = TimeUnit.DAYS.toMillis(30)

    /**
     * Combines daily buckets (the system keeps about ten days of them), weekly buckets (kept about four
     * weeks) and long-term buckets (monthly or yearly, for the last-used date only). The 30-day minutes
     * are the larger of the daily and weekly sums, so a short daily history does not understate them.
     */
    fun usage(
        daily: List<UsageBucket>,
        weekly: List<UsageBucket>,
        longTerm: List<UsageBucket>,
        now: Long,
        zone: ZoneId,
    ): Map<String, PackageUsage> {
        val since7 = now - DAYS_7_MS
        val since30 = now - DAYS_30_MS
        val fg7 = HashMap<String, Long>()
        val fg30Daily = HashMap<String, Long>()
        val fg30Weekly = HashMap<String, Long>()
        val days = HashMap<String, HashSet<LocalDate>>()
        val lastUsed = HashMap<String, Long>()

        fun noteLastUsed(b: UsageBucket) {
            if (b.lastTimeUsed > 0 && b.lastTimeUsed <= now + DAYS_7_MS) {
                lastUsed[b.packageName] = maxOf(lastUsed[b.packageName] ?: 0L, b.lastTimeUsed)
            }
        }

        for (b in daily) {
            noteLastUsed(b)
            if (b.foregroundMs <= 0 || b.lastTimeStamp < since30) continue
            fg30Daily.merge(b.packageName, b.foregroundMs, Long::plus)
            days.getOrPut(b.packageName) { HashSet() } += Instant.ofEpochMilli(b.firstTimeStamp).atZone(zone).toLocalDate()
            if (b.firstTimeStamp >= since7) fg7.merge(b.packageName, b.foregroundMs, Long::plus)
        }
        for (b in weekly) {
            noteLastUsed(b)
            if (b.foregroundMs <= 0 || b.lastTimeStamp < since30) continue
            fg30Weekly.merge(b.packageName, b.foregroundMs, Long::plus)
        }
        for (b in longTerm) noteLastUsed(b)

        val packages = fg7.keys + fg30Daily.keys + fg30Weekly.keys + lastUsed.keys
        return packages.associateWith { pkg ->
            PackageUsage(
                fgMs7 = fg7[pkg] ?: 0L,
                fgMs30 = maxOf(fg30Daily[pkg] ?: 0L, fg30Weekly[pkg] ?: 0L),
                daysUsed30 = days[pkg]?.size ?: 0,
                lastUsedMs = lastUsed[pkg] ?: 0L,
            )
        }
    }

    /** Sums Wi-Fi and mobile buckets per uid. Negative byte counts (never expected) are ignored. */
    fun traffic(wifi: List<TrafficBucket>, mobile: List<TrafficBucket>): Map<Int, UidTraffic> {
        val out = HashMap<Int, UidTraffic>()
        fun add(b: TrafficBucket, isWifi: Boolean) {
            if (b.bytes <= 0) return
            val part = UidTraffic(
                wifiBytes = if (isWifi) b.bytes else 0,
                mobileBytes = if (isWifi) 0 else b.bytes,
                fgBytes = if (b.state == TrafficState.FOREGROUND) b.bytes else 0,
                bgBytes = if (b.state == TrafficState.BACKGROUND) b.bytes else 0,
                unknownStateBytes = if (b.state == TrafficState.UNKNOWN) b.bytes else 0,
            )
            out[b.uid] = (out[b.uid] ?: UidTraffic()) + part
        }
        wifi.forEach { add(it, isWifi = true) }
        mobile.forEach { add(it, isWifi = false) }
        return out
    }
}

/** What the scan knows about one installed app before usage and traffic are attached. */
data class AppFacts(
    val packageName: String,
    val label: String,
    val system: Boolean,
    /** Epoch millis of the first install; 0 when unknown. */
    val firstInstallMs: Long,
)

/** Builds the tunnel's observations from aggregated facts. Pure, so the key schema is unit-tested. */
object TimelineObservations {
    private const val TUNNEL = TimelineKeys.TUNNEL_ID

    fun isoDay(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** Milliseconds to whole minutes, rounded. */
    fun minutes(ms: Long): Long = (ms + 30_000L) / 60_000L

    /** Bytes to whole SI megabytes, rounded. */
    fun megabytes(bytes: Long): Long = Math.round(bytes / 1_000_000.0)

    /**
     * One app's observations. [traffic] is null when network totals were unavailable this scan, so no
     * `net:` keys are emitted and change rules cannot mistake the gap for a drop to zero.
     */
    fun forApp(app: AppFacts, usage: PackageUsage?, launches7: Int, traffic: UidTraffic?, zone: ZoneId): List<Observation> {
        val pkg = app.packageName
        val obs = ArrayList<Observation>(12)
        fun add(key: String, value: String) = obs.add(Observation(TUNNEL, pkg, key, value))
        add(TimelineKeys.LABEL, app.label.ifBlank { pkg })
        add(TimelineKeys.SYSTEM, app.system.toString())
        if (app.firstInstallMs > 0) add(TimelineKeys.FIRST_INSTALL, isoDay(app.firstInstallMs, zone))
        val u = usage ?: PackageUsage()
        add(TimelineKeys.FG_MINUTES_7, minutes(u.fgMs7).toString())
        add(TimelineKeys.FG_MINUTES_30, minutes(u.fgMs30).toString())
        add(TimelineKeys.DAYS_USED_30, u.daysUsed30.toString())
        add(TimelineKeys.LAUNCHES_7, launches7.toString())
        add(TimelineKeys.LAST_USED, if (u.lastUsedMs > 0) isoDay(u.lastUsedMs, zone) else TimelineKeys.NEVER)
        if (traffic != null) {
            add(TimelineKeys.WIFI_MB_30, megabytes(traffic.wifiBytes).toString())
            add(TimelineKeys.MOBILE_MB_30, megabytes(traffic.mobileBytes).toString())
            if (traffic.splitKnown) {
                add(TimelineKeys.FG_MB_30, megabytes(traffic.fgBytes).toString())
                add(TimelineKeys.BG_MB_30, megabytes(traffic.bgBytes).toString())
            }
        }
        return obs
    }

    /** The device-wide summary subject. [netTotalBytes] is null when traffic could not be read. */
    fun summary(accessGranted: Boolean, appsTotal: Int, unused60: Int, netTotalBytes: Long?, netAvailable: String): List<Observation> {
        val obs = ArrayList<Observation>(5)
        fun add(key: String, value: String) = obs.add(Observation(TUNNEL, TimelineKeys.SUMMARY, key, value))
        add(TimelineKeys.ACCESS_USAGE, if (accessGranted) TimelineKeys.GRANTED else TimelineKeys.NOT_GRANTED)
        add(TimelineKeys.APPS_TOTAL, appsTotal.toString())
        if (accessGranted) {
            add(TimelineKeys.APPS_UNUSED_60, unused60.toString())
            add(TimelineKeys.NET_AVAILABLE, netAvailable)
            if (netTotalBytes != null) add(TimelineKeys.NET_TOTAL_MB_30, megabytes(netTotalBytes).toString())
        }
        return obs
    }
}
