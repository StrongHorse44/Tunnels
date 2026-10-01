package io.github.stronghorse44.tunnels.surroundings

import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SurroundingsFormatTest {
    private val t = SurroundingsKeys.TUNNEL_ID

    @Test
    fun elapsed() {
        assertEquals("0s", SurroundingsFormat.elapsed(0))
        assertEquals("45s", SurroundingsFormat.elapsed(45_000))
        assertEquals("1m 30s", SurroundingsFormat.elapsed(90_000))
        assertEquals("1h 0m", SurroundingsFormat.elapsed(3_600_000))
        assertEquals("2h 5m", SurroundingsFormat.elapsed(2 * 3_600_000L + 5 * 60_000))
        assertEquals("0s", SurroundingsFormat.elapsed(-5))
    }

    @Test
    fun monitorLineAndLabels() {
        assertEquals("1 scan · no trackers yet", SurroundingsFormat.monitorLine(1, emptyMap(), null))
        assertEquals(
            "12 scans · 3 identities since start: 1 near its owner, 2 separated · LTE",
            SurroundingsFormat.monitorLine(12, mapOf(TrackerState.WITH_OWNER to 1, TrackerState.SEPARATED to 2), "LTE"),
        )
        assertEquals("away from owner", SurroundingsFormat.stateLabel(TrackerState.SEPARATED))
        assertEquals("near (-55 dBm)", SurroundingsFormat.signal(-55))
        assertEquals("—", SurroundingsFormat.signal(null))
        assertEquals("Apple Find My · deadbeef", SurroundingsFormat.trackerTitle("tracker:findmy:deadbeef"))
        assertEquals("Tile", SurroundingsFormat.trackerTitle("tracker:tile"))
        assertEquals("ble:summary", SurroundingsFormat.trackerTitle("ble:summary"))
    }

    @Test
    fun timeOfIsRelativeToToday() {
        val zone = ZoneOffset.UTC
        val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        assertEquals("today 22:13", SurroundingsFormat.timeOf(now, now, zone))
        assertEquals("today 00:05", SurroundingsFormat.timeOf(now - 22 * 3_600_000L - 8 * 60_000L, now, zone))
        assertEquals("yesterday 23:13", SurroundingsFormat.timeOf(now - 23 * 3_600_000L, now, zone))
        assertEquals("12 Nov 22:13", SurroundingsFormat.timeOf(now - 48 * 3_600_000L, now, zone))
    }

    @Test
    fun trackerCardsGroupDevicesUnderTypes() {
        val obs = listOf(
            Observation(t, "tracker:tile", SurroundingsKeys.DEVICES, "2"),
            Observation(t, "tracker:tile", SurroundingsKeys.SEEN_SESSIONS, "4"),
            Observation(t, "tracker:tile", SurroundingsKeys.SEEN_SPAN, "95"),
            Observation(t, "tracker:tile:aaaaaaaa", SurroundingsKeys.SEEN_SESSIONS, "1"),
            Observation(t, "tracker:tile:bbbbbbbb", SurroundingsKeys.SEEN_SESSIONS, "3"),
            Observation(t, "tracker:findmy", SurroundingsKeys.DEVICES, "1"),
            Observation(t, "tracker:findmy", SurroundingsKeys.SEEN_SESSIONS, "1"),
            Observation(t, "tracker:findmy", SurroundingsKeys.SEEN_SPAN, "0"),
            Observation(t, "tracker:findmy", SurroundingsKeys.STATE, "with-owner"),
            Observation(t, "tracker:findmy:cccccccc", SurroundingsKeys.STATE, "with-owner"),
            Observation(t, "tracker:findmy:cccccccc", SurroundingsKeys.RSSI_LAST, "-52"),
            Observation(t, "tracker:findmy:cccccccc", SurroundingsKeys.SEEN_THIS_SCAN, "true"),
            Observation(t, "ble:summary", SurroundingsKeys.DEVICES_TOTAL, "9"),
            Observation(t, "Home", SurroundingsKeys.WIFI_SECURITY, "wpa2"),
        )
        val cards = SurroundingsFormat.trackerCards(obs)
        assertEquals(listOf(TrackerType.APPLE_FINDMY, TrackerType.TILE), cards.map { it.type })
        val tile = cards.single { it.type == TrackerType.TILE }
        assertEquals("tracker:tile", tile.subject)
        assertEquals(4, tile.sessions)
        assertEquals(95L, tile.spanMinutes)
        assertEquals(2, tile.devicesCount)
        // Most-seen device first.
        assertEquals(listOf("bbbbbbbb", "aaaaaaaa"), tile.devices.map { it.key })
        // The card reads the same thresholds as the rule: 4 scans over 95 minutes is WARN.
        assertEquals(FollowingLevel.WARN, tile.level)
        assertTrue(tile.progress.reached)
        val apple = cards.single { it.type == TrackerType.APPLE_FINDMY }
        assertEquals(TrackerState.WITH_OWNER, apple.state)
        assertEquals(FollowingLevel.NONE, apple.level)
        assertFalse(apple.progress.reached)
        assertEquals("1 of 3 scans · 0 of 30 min", apple.progress.label)
        val identity = apple.identities.single()
        assertEquals("cccccccc", identity.key)
        assertEquals(TrackerState.WITH_OWNER, identity.state)
        assertEquals(-52, identity.rssiLast)
        assertTrue(identity.seenThisScan)
        assertTrue(SurroundingsFormat.trackerCards(emptyList()).isEmpty())
    }
}
