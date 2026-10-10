package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmissionsObservationsTest {
    @Test
    fun aReadingSurvivesTheStoredForm() {
        val r = reading(
            states = mapOf(Emitter.WIFI to EmitterState.ON, Emitter.UWB to EmitterState.ABSENT, Emitter.HOTSPOT to EmitterState.ON),
            airplane = false,
            shell = ShellAccess.READY,
            unconfirmed = setOf(Emitter.HOTSPOT),
            hotspotKinds = listOf("wifi", "usb"),
            appUse = AppUseCounts(mapOf(RadioUse.Kind.BLUETOOTH to 3)),
        )
        val back = EmissionsObservations.from(EmissionsObservations.toObservations(r))
        assertEquals(r.emitters, back.emitters)
        assertEquals(false, back.airplane)
        assertEquals("present", back.sim)
        assertEquals(true, back.mobileData)
        assertEquals(true, back.location)
        assertEquals(listOf("usb", "wifi"), back.hotspotKinds)
        assertEquals(ShellAccess.READY, back.shell)
        assertEquals(3, back.appUse!!.counts[RadioUse.Kind.BLUETOOTH])
        assertEquals(0, back.appUse!!.counts[RadioUse.Kind.UWB])
    }

    @Test
    fun theSummaryCountsAndLevelAreStored() {
        val obs = EmissionsObservations.toObservations(
            reading(states = mapOf(Emitter.WIFI to EmitterState.ON, Emitter.NFC to EmitterState.UNKNOWN, Emitter.UWB to EmitterState.ABSENT)),
        )
        val summary = obs.filter { it.subject == EmissionsKeys.SUMMARY }.associate { it.key to it.value }
        assertEquals("1", summary[EmissionsKeys.ON_COUNT])
        assertEquals("5", summary[EmissionsKeys.OFF_COUNT])
        assertEquals("1", summary[EmissionsKeys.UNKNOWN_COUNT])
        assertEquals("1", summary[EmissionsKeys.ABSENT_COUNT])
        assertEquals(QuietLevel.PARTLY.id, summary[EmissionsKeys.LEVEL])
        assertTrue(obs.all { it.tunnelId == EmissionsKeys.TUNNEL_ID })
    }

    @Test
    fun appUseIsStoredAsCountsOnlyAndSaysWhenItWasNotRead() {
        val noShell = EmissionsObservations.toObservations(reading())
        assertEquals(
            listOf(EmissionsKeys.APPS_READ to EmissionsKeys.READ_NO_SHELL),
            noShell.filter { it.subject == EmissionsKeys.APPS }.map { it.key to it.value },
        )
        assertNull(EmissionsObservations.from(noShell).appUse)
        val read = EmissionsObservations.toObservations(reading(appUse = AppUseCounts(mapOf(RadioUse.Kind.WIFI to 2))))
        val apps = read.filter { it.subject == EmissionsKeys.APPS }
        // Rule 3: numbers and the read status only, never an app.
        assertTrue(apps.all { it.key == EmissionsKeys.APPS_READ || it.value.all(Char::isDigit) })
    }

    @Test
    fun missingObservationsReadAsUnknown() {
        val r = EmissionsObservations.from(emptyList())
        assertTrue(Emitter.entries.all { r.state(it) == EmitterState.UNKNOWN })
        assertNull(r.airplane)
        assertEquals(QuietLevel.QUIET_UNREAD, r.level)
    }

    @Test
    fun theRestoreSetKeepsOnlyRestorableTransmittersInCardOrder() {
        assertEquals("wifi,bluetooth", RestoreSet.encode(listOf(Emitter.BLUETOOTH, Emitter.HOTSPOT, Emitter.WIFI)))
        assertNull(RestoreSet.encode(listOf(Emitter.HOTSPOT)))
        assertEquals(setOf(Emitter.WIFI, Emitter.NFC), RestoreSet.decode("nfc, wifi,bogus,hotspot"))
        assertEquals(emptySet<Emitter>(), RestoreSet.decode(null))
        // A second Silence must not forget what the first one turned off.
        assertEquals("cellular,wifi,nfc", RestoreSet.merge("wifi,nfc", listOf(Emitter.CELLULAR)))
    }
}
