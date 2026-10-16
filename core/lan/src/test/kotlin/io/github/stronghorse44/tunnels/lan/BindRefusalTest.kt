package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/** A bind refused by a VPN Tunnels may not bypass is told apart from a network that went away (0.1.17, OQ197). */
class BindRefusalTest {
    private val eperm = 1
    private val enonet = 64

    /** Stands in for Android's ErrnoException. */
    private class Errno(val errno: Int, message: String) : Exception(message)

    private val errnoOf: (Throwable) -> Int? = { (it as? Errno)?.errno }

    private fun refused(t: Throwable) = BindRefusal.isVpnRefusal(t, errnoOf, eperm)

    @Test
    fun epermInTheCauseChainIsTheVpns() {
        assertTrue(refused(Errno(eperm, "Binding socket to network 101")))
        assertTrue(refused(SocketException("Binding socket to network 101").apply { initCause(Errno(eperm, "Binding socket to network 101")) }))
        assertTrue("deeper", refused(IOException("x", SocketException("y").apply { initCause(Errno(eperm, "z")) })))
    }

    @Test
    fun theMessageFormAndroidThrowsCountsToo() {
        assertTrue(refused(SocketException("bind failed: EPERM (Operation not permitted)")))
    }

    @Test
    fun anyOtherFailureIsNotTheVpns() {
        assertFalse("the network is gone", refused(SocketException("Binding socket to network 101").apply { initCause(Errno(enonet, "ENONET")) }))
        assertFalse(refused(SocketException("Socket closed")))
        assertFalse(refused(IllegalStateException()))
    }

    @Test
    fun aCauseLoopEnds() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(refused(a))
    }

    @Test
    fun theTextsAreRjs() {
        assertEquals(
            "A VPN that doesn't let Tunnels bypass it is on, so Tunnels can't reach your Wi-Fi and sends nothing. " +
                "Exclude Tunnels in that VPN app's settings, or disconnect it, then try again.",
            GateTexts.refusal(LanKeys.REASON_VPN_BLOCKS_WIFI),
        )
        assertEquals(GateTexts.VPN_BLOCKS_WIFI, GateTexts.gateReason("vpn-blocks-wifi"))
        assertEquals(
            "A VPN that doesn't let Tunnels bypass it is on, so Tunnels sends nothing to your router: exclude Tunnels in that VPN app, or disconnect it.",
            GateTexts.closeRefusal("vpn-blocks-wifi"),
        )
    }
}
