package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPlanTest {
    @Test
    fun silenceTurnsOffWhatIsOnOrUnknownInOrderAndSkipsWhatIsOffOrAbsent() {
        val r = reading(
            states = mapOf(
                Emitter.CELLULAR to EmitterState.ON, Emitter.WIFI to EmitterState.ON, Emitter.HOTSPOT to EmitterState.ON,
                Emitter.BLE_SCANNING to EmitterState.UNKNOWN, Emitter.UWB to EmitterState.ABSENT,
            ),
            hotspotKinds = listOf("wifi"),
        )
        assertEquals(
            listOf(Emitter.HOTSPOT, Emitter.CELLULAR, Emitter.WIFI, Emitter.BLE_SCANNING),
            ShellPlan.silence(r).map { it.emitter },
        )
    }

    @Test
    fun usbOrBluetoothTetheringIsLeftToTheUser() {
        val r = reading(states = mapOf(Emitter.HOTSPOT to EmitterState.ON), hotspotKinds = listOf("usb"))
        assertNull(ShellPlan.silenceStep(Emitter.HOTSPOT, r))
        val report = SilenceReport.of(r.copy(shell = ShellAccess.READY))
        assertEquals(listOf(Emitter.HOTSPOT), report.byYou)
    }

    @Test
    fun restoreStartsWithAirplaneModeOffAndNeverTouchesTheHotspot() {
        val r = reading(airplane = true)
        val steps = ShellPlan.restore(setOf(Emitter.NFC, Emitter.WIFI, Emitter.CELLULAR, Emitter.HOTSPOT), r)
        assertEquals(listOf(Emitter.CELLULAR, Emitter.WIFI, Emitter.NFC), steps.map { it.emitter })
        assertEquals("cmd connectivity airplane-mode disable", steps.first().commands.single())
        assertNull(ShellPlan.on(Emitter.HOTSPOT))
        // Already on: nothing to do.
        assertTrue(ShellPlan.restore(setOf(Emitter.WIFI), reading(states = mapOf(Emitter.WIFI to EmitterState.ON))).isEmpty())
    }

    @Test
    fun everyCommandIsAFixedShellSafeStringAndUnconfirmed() {
        val unsafe = Regex("""[;&|$`<>"'\\(){}*?\n]""")
        ShellPlan.allCommands.forEach { assertFalse(it, unsafe.containsMatchIn(it)) }
        Emitter.entries.forEach { e ->
            assertTrue(ShellPlan.off(e).commands.isNotEmpty())
            // Not one of these has been run on RJ's phone yet: they stay flagged until a device check.
            assertFalse(ShellPlan.off(e).confirmed)
        }
    }

    @Test
    fun outputThatReadsAsAFailureIsCaught() {
        assertFalse(ShellOutput.failed(""))
        assertFalse(ShellOutput.failed("Wifi is disabled"))
        assertTrue(ShellOutput.failed("Unknown command: set-scan-always-available"))
        assertTrue(ShellOutput.failed("Can't find service: uwb"))
        assertTrue(ShellOutput.failed("usage: svc nfc [enable|disable]"))
        assertTrue(ShellOutput.failed("Security exception: ...\n[exit 255]"))
        assertEquals("Can't find service: uwb", ShellOutput.firstFailure("\nCan't find service: uwb\n"))
    }

    @Test
    fun theOutcomeIsJudgedByReadingAgain() {
        val before = reading(states = mapOf(Emitter.WIFI to EmitterState.ON, Emitter.NFC to EmitterState.ON))
        val after = reading(states = mapOf(Emitter.NFC to EmitterState.ON))
        val o = SilenceOutcome.silence(before, after, mapOf(Emitter.NFC to "Unknown command: nfc"))
        assertEquals(listOf(Emitter.WIFI), o.done)
        assertEquals("Partly done: Wi-Fi. Still on: NFC (Unknown command: nfc).", o.summary)
        val restored = SilenceOutcome.restore(setOf(Emitter.WIFI, Emitter.NFC), reading(states = mapOf(Emitter.WIFI to EmitterState.ON)), emptyMap())
        assertEquals("Partly done: Wi-Fi. Still off: NFC.", restored.summary)
        assertEquals("Done: Wi-Fi, NFC.", SilenceOutcome.silence(before, reading(), emptyMap()).summary)
    }
}
