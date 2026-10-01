package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SightingsTest {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun rec(session: String, at: Long, key: String = "ab12cd34", type: TrackerType = TrackerType.TILE, state: TrackerState = TrackerState.UNKNOWN, rssi: Int = -60, count: Int = 1) =
        SightingRecord(session, at, type, key, state, rssi, count)

    @Test
    fun deviceKeyIsPseudonymousAndStable() {
        val k = DeviceKey.of("AA:BB:CC:DD:EE:FF", "svc:feed")
        assertEquals(8, k.length)
        assertTrue(k.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(k, DeviceKey.of("AA:BB:CC:DD:EE:FF", "svc:feed"))
        assertNotEquals(k, DeviceKey.of("AA:BB:CC:DD:EE:00", "svc:feed"))
        assertNotEquals(k, DeviceKey.of("AA:BB:CC:DD:EE:FF", "mfr:004c"))
        assertTrue(!k.contains("AA", ignoreCase = true) || k.length == 8)
    }

    @Test
    fun recordRoundTripsThroughEventsSummary() {
        val r = SightingRecord("s1", t0, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.SEPARATED, -71, 4, battery = "low")
        assertEquals("tracker:findmy:deadbeef", r.subject)
        assertEquals("session=s1;at=$t0;state=separated;rssi=-71;count=4;battery=low", r.encode())
        assertEquals(r, SightingRecord.parse(r.subject, r.encode()))
        // Defaults for missing optional fields, null for garbage.
        val minimal = SightingRecord.parse("tracker:tile:ab12cd34", "session=x;at=5")!!
        assertEquals(TrackerState.UNKNOWN, minimal.state)
        assertEquals(1, minimal.count)
        assertEquals(0, minimal.rssi)
        assertNull(SightingRecord.parse("tracker:tile", "session=x;at=5"))
        assertNull(SightingRecord.parse("tracker:tile:ab12cd34", "at=5"))
        assertNull(SightingRecord.parse("tracker:tile:ab12cd34", "session=x"))
        assertNull(SightingRecord.parse("tracker:nosuch:ab12cd34", "session=x;at=5"))
        assertNull(SightingRecord.parse("wifi:summary", "session=x;at=5"))
    }

    @Test
    fun subjectsParse() {
        assertEquals(TrackerType.TILE to null, SurroundingsKeys.parseTrackerSubject("tracker:tile"))
        assertEquals(TrackerType.TILE to "ab12cd34", SurroundingsKeys.parseTrackerSubject("tracker:tile:ab12cd34"))
        assertNull(SurroundingsKeys.parseTrackerSubject("tracker:tile:"))
        assertNull(SurroundingsKeys.parseTrackerSubject("tracker:"))
        assertNull(SurroundingsKeys.parseTrackerSubject("ble:summary"))
        assertTrue(SurroundingsKeys.isTypeSubject("tracker:findmy"))
        assertTrue(!SurroundingsKeys.isTypeSubject("tracker:findmy:deadbeef"))
    }

    @Test
    fun aggregatorFoldsDevicesAndTypes() {
        val records = listOf(
            rec("s1", t0, rssi = -60),
            rec("s1", t0 + minute, rssi = -70, count = 3), // same device, same session: one session, weighted rssi
            rec("s2", t0 + 40 * minute, rssi = -80),
            rec("s2", t0 + 40 * minute, key = "ffffffff", rssi = -50), // another Tile
            rec("s3", t0 + 90 * minute, key = "11111111", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED, rssi = -65),
            rec("s4", t0 + 200 * minute, key = "22222222", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER, rssi = -45),
        )
        val agg = SightingAggregator.aggregate(records)
        assertEquals(4, agg.devices.size)
        val tile = agg.devices["ab12cd34"]!!
        assertEquals(TrackerType.TILE, tile.type)
        assertEquals(5, tile.sightings)
        assertEquals(2, tile.sessions)
        assertEquals(40, tile.spanMinutes)
        assertEquals(Math.round((-60.0 - 70 * 3 - 80) / 5).toInt(), tile.rssiAvg)
        assertEquals(TrackerState.UNKNOWN, tile.state)

        val tiles = agg.types[TrackerType.TILE]!!
        assertEquals(2, tiles.devices)
        assertEquals(2, tiles.sessions)
        assertEquals(6, tiles.sightings)
        assertEquals(0, tiles.sessionsSeparated)
        assertEquals(TrackerState.UNKNOWN, tiles.state)

        val apple = agg.types[TrackerType.APPLE_FINDMY]!!
        assertEquals(2, apple.devices)
        assertEquals(2, apple.sessions)
        assertEquals(110, apple.spanMinutes)
        assertEquals(1, apple.sessionsSeparated)
        assertEquals(0, apple.spanSeparatedMinutes)
        assertEquals(TrackerState.SEPARATED, apple.state)
        assertEquals(SightingAggregate.EMPTY, SightingAggregator.aggregate(emptyList()))
    }

    @Test
    fun followingHeuristicThresholds() {
        // Fewer than three sessions, or three within half an hour: nothing.
        assertEquals(FollowingLevel.NONE, FollowingHeuristic.assess(TrackerType.TILE, 2, 500, 0, 0))
        assertEquals(FollowingLevel.NONE, FollowingHeuristic.assess(TrackerType.TILE, 3, 29, 0, 0))
        assertEquals(FollowingLevel.WARN, FollowingHeuristic.assess(TrackerType.TILE, 3, 30, 0, 0))
        assertEquals(FollowingLevel.WARN, FollowingHeuristic.assess(TrackerType.SAMSUNG_SMARTTAG, 10, 600, 0, 0))
        // Apple separated: critical only with three separated sessions over an hour.
        assertEquals(FollowingLevel.WARN, FollowingHeuristic.assess(TrackerType.APPLE_FINDMY, 3, 90, 2, 90))
        assertEquals(FollowingLevel.WARN, FollowingHeuristic.assess(TrackerType.APPLE_FINDMY, 3, 90, 3, 59))
        assertEquals(FollowingLevel.CRITICAL, FollowingHeuristic.assess(TrackerType.APPLE_FINDMY, 3, 90, 3, 60))
        // Separated state on a non-Apple type never upgrades to critical.
        assertEquals(FollowingLevel.WARN, FollowingHeuristic.assess(TrackerType.TILE, 5, 300, 5, 300))

        val agg = SightingAggregator.aggregate(
            (0 until 3).map { i -> rec("s$i", t0 + i * 35 * minute, key = "k$i", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED) },
        )
        assertEquals(FollowingLevel.CRITICAL, FollowingHeuristic.assess(agg.types[TrackerType.APPLE_FINDMY]!!))
    }

    @Test
    fun observationsCarryTheSchemaAndStayBounded() {
        val records = (0 until 50).map { i -> rec("s${i % 5}", t0 + i * minute, key = "%08x".format(i)) } +
            rec("s9", t0 + 59 * minute, key = "aaaaaaaa", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED)
        val agg = SightingAggregator.aggregate(records)
        val obs = SurroundingsKeys.bleObservations(agg, devicesTotal = 123, available = SurroundingsKeys.AVAILABLE_YES, now = t0 + 60 * minute, muted = setOf("tracker:findmy"), sessions30d = 6)
        assertTrue(obs.all { it.tunnelId == SurroundingsKeys.TUNNEL_ID })
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        val summary = obs.filter { it.subject == SurroundingsKeys.BLE_SUMMARY }.associate { it.key to it.value }
        assertEquals("yes", summary[SurroundingsKeys.BLE_AVAILABLE])
        assertEquals("123", summary[SurroundingsKeys.DEVICES_TOTAL])
        assertEquals("51", summary[SurroundingsKeys.TRACKERS_TOTAL])
        assertEquals("findmy=1,tile=50", summary[SurroundingsKeys.TRACKERS_BY_TYPE])
        assertEquals("11", summary[SurroundingsKeys.TRACKERS_UNLISTED])
        assertEquals("6", summary[SurroundingsKeys.SESSIONS_30D])

        val deviceSubjects = obs.map { it.subject }.filter { SurroundingsKeys.parseTrackerSubject(it)?.second != null }.toSet()
        assertEquals(SurroundingsKeys.MAX_LISTED_DEVICES, deviceSubjects.size)
        val tileType = obs.filter { it.subject == "tracker:tile" }.associate { it.key to it.value }
        assertEquals("50", tileType[SurroundingsKeys.DEVICES])
        assertEquals("5", tileType[SurroundingsKeys.SEEN_SESSIONS])
        assertEquals("49", tileType[SurroundingsKeys.SEEN_SPAN])
        assertEquals("high", tileType[SurroundingsKeys.CONFIDENCE])
        assertEquals("true", tileType[SurroundingsKeys.SEEN_LAST_DAY])
        assertNull(tileType[SurroundingsKeys.MUTED])
        val appleType = obs.filter { it.subject == "tracker:findmy" }.associate { it.key to it.value }
        assertEquals("true", appleType[SurroundingsKeys.MUTED])
        assertEquals("separated", appleType[SurroundingsKeys.STATE])
        // A muted type mutes its devices too.
        assertEquals("true", obs.first { it.subject == "tracker:findmy:aaaaaaaa" && it.key == SurroundingsKeys.MUTED }.value)
        // No address-like values anywhere.
        assertTrue(obs.none { Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}", RegexOption.IGNORE_CASE).containsMatchIn(it.value) })
    }

    @Test
    fun emptyAggregateStillReportsAvailability() {
        val obs = SurroundingsKeys.bleObservations(SightingAggregate.EMPTY, 0, SurroundingsKeys.AVAILABLE_NO_ADAPTER, t0)
        assertEquals(setOf(SurroundingsKeys.BLE_SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("no adapter", SurroundingsKeys.value(obs, SurroundingsKeys.BLE_AVAILABLE))
        assertEquals("none", SurroundingsKeys.value(obs, SurroundingsKeys.TRACKERS_BY_TYPE))
    }
}
