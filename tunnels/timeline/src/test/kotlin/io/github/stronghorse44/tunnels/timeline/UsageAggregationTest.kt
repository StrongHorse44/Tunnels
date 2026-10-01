package io.github.stronghorse44.tunnels.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class UsageAggregationTest {
    private val zone = ZoneOffset.UTC
    private val day = TimeUnit.DAYS.toMillis(1)
    private val minute = TimeUnit.MINUTES.toMillis(1)
    /** 2026-09-30 12:00 UTC. */
    private val now = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli() + 12 * TimeUnit.HOURS.toMillis(1)

    /** A daily bucket for the day [daysAgo] days before now. */
    private fun daily(pkg: String, daysAgo: Int, fgMinutes: Long, used: Boolean = fgMinutes > 0): UsageBucket {
        val start = LocalDate.of(2026, 9, 30).minusDays(daysAgo.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return UsageBucket(pkg, start, start + day - 1, if (used) start + 10 * minute else 0, fgMinutes * minute)
    }

    @Test
    fun sumsDailyBucketsIntoSevenAndThirtyDayWindows() {
        val daily = listOf(
            daily("a", 0, 10), daily("a", 1, 20), daily("a", 6, 30),   // inside 7 days
            daily("a", 8, 40), daily("a", 9, 50),                       // inside 30 only
            daily("a", 31, 999),                                        // outside the window
            daily("b", 2, 0),                                           // present but unused
        )
        val usage = UsageAggregation.usage(daily, emptyList(), emptyList(), now, zone)
        val a = usage.getValue("a")
        assertEquals(60 * minute, a.fgMs7)
        assertEquals(150 * minute, a.fgMs30)
        assertEquals(5, a.daysUsed30)
        assertEquals(daily("a", 0, 10).lastTimeUsed, a.lastUsedMs)
        assertFalse("b" in usage)
    }

    @Test
    fun weeklyBucketsFillInWhenDailyHistoryIsShort() {
        val daily = listOf(daily("a", 0, 10), daily("a", 3, 10))
        val weekStart = now - 20 * day
        val weekly = listOf(UsageBucket("a", weekStart, weekStart + 7 * day, weekStart + minute, 300 * minute))
        val usage = UsageAggregation.usage(daily, weekly, emptyList(), now, zone).getValue("a")
        assertEquals(20 * minute, usage.fgMs7)
        assertEquals("the larger of the two sums", 300 * minute, usage.fgMs30)
        assertEquals("days only come from daily buckets", 2, usage.daysUsed30)
    }

    @Test
    fun longTermBucketsOnlyContributeLastUsed() {
        val lastYear = now - 200 * day
        val yearly = listOf(UsageBucket("old", now - 400 * day, now, lastYear, 5000 * minute))
        val usage = UsageAggregation.usage(emptyList(), emptyList(), yearly, now, zone).getValue("old")
        assertEquals(0L, usage.fgMs30)
        assertEquals(0, usage.daysUsed30)
        assertEquals(lastYear, usage.lastUsedMs)
        // A bogus future timestamp is ignored rather than becoming "last used tomorrow".
        val future = listOf(UsageBucket("f", now, now, now + 30 * day, 0))
        assertNull(UsageAggregation.usage(emptyList(), emptyList(), future, now, zone)["f"])
    }

    @Test
    fun trafficSplitsByNetworkAndState() {
        val wifi = listOf(
            TrafficBucket(10001, TrafficState.FOREGROUND, 1_000),
            TrafficBucket(10001, TrafficState.BACKGROUND, 2_000),
            TrafficBucket(10002, TrafficState.UNKNOWN, 5_000),
            TrafficBucket(10003, TrafficState.BACKGROUND, -5),
        )
        val mobile = listOf(TrafficBucket(10001, TrafficState.BACKGROUND, 4_000))
        val t = UsageAggregation.traffic(wifi, mobile)
        val a = t.getValue(10001)
        assertEquals(3_000L, a.wifiBytes)
        assertEquals(4_000L, a.mobileBytes)
        assertEquals(7_000L, a.totalBytes)
        assertEquals(1_000L, a.fgBytes)
        assertEquals(6_000L, a.bgBytes)
        assertTrue(a.splitKnown)
        val b = t.getValue(10002)
        assertEquals(5_000L, b.unknownStateBytes)
        assertFalse(b.splitKnown)
        assertNull(t[10003])
    }

    @Test
    fun observationsFollowTheKeySchema() {
        val app = AppFacts("com.x", "X", system = false, firstInstallMs = now - 100 * day)
        val usage = PackageUsage(fgMs7 = 90_000, fgMs30 = 3_600_000, daysUsed30 = 4, lastUsedMs = now - 2 * day)
        val traffic = UidTraffic(wifiBytes = 12_400_000, mobileBytes = 600_000, fgBytes = 3_000_000, bgBytes = 10_000_000)
        val obs = TimelineObservations.forApp(app, usage, 7, traffic, zone)
        val map = obs.associate { it.key to it.value }
        assertTrue(obs.all { it.tunnelId == TimelineKeys.TUNNEL_ID && it.subject == "com.x" })
        assertEquals(
            mapOf(
                TimelineKeys.LABEL to "X",
                TimelineKeys.SYSTEM to "false",
                TimelineKeys.FIRST_INSTALL to "2026-06-22",
                TimelineKeys.FG_MINUTES_7 to "2",
                TimelineKeys.FG_MINUTES_30 to "60",
                TimelineKeys.DAYS_USED_30 to "4",
                TimelineKeys.LAUNCHES_7 to "7",
                TimelineKeys.LAST_USED to "2026-09-28",
                TimelineKeys.WIFI_MB_30 to "12",
                TimelineKeys.MOBILE_MB_30 to "1",
                TimelineKeys.FG_MB_30 to "3",
                TimelineKeys.BG_MB_30 to "10",
            ),
            map,
        )

        val bare = TimelineObservations.forApp(AppFacts("com.y", "", true, 0), null, 0, null, zone).associate { it.key to it.value }
        assertEquals("com.y", bare[TimelineKeys.LABEL])
        assertEquals("true", bare[TimelineKeys.SYSTEM])
        assertFalse(TimelineKeys.FIRST_INSTALL in bare)
        assertEquals(TimelineKeys.NEVER, bare[TimelineKeys.LAST_USED])
        assertEquals("0", bare[TimelineKeys.FG_MINUTES_30])
        assertTrue(bare.keys.none { it.startsWith("net:") })

        val unsplit = TimelineObservations.forApp(app, usage, 1, UidTraffic(wifiBytes = 1, unknownStateBytes = 1), zone).associate { it.key to it.value }
        assertEquals("0", unsplit[TimelineKeys.WIFI_MB_30])
        assertFalse(TimelineKeys.BG_MB_30 in unsplit)
        assertFalse(TimelineKeys.FG_MB_30 in unsplit)
    }

    @Test
    fun summaryShrinksWithoutAccess() {
        val denied = TimelineObservations.summary(false, 12, 3, 5L, TimelineKeys.NET_YES).associate { it.key to it.value }
        assertEquals(mapOf(TimelineKeys.ACCESS_USAGE to TimelineKeys.NOT_GRANTED, TimelineKeys.APPS_TOTAL to "12"), denied)
        val granted = TimelineObservations.summary(true, 12, 3, 2_500_000L, TimelineKeys.NET_YES).associate { it.key to it.value }
        assertEquals(TimelineKeys.GRANTED, granted[TimelineKeys.ACCESS_USAGE])
        assertEquals("3", granted[TimelineKeys.APPS_UNUSED_60])
        assertEquals("3", granted[TimelineKeys.NET_TOTAL_MB_30])
        assertEquals(TimelineKeys.NET_YES, granted[TimelineKeys.NET_AVAILABLE])
        val noNet = TimelineObservations.summary(true, 12, 3, null, TimelineKeys.NET_TIMEOUT).associate { it.key to it.value }
        assertFalse(TimelineKeys.NET_TOTAL_MB_30 in noNet)
        assertEquals(TimelineKeys.NET_TIMEOUT, noNet[TimelineKeys.NET_AVAILABLE])
        assertTrue(TimelineObservations.summary(true, 1, 0, null, TimelineKeys.NET_YES).all { it.subject == TimelineKeys.SUMMARY })
    }

    @Test
    fun rounding() {
        assertEquals(0L, TimelineObservations.minutes(29_999))
        assertEquals(1L, TimelineObservations.minutes(30_000))
        assertEquals(2L, TimelineObservations.megabytes(1_500_000))
        assertEquals(0L, TimelineObservations.megabytes(499_999))
        assertEquals("2026-09-30", TimelineObservations.isoDay(now, zone))
    }
}
