package io.github.stronghorse44.tunnels.surroundings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.ble.ManyNames
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.ble.WifiNetwork
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wi-Fi access points over time on the emulator: keyed ids come from the Surroundings keystore key (stable, never the
 * address), a scan row goes through the store and back, the threshold setting is clamped, and every Wi-Fi finding kind
 * gets a mute and the Wi-Fi settings.
 */
@RunWith(AndroidJUnit4::class)
class WifiSightingsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun net(ssid: String, bssid: String, open: Boolean = false) =
        WifiNetwork(ssid, bssid, if (open) "[ESS]" else "[WPA2-PSK-CCMP][ESS]", 2437)

    @Test
    fun keyedIdsAreStableAndHideTheAddress() {
        val fresh = listOf(net("Home", "00:11:22:33:44:55"), net("Home", "00:11:22:33:44:55"), net("iPhone", "aa:bb:cc:dd:ee:ff"))
        val a = WifiSightings.recordOf("s1", 1L, 3L, fresh)!!
        val b = WifiSightings.recordOf("s2", 2L, 3L, fresh.reversed())!!
        assertEquals(2, a.aps.size)
        assertTrue(a.aps.all { Regex("[0-9a-f]{${WifiFollowing.KEY_LENGTH}}").matches(it.key) })
        assertEquals(a.aps.associate { it.name to it.key }, b.aps.associate { it.name to it.key })
        assertTrue(a.encode().none { it == ':' })
        assertNull(WifiSightings.recordOf("s3", 3L, null, emptyList()))
    }

    @Test
    fun aDeviceWithManyOpenNamesIsClustered() {
        val fresh = (0 until 6).map { net("free$it", "02:aa:bb:cc:dd:%02x".format(it), open = true) }
        val c = WifiSightings.clustersOf(fresh).single()
        assertEquals(6, c.names.size)
        assertEquals(ManyNames.Level.WARN, ManyNames.level(c))
        assertNotEquals("00:aa:bb:cc:dd", c.key)
    }

    @Test
    fun aScanRowRoundTripsThroughTheStoreAndTheThresholdIsClamped() = runBlocking {
        val now = System.currentTimeMillis()
        val record = WifiSightings.recordOf("smoke-$now", now, 9L, listOf(net("Smoke, test", "12:34:56:78:9a:bc")))!!
        WifiSightings.record(context, record)
        assertTrue(WifiSightings.history(context, now).any { it == record })

        WifiSightings.setThreshold(context, 500)
        assertEquals(WifiFollowing.MAX_THRESHOLD, WifiSightings.threshold(context))
        WifiSightings.setThreshold(context, WifiFollowing.DEFAULT_THRESHOLD)
        assertEquals(WifiFollowing.DEFAULT_THRESHOLD, WifiSightings.threshold(context))
    }

    @Test
    fun everyWifiFindingHasAMuteAndTheWifiSettings() {
        val tunnel = SurroundingsTunnel(context)
        for (kind in SurroundingsRules.wifiDeviceKinds) {
            val subject = if (kind == SurroundingsRules.WIFI_MANY_NAMES) SurroundingsKeys.deviceSubject("0123456789abcdef") else SurroundingsKeys.apSubject("0123456789abcdef")
            val actions = tunnel.actionsFor(FindingDraft(SurroundingsKeys.TUNNEL_ID, subject, kind, Severity.NOTICE, ""))
            assertTrue(kind, actions.any { it is FindingAction.Perform && it.label == SurroundingsTunnel.LABEL_MUTE_WIFI })
            assertTrue(kind, actions.any { it is FindingAction.OpenSettings })
        }
    }
}
