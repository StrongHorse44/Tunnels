package io.github.stronghorse44.tunnels.surroundings

import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("1 scan · 0 trackers", SurroundingsFormat.monitorLine(1, 0, null))
        assertEquals("12 scans · 1 tracker · LTE", SurroundingsFormat.monitorLine(12, 1, "LTE"))
        assertEquals("away from owner", SurroundingsFormat.stateLabel(TrackerState.SEPARATED))
        assertEquals("Apple Find My · deadbeef", SurroundingsFormat.trackerTitle("tracker:findmy:deadbeef"))
        assertEquals("Tile", SurroundingsFormat.trackerTitle("tracker:tile"))
        assertEquals("ble:summary", SurroundingsFormat.trackerTitle("ble:summary"))
    }

    @Test
    fun trackerCardsGroupDevicesUnderTypes() {
        val obs = listOf(
            Observation(t, "tracker:tile", SurroundingsKeys.DEVICES, "2"),
            Observation(t, "tracker:tile", SurroundingsKeys.SEEN_SESSIONS, "4"),
            Observation(t, "tracker:tile:aaaaaaaa", SurroundingsKeys.SEEN_SESSIONS, "1"),
            Observation(t, "tracker:tile:bbbbbbbb", SurroundingsKeys.SEEN_SESSIONS, "3"),
            Observation(t, "tracker:findmy", SurroundingsKeys.DEVICES, "1"),
            Observation(t, "tracker:findmy:cccccccc", SurroundingsKeys.STATE, "separated"),
            Observation(t, "ble:summary", SurroundingsKeys.DEVICES_TOTAL, "9"),
            Observation(t, "Home", SurroundingsKeys.WIFI_SECURITY, "wpa2"),
        )
        val cards = SurroundingsFormat.trackerCards(obs)
        assertEquals(listOf(TrackerType.APPLE_FINDMY, TrackerType.TILE), cards.map { it.type })
        val tile = cards.single { it.type == TrackerType.TILE }
        assertEquals("tracker:tile", tile.subject)
        assertEquals("4", tile.facts[SurroundingsKeys.SEEN_SESSIONS])
        // Most-seen device first.
        assertEquals(listOf("bbbbbbbb", "aaaaaaaa"), tile.devices.map { it.key })
        assertEquals("separated", cards.single { it.type == TrackerType.APPLE_FINDMY }.devices.single().facts[SurroundingsKeys.STATE])
        assertTrue(SurroundingsFormat.trackerCards(emptyList()).isEmpty())
    }
}
