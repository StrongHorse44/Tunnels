package io.github.stronghorse44.tunnels.homenet

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketException

/**
 * The 0.1.17 hotfix on the emulator. A VPN that Tunnels cannot bypass can't be set up here without the user's consent, so
 * the refusal itself is tested through its classification, and the route check through the emulator's own Wi-Fi: with no
 * VPN, the kernel's source address for the gateway and the SSDP group is the Wi-Fi's own, so the check would allow it.
 */
@RunWith(AndroidJUnit4::class)
class VpnFallthroughSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onlyAnEpermBindRefusalCountsAsTheVpns() {
        val eperm = SocketException("Binding socket to network 101").apply { initCause(ErrnoException("Binding socket to network 101", OsConstants.EPERM)) }
        assertTrue(VpnFallthroughCheck.isVpnRefusal(eperm))
        assertTrue(VpnFallthroughCheck.isVpnRefusal(ErrnoException("bind", OsConstants.EPERM).rethrowAsSocketException()))
        val gone = SocketException("Binding socket to network 101").apply { initCause(ErrnoException("Binding socket to network 101", OsConstants.ENONET)) }
        assertFalse("a network that is gone is not the VPN", VpnFallthroughCheck.isVpnRefusal(gone))
        assertFalse(VpnFallthroughCheck.isVpnRefusal(SocketException("closed")))
    }

    @Test
    fun theRouteCheckSeesTheWifiOnTheEmulator() {
        val state = NetworkGate(context).current()
        assumeTrue("the emulator has no Wi-Fi with a gateway", state.onWifi && state.gateway != null && state.prefixes.isNotEmpty())
        assertTrue("no VPN: the gateway and the SSDP group route over the Wi-Fi", VpnFallthroughCheck(context, state).allowed())
        assertFalse("no Wi-Fi state: refused", VpnFallthroughCheck(context, WifiState.OFFLINE).allowed())
    }
}
