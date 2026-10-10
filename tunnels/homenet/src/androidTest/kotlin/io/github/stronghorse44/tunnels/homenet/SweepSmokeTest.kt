package io.github.stronghorse44.tunnels.homenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanSweep
import io.github.stronghorse44.tunnels.model.ScanProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * The opt-in address sweep on the emulator: the switch is off until set and round-trips through the encrypted store,
 * the sweep never reaches past the gate (an emulator's network is never confirmed, so nothing is swept), and the targets
 * of a private /24 stay inside it.
 */
@RunWith(AndroidJUnit4::class)
class SweepSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun off() = runBlocking { SweepSetting.set(context, false) }

    @Test
    fun theSwitchIsOffUntilSetAndRoundTrips() = runBlocking {
        SweepSetting.set(context, false)
        assertFalse(SweepSetting.isOn(context))
        SweepSetting.set(context, true)
        assertTrue(SweepSetting.isOn(context))
    }

    @Test
    fun anUnconfirmedNetworkIsNeverSwept() = runBlocking {
        SweepSetting.set(context, true)
        assertTrue(NetworkGate(context).forgetAll())
        val obs = HomeNetworkTunnel(context).scan(ScanProgress.NONE)
        val summary = obs.filter { it.subject == LanKeys.SUBJECT_SUMMARY }.associate { it.key to it.value }
        assertEquals(LanKeys.GATE_UNCONFIRMED, summary[LanKeys.SCAN_GATE])
        assertTrue(LanKeys.SCAN_SWEEP !in summary)
        assertTrue(obs.none { it.key == LanKeys.HOST_FOUND_BY })
    }

    @Test
    fun targetsStayInsideThePrivateNetwork() {
        val targets = LanSweep.targets(listOf(InetAddress.getByName("192.168.50.7") to 24))
        assertEquals(253, targets.size)
        assertTrue(targets.all { it.startsWith("192.168.50.") })
    }
}
