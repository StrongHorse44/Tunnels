package io.github.stronghorse44.tunnels.homenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.HostKind
import io.github.stronghorse44.tunnels.lan.LookCloser
import io.github.stronghorse44.tunnels.lan.OsHint
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Look closer" on the emulator: it never asks a device on a network the gate has not confirmed (an emulator's network
 * never is), and nothing is kept then; a kept summary goes through the encrypted store's events and back.
 */
@RunWith(AndroidJUnit4::class)
class LookCloserSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun anUnconfirmedNetworkIsNeverAsked() = runBlocking {
        assertTrue(NetworkGate(context).forgetAll())
        val store = TunnelsStore.get(context)
        val before = store.events(LookCloser.LEDGER, 500).first().size
        val steps = ArrayList<String>()
        val outcome = HomeNetworkTunnel(context).closer.look("10.0.2.2", listOf("x")) { steps += it }
        assertNull(outcome.findings)
        assertNotNull(outcome.error)
        assertTrue(steps.isEmpty())
        assertEquals(before, store.events(LookCloser.LEDGER, 500).first().size)
    }

    @Test
    fun aKeptSummaryRoundTripsThroughTheStore() = runBlocking {
        val now = System.currentTimeMillis()
        val kept = LookCloser.Summary(now, HostKind.CAMERA, "Hikvision", "Web Viewer", listOf(80, 554), listOf("garage-cam"), OsHint.UNIX_LIKE)
        val store = TunnelsStore.get(context)
        store.recordEvent(LookCloser.LEDGER, LookCloser.EVENT, LookCloser.subject("smoke", "192.168.1.250"), kept.encode())
        val row = store.events(LookCloser.LEDGER, 500).first().first { it.subject == LookCloser.subject("smoke", "192.168.1.250") }
        assertEquals(kept.copy(at = kept.at), LookCloser.Summary.parse(row.summary))
        assertEquals("192.168.1.250", LookCloser.ipOf(row.subject, "smoke"))
    }
}
