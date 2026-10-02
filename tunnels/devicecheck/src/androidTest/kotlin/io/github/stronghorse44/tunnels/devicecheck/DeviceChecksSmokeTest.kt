package io.github.stronghorse44.tunnels.devicecheck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the emulator the way the screen does: every probe answers, and the readings make sense for an emulator. */
@RunWith(AndroidJUnit4::class)
class DeviceChecksSmokeTest {
    @Test
    fun everyProbeAnswers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val groups = DeviceCheckRunner.run(context)
        val byId = groups.flatMap { it.results }.associateBy { it.id }
        assertEquals(CheckStatus.PASS, byId.getValue("package_visibility").status)
        assertTrue(byId.getValue("store_key").status == CheckStatus.PASS || byId.getValue("store_key").status == CheckStatus.WARN)
        // An emulator is not GrapheneOS: no Sensors permission, and the Network toggle check says so.
        assertEquals(CheckStatus.NOTE, byId.getValue("toggle_sensors").status)
        assertEquals(CheckStatus.NOTE, byId.getValue("toggle_network").status)
        assertEquals(CheckStatus.TODO, byId.getValue("verified_boot").status) // nothing scanned in this test app
        assertTrue(byId.containsKey("private_dns") && byId.containsKey("vpn"))
        assertTrue(groups.any { it.title == "By hand" })
        assertTrue(ChecksSummary.line(groups).isNotBlank())
    }
}
