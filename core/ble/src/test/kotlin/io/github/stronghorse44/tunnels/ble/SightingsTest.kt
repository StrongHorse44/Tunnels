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
        val r = SightingRecord("s1", t0, TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.SEPARATED, -71, 4, battery = "low", kind = "airtag")
        assertEquals("tracker:findmy:deadbeef", r.subject)
        assertEquals("session=s1;at=$t0;state=separated;rssi=-71;count=4;battery=low;kind=airtag", r.encode())
        assertEquals(r, SightingRecord.parse(r.subject, r.encode()))
        // Rows written before the kind field existed still parse.
        assertEquals(r.copy(kind = null), SightingRecord.parse(r.subject, "session=s1;at=$t0;state=separated;rssi=-71;count=4;battery=low"))
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
        assertEquals(-80, tile.rssiLast)
        assertEquals("s2", tile.lastSession)
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
        assertEquals(1, apple.devicesWithOwner)
        assertEquals(SightingAggregate.EMPTY, SightingAggregator.aggregate(emptyList()))
    }

    @Test
    fun foldKeepsKindBatteryAndSeparatedAcrossAWindow() {
        val fold = SightingFold(TrackerType.APPLE_FINDMY)
        fold.add(TrackerMatch(TrackerType.APPLE_FINDMY, TrackerState.WITH_OWNER, "mfr:004c", Confidence.HIGH, battery = "full", kind = AppleFindMyFrame.KIND_AIRTAG), -60)
        fold.add(TrackerMatch(TrackerType.APPLE_FINDMY, TrackerState.SEPARATED, "mfr:004c", Confidence.HIGH, battery = "low", kind = AppleFindMyFrame.KIND_AIRTAG), -70)
        fold.add(TrackerMatch(TrackerType.APPLE_FINDMY, TrackerState.WITH_OWNER, "mfr:004c", Confidence.HIGH), -80)
        val r = fold.record("s1", t0, "deadbeef")
        // The kind the first frame carried survives a later frame without one and reaches the stored row.
        assertEquals(AppleFindMyFrame.KIND_AIRTAG, r.kind)
        assertEquals("low", r.battery)
        assertEquals(TrackerState.SEPARATED, r.state)
        assertEquals(3, r.count)
        assertEquals(-70, r.rssi)
        assertTrue(r.encode().contains("kind=airtag"))
        assertEquals(AppleFindMyFrame.KIND_AIRTAG, SightingAggregator.aggregate(listOf(r)).devices["deadbeef"]!!.kind)
        // A Tile never states kind or battery: both stay null and the state stays unknown.
        val tile = SightingFold(TrackerType.TILE).apply { add(TrackerMatch(TrackerType.TILE, TrackerState.UNKNOWN, "svc:feed", Confidence.HIGH), -55) }.record("s1", t0, "k")
        assertNull(tile.kind)
        assertNull(tile.battery)
        assertEquals(TrackerState.UNKNOWN, tile.state)
        assertEquals(0, SightingFold(TrackerType.TILE).record("s", t0, "k").rssi)
    }

    @Test
    fun typeStateFollowsItsDevices() {
        // Two identities both near their owner in one scan: the family is near its owner, not "unknown".
        val withOwner = SightingAggregator.aggregate(
            listOf(
                rec("s1", t0, key = "a1", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER),
                rec("s1", t0, key = "a2", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER),
            ),
        ).types[TrackerType.APPLE_FINDMY]!!
        assertEquals(TrackerState.WITH_OWNER, withOwner.state)
        assertEquals(2, withOwner.devicesWithOwner)
        // One near its owner and one that said nothing: unknown for the family.
        val mixed = SightingAggregator.aggregate(
            listOf(
                rec("s1", t0, key = "a1", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER),
                rec("s1", t0, key = "a2", type = TrackerType.APPLE_FINDMY, state = TrackerState.UNKNOWN),
            ),
        ).types[TrackerType.APPLE_FINDMY]!!
        assertEquals(TrackerState.UNKNOWN, mixed.state)
        // A device that was separated earlier and near its owner later: the device is near its owner now, the family stays separated (it was seen away once).
        val flipped = SightingAggregator.aggregate(
            listOf(
                rec("s1", t0, key = "a1", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED),
                rec("s2", t0 + 40 * minute, key = "a1", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER),
            ),
        )
        assertEquals(TrackerState.WITH_OWNER, flipped.devices["a1"]!!.state)
        assertEquals(TrackerState.SEPARATED, flipped.types[TrackerType.APPLE_FINDMY]!!.state)
        // Kind survives aggregation from the latest record that carried one.
        val kind = SightingAggregator.aggregate(
            listOf(
                rec("s1", t0, key = "k", type = TrackerType.APPLE_FINDMY).copy(kind = "airtag"),
                rec("s2", t0 + minute, key = "k", type = TrackerType.APPLE_FINDMY),
            ),
        ).devices["k"]!!.kind
        assertEquals("airtag", kind)
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

        // Judged per identity: three separated identities in three scans are three passers-by, one identity in three scans is critical.
        val three = SightingAggregator.aggregate(
            (0 until 3).map { i -> rec("s$i", t0 + i * 35 * minute, key = "k$i", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED) },
        )
        assertTrue(three.devices.values.all { FollowingHeuristic.assess(it) == FollowingLevel.NONE })
        val one = SightingAggregator.aggregate(
            (0 until 3).map { i -> rec("s$i", t0 + i * 35 * minute, key = "k", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED) },
        ).devices["k"]!!
        assertEquals(3, one.sessionsSeparated)
        assertEquals(70L, one.spanSeparatedMinutes)
        assertEquals(FollowingLevel.CRITICAL, one.level)
    }

    @Test
    fun separatedCountsArePerIdentityAndComeFromStoredRows() {
        // Rows as the events table keeps them (summaries only): the separated span is read back from them.
        val rows = listOf(
            rec("s1", t0, key = "k", type = TrackerType.APPLE_FINDMY, state = TrackerState.WITH_OWNER),
            rec("s2", t0 + 20 * minute, key = "k", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED),
            rec("s3", t0 + 50 * minute, key = "k", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED),
        ).map { SightingRecord.parse(it.subject, it.encode())!! }
        val d = SightingAggregator.aggregate(rows).devices["k"]!!
        assertEquals(3, d.sessions)
        assertEquals(50L, d.spanMinutes)
        assertEquals(2, d.sessionsSeparated)
        assertEquals(30L, d.spanSeparatedMinutes)
        assertEquals(FollowingLevel.WARN, d.level)
        assertEquals(t0 + 20 * minute, d.firstSeparated)
        assertNull(SightingAggregator.aggregate(listOf(rec("s1", t0))).devices.values.single().firstSeparated)
    }

    @Test
    fun closenessOrdersByLevelThenProgress() {
        val c = FollowingHeuristic::closeness
        assertTrue(c(FollowingLevel.WARN, 3, 30) > c(FollowingLevel.NONE, 3, 29))
        assertTrue(c(FollowingLevel.CRITICAL, 3, 60) > c(FollowingLevel.WARN, 9, 900))
        assertTrue(c(FollowingLevel.NONE, 2, 25) > c(FollowingLevel.NONE, 2, 5))
        // Progress past the threshold does not count twice.
        assertEquals(c(FollowingLevel.NONE, 3, 30), c(FollowingLevel.NONE, 30, 300), 1e-9)
        assertTrue(!FollowingHeuristic.isCandidate(1))
        assertTrue(FollowingHeuristic.isCandidate(2))
    }

    @Test
    fun observationsCarryTheSchemaAndStayBounded() {
        val records = (0 until 50).map { i -> rec("s${i % 5}", t0 + i * minute, key = "%08x".format(i)) } +
            rec("s9", t0 + 59 * minute, key = "aaaaaaaa", type = TrackerType.APPLE_FINDMY, state = TrackerState.SEPARATED)
        val agg = SightingAggregator.aggregate(records)
        val obs = SurroundingsKeys.bleObservations(agg, devicesTotal = 123, available = SurroundingsKeys.AVAILABLE_YES, now = t0 + 60 * minute, muted = setOf("tracker:findmy"), sessions30d = 6, currentSession = "s9")
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
        // Family subjects summarise identities by state and name the closest one; they carry no separated counts any more.
        assertEquals("0", tileType[SurroundingsKeys.DEVICES_WITH_OWNER])
        assertEquals("0", tileType[SurroundingsKeys.DEVICES_SEPARATED])
        assertEquals("50", tileType[SurroundingsKeys.DEVICES_UNKNOWN])
        assertEquals("none", tileType[SurroundingsKeys.CLOSEST_KEY])
        assertNull(tileType[SurroundingsKeys.CLOSEST_SESSIONS])
        assertEquals("0", tileType[SurroundingsKeys.FOLLOWING_COUNT])
        assertNull(tileType[SurroundingsKeys.SEEN_SESSIONS_SEPARATED])
        val appleType = obs.filter { it.subject == "tracker:findmy" }.associate { it.key to it.value }
        assertEquals("true", appleType[SurroundingsKeys.MUTED])
        assertEquals("separated", appleType[SurroundingsKeys.STATE])
        // A muted type mutes its devices too.
        assertEquals("true", obs.first { it.subject == "tracker:findmy:aaaaaaaa" && it.key == SurroundingsKeys.MUTED }.value)
        // Per-device facts the detail shows: first/last seen, last signal, whether it was in this scan.
        val appleDevice = obs.filter { it.subject == "tracker:findmy:aaaaaaaa" }.associate { it.key to it.value }
        assertEquals((t0 + 59 * minute).toString(), appleDevice[SurroundingsKeys.SEEN_FIRST])
        assertEquals((t0 + 59 * minute).toString(), appleDevice[SurroundingsKeys.SEEN_LAST])
        assertEquals("-60", appleDevice[SurroundingsKeys.RSSI_LAST])
        assertEquals("true", appleDevice[SurroundingsKeys.SEEN_THIS_SCAN])
        assertEquals("1", appleDevice[SurroundingsKeys.SEEN_SESSIONS_SEPARATED])
        assertEquals("0", appleDevice[SurroundingsKeys.SEEN_SPAN_SEPARATED])
        assertEquals("false", obs.first { it.subject == "tracker:tile:00000031" && it.key == SurroundingsKeys.SEEN_THIS_SCAN }.value)
        assertNull(appleDevice[SurroundingsKeys.KIND])
        assertEquals("type=findmy;minutes=3;closest=-48", SurroundingsKeys.findItSummary(TrackerType.APPLE_FINDMY, 3, -48))
        assertEquals("type=tile;minutes=0", SurroundingsKeys.findItSummary(TrackerType.TILE, 0, null))
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
