package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceAndBagTest {
    @Test
    fun quietLevels() {
        assertEquals(QuietLevel.SILENT, reading().level)
        assertEquals(QuietLevel.SILENT, reading(states = mapOf(Emitter.UWB to EmitterState.ABSENT)).level)
        assertEquals(QuietLevel.QUIET_UNREAD, reading(states = mapOf(Emitter.UWB to EmitterState.UNKNOWN)).level)
        assertEquals(QuietLevel.LOUD, reading(default = EmitterState.ON).level)
        assertEquals(QuietLevel.PARTLY, reading(states = mapOf(Emitter.WIFI to EmitterState.ON)).level)
    }

    @Test
    fun theReportSaysWhoCanTurnWhatOff() {
        val r = reading(states = mapOf(Emitter.WIFI to EmitterState.ON, Emitter.NFC to EmitterState.ON, Emitter.UWB to EmitterState.ABSENT))
        val manual = SilenceReport.of(r)
        assertEquals("2 of 7 transmitters on.", manual.headline)
        assertTrue(manual.byTunnels.isEmpty())
        assertTrue(manual.howFar, manual.howFar.startsWith("You can turn off all 2 from Android's own panels"))
        val withShell = SilenceReport.of(r.copy(shell = ShellAccess.READY))
        assertEquals("Tunnels can turn off all 2 with one tap.", withShell.howFar)
        assertEquals("All 7 transmitters Android reports are off.", SilenceReport.of(reading(states = mapOf(Emitter.UWB to EmitterState.ABSENT))).headline)
    }

    @Test
    fun theFullSilenceGuideCoversPowerOffAndTheBag() {
        assertTrue(FullSilence.steps.any { it.title.startsWith("Power off") })
        assertTrue(FullSilence.steps.any { it.title.contains("Faraday") })
        assertTrue(FullSilence.limits.isNotEmpty())
        Emitter.entries.forEach { assertTrue(EmitterSettings.routeFor(it).path.isNotBlank()) }
    }

    @Test
    fun aBagThatBlocksEverything() {
        val outside = BagTest.Sample(cellInService = true, cellLevel = 3, wifiNetworks = 12, wifiLinked = true, bleDevices = 20)
        val inside = BagTest.Sample(cellInService = false, cellLevel = 0, wifiNetworks = 0, wifiLinked = false, bleDevices = 0)
        val result = BagTest.judge(outside, inside)
        assertEquals(BagTest.Verdict.BLOCKED, result.overall)
        assertEquals("The bag blocked everything Tunnels could test.", result.headline)
    }

    @Test
    fun aBagThatLeaksBluetoothAndKeepsCellRegistered() {
        val outside = BagTest.Sample(cellInService = true, cellLevel = 4, wifiNetworks = null, wifiLinked = null, bleDevices = 9)
        val inside = BagTest.Sample(cellInService = true, cellLevel = 1, wifiNetworks = null, wifiLinked = null, bleDevices = 1)
        val result = BagTest.judge(outside, inside)
        assertEquals(BagTest.Verdict.LEAKS, result.overall)
        assertEquals("The bag leaks: Cellular, Bluetooth still got through.", result.headline)
        assertEquals(BagTest.Verdict.UNTESTED, result.channels.first { it.channel == BagTest.Channel.WIFI }.verdict)
    }

    @Test
    fun aModemThatStillSaysInServiceWithNoSignalCountsAsBlocked() {
        val outside = BagTest.Sample(cellInService = true, cellLevel = 2)
        val inside = BagTest.Sample(cellInService = true, cellLevel = 0)
        assertEquals(BagTest.Verdict.BLOCKED, BagTest.judge(outside, inside).channels.first().verdict)
    }

    @Test
    fun wifiNeedsEvidenceToCountAsBlocked() {
        // Seen outside by a scan only; inside no fresh scan and the link was never up: not tested.
        val o = BagTest.Sample(wifiNetworks = 5, wifiLinked = false)
        val i = BagTest.Sample(wifiNetworks = null, wifiLinked = false)
        assertEquals(BagTest.Verdict.UNTESTED, BagTest.judge(o, i).channels.first { it.channel == BagTest.Channel.WIFI }.verdict)
        // The link dropped: blocked even without a scan.
        val linked = BagTest.judge(BagTest.Sample(wifiLinked = true), BagTest.Sample(wifiLinked = false))
        assertEquals(BagTest.Verdict.BLOCKED, linked.channels.first { it.channel == BagTest.Channel.WIFI }.verdict)
    }

    @Test
    fun nothingOutsideMeansNothingToTest() {
        val result = BagTest.judge(BagTest.Sample(), BagTest.Sample())
        assertEquals(BagTest.Verdict.UNTESTED, result.overall)
        assertTrue(result.channels.all { it.note != null })
    }

    @Test
    fun preflightNamesWhatCannotBeTested() {
        val r = reading(states = mapOf(Emitter.WIFI to EmitterState.ON), airplane = true, location = false)
        val notes = BagTest.preflight(r)
        assertTrue(notes.any { it.startsWith("Airplane mode is on") })
        assertTrue(notes.any { it.startsWith("Bluetooth is off") })
        assertTrue(notes.any { it.startsWith("Location is off") })
    }

    @Test
    fun theTileLightsWhenQuiet() {
        assertEquals(TileText.Look(true, "Silent"), TileText.of(reading()))
        assertEquals(TileText.Look(false, "2 on"), TileText.of(reading(states = mapOf(Emitter.WIFI to EmitterState.ON, Emitter.NFC to EmitterState.ON))))
    }
}
