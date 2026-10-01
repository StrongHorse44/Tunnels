package io.github.stronghorse44.tunnels.timeline

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineStatsTest {
    private val t = TimelineKeys.TUNNEL_ID

    private fun app(pkg: String, label: String, minutes: Long, wifi: Long?, mobile: Long?) = buildList {
        add(Observation(t, pkg, TimelineKeys.LABEL, label))
        add(Observation(t, pkg, TimelineKeys.SYSTEM, "false"))
        add(Observation(t, pkg, TimelineKeys.FG_MINUTES_30, minutes.toString()))
        wifi?.let { add(Observation(t, pkg, TimelineKeys.WIFI_MB_30, it.toString())) }
        mobile?.let { add(Observation(t, pkg, TimelineKeys.MOBILE_MB_30, it.toString())) }
    }

    @Test
    fun ranksTopFiveByMinutesAndByData() {
        val obs = (1..7).flatMap { i -> app("com.app$i", "App $i", minutes = i * 10L, wifi = (8 - i) * 100L, mobile = 1) } +
            app("com.idle", "Idle", 0, 0, 0) +
            app("com.nonet", "No net", 5, null, null) +
            TimelineObservations.summary(true, 9, 2, 3_000_000_000L, TimelineKeys.NET_YES)
        val s = TimelineStats.from(obs)
        assertEquals(true, s.accessGranted)
        assertEquals(9, s.apps)
        assertEquals(2, s.unused60)
        assertEquals(3000L, s.totalMb)
        assertEquals(listOf("App 7", "App 6", "App 5", "App 4", "App 3"), s.topByForeground.map { it.label })
        assertEquals(70L, s.topByForeground.first().amount)
        assertEquals(listOf("App 1", "App 2", "App 3", "App 4", "App 5"), s.topByData.map { it.label })
        assertEquals(701L, s.topByData.first().amount)
    }

    @Test
    fun emptyAndDeniedStates() {
        assertEquals(TimelineStats.EMPTY, TimelineStats.from(emptyList()))
        assertNull(TimelineStats.EMPTY.accessGranted)
        val denied = TimelineStats.from(TimelineObservations.summary(false, 40, 0, null, TimelineKeys.NET_ERROR))
        assertEquals(false, denied.accessGranted)
        assertEquals(40, denied.apps)
        assertNull(denied.totalMb)
        assertEquals(emptyList<TimelineStats.Entry>(), denied.topByForeground)
    }
}
