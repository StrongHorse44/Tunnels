package io.github.stronghorse44.tunnels.surroundings

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bluetooth devices over time on the emulator: keyed ids come from the Surroundings keystore key (stable, never the
 * address), hints are kept with the row, a row goes through the store and back, every Bluetooth finding kind gets Find
 * it, a mute and the Bluetooth settings, and find-it opens in device mode without an adapter.
 */
@RunWith(AndroidJUnit4::class)
class BtSightingsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keyedIdsAreStableHideTheAddressAndKeepHints() {
        val heard = listOf(
            HeardDevice("C4:11:22:33:44:55", "HD Mini Cam", emptySet(), -60),
            HeardDevice("c4:11:22:33:44:55", "HD Mini Cam", emptySet(), -70),
            HeardDevice("00:1A:7D:DA:71:13", "", setOf(0x02F2), -80),
        )
        val a = BtSightings.recordOf("s1", 1L, 3L, heard)!!
        val b = BtSightings.recordOf("s2", 2L, 3L, heard.reversed())!!
        assertEquals(2, a.devices.size)
        assertTrue(a.devices.all { Regex("[0-9a-f]{${WifiFollowing.KEY_LENGTH}}").matches(it.key) })
        assertEquals(a.devices.map { it.key }.toSet(), b.devices.map { it.key }.toSet())
        assertEquals("name-cam", a.devices.first { it.name == "HD Mini Cam" }.hint)
        assertEquals("maker-02f2", a.devices.first { it.name == "" }.hint)
        assertEquals(BtSightings.keyOf("C4:11:22:33:44:55"), a.devices.first { it.name == "HD Mini Cam" }.key)
        assertFalse(a.encode().contains("C4:11", ignoreCase = true))
        assertNull(BtSightings.recordOf("s3", 3L, null, emptyList()))
    }

    @Test
    fun aRowRoundTripsThroughTheStore() = runBlocking {
        val now = System.currentTimeMillis()
        val record = BtSightings.recordOf("smoke-$now", now, 9L, listOf(HeardDevice("F0:12:34:56:78:9A", "Smoke, test", emptySet(), -50)))!!
        BtSightings.record(context, record)
        assertTrue(BtSightings.history(context, now).any { it == record })
    }

    @Test
    fun everyBluetoothFindingHasFindItAMuteAndTheSettings() {
        val tunnel = SurroundingsTunnel(context)
        for (kind in SurroundingsRules.btDeviceKinds) {
            val actions = tunnel.actionsFor(FindingDraft(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.btDeviceSubject("0123456789abcdef"), kind, Severity.NOTICE, ""))
            assertTrue(kind, actions.any { it is FindingAction.Perform && it.label == TrackerActions.LABEL_FIND_IT })
            assertTrue(kind, actions.any { it is FindingAction.Perform && it.label == SurroundingsTunnel.LABEL_MUTE_WIFI })
            assertTrue(kind, actions.any { it is FindingAction.OpenSettings })
        }
        // The Wi-Fi hint gets the Wi-Fi actions.
        val hint = tunnel.actionsFor(FindingDraft(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.apSubject("0123456789abcdef"), SurroundingsRules.WIFI_AP_HINT, Severity.NOTICE, ""))
        assertTrue(hint.any { it is FindingAction.Perform && it.label == SurroundingsTunnel.LABEL_MUTE_WIFI })
    }

    @Test
    fun findItOpensInDeviceMode() {
        ActivityScenario.launch<FindItActivity>(FindItActivity.deviceIntent(context, "0123456789abcdef")).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(1_000)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val s = activity.currentState
                assertNull(s.type)
                assertEquals("0123456789abcdef", s.deviceKey)
                assertEquals("0123456789abcdef", s.lockedKey)
                assertEquals("Bluetooth device", s.label)
            }
            scenario.moveToState(Lifecycle.State.DESTROYED)
        }
    }
}
