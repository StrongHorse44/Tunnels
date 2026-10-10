package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShellReadsTest {
    @Test
    fun settingsFlags() {
        assertEquals(EmitterState.ON, ShellReads.flag("1\n"))
        assertEquals(EmitterState.OFF, ShellReads.flag("0"))
        assertEquals(EmitterState.UNKNOWN, ShellReads.flag("null"))
        assertEquals(EmitterState.UNKNOWN, ShellReads.flag(""))
    }

    @Test
    fun uwbStatusInTheShapesAospHasPrinted() {
        assertEquals(EmitterState.ON, ShellReads.uwb("Uwb state: enabled"))
        assertEquals(EmitterState.ON, ShellReads.uwb("state = STATE_ENABLED_ACTIVE"))
        assertEquals(EmitterState.OFF, ShellReads.uwb("Adapter state: STATE_DISABLED"))
        assertEquals(EmitterState.OFF, ShellReads.uwb("UWB is disabled"))
        assertEquals(EmitterState.ON, ShellReads.uwb("state: 2"))
        assertEquals(EmitterState.UNKNOWN, ShellReads.uwb("Can't find service: uwb"))
        assertEquals(EmitterState.UNKNOWN, ShellReads.uwb("something else entirely"))
        assertFalse(ShellReads.UWB_CONFIRMED)
    }

    @Test
    fun tetheringSectionGivesStateAndKinds() {
        val dump = """
            Tethering:
              Configuration:
                tetherableUsbRegexs: [rndis\d, ncm\d]
              Tether state:
                wlan1 - TetheredState - lastError = 0
                rndis0 - AvailableState - lastError = 0
                bt-pan - LocalHotspotState - lastError = 0
              Upstream wanted: true
        """.trimIndent()
        assertEquals(ShellReads.Tethering(EmitterState.ON, listOf("bluetooth", "wifi")), ShellReads.tethering(dump))

        val idle = "Tethering:\n  Tether state:\n    rndis0 - AvailableState - lastError = 0\n  Upstream wanted: false"
        assertEquals(ShellReads.Tethering(EmitterState.OFF, emptyList()), ShellReads.tethering(idle))
        assertEquals(EmitterState.UNKNOWN, ShellReads.tethering("Can't find service: tethering").state)
        assertEquals(EmitterState.UNKNOWN, ShellReads.tethering("Tethering:\n  nothing here").state)
        assertFalse(ShellReads.TETHERING_CONFIRMED)
    }

    @Test
    fun interfaceKinds() {
        assertEquals("wifi", ShellReads.kindOf("wlan1"))
        assertEquals("wifi", ShellReads.kindOf("ap0"))
        assertEquals("usb", ShellReads.kindOf("ncm0"))
        assertEquals("bluetooth", ShellReads.kindOf("bt-pan"))
        assertEquals("other", ShellReads.kindOf("eth0"))
    }
}
