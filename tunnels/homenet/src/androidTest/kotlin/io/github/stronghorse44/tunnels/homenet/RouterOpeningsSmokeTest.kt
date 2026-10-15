package io.github.stronghorse44.tunnels.homenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Close it on the emulator: the first tap only asks for a second, and on a network the gate has not confirmed (an
 * emulator's never is) the router is never asked.
 */
@RunWith(AndroidJUnit4::class)
class RouterOpeningsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun closeAsksTwiceAndNeverSendsOnAnUnconfirmedNetwork() = runBlocking {
        assertTrue(NetworkGate(context).forgetAll())
        val openings = HomeNetworkTunnel(context).openings
        val subject = "192.168.1.250 · TCP 8554 to the internet"
        assertEquals(
            "Tap Close it again within a minute to ask your router to stop forwarding TCP 8554 from the internet to 192.168.1.250.",
            openings.closeFromFinding(subject),
        )
        assertEquals("This Wi-Fi is not confirmed as yours, so Tunnels sends nothing.", openings.closeFromFinding(subject))
        assertTrue(openings.closeFromFinding("not an opening").startsWith("The last scan did not list"))
    }
}
