package io.github.stronghorse44.tunnels.explore

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Satellites and radio on the emulator: no GNSS status ever arrives and there is no Bluetooth adapter,
 * so both scans must come back with availability facts instead of throwing.
 */
@RunWith(AndroidJUnit4::class)
class ExplorePhase4SmokeTest {
    private companion object {
        const val TAG = "ExplorePhase4SmokeTest"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerRegistersSatellitesAndRadio() {
        val modules = ExploreTunnels().create(context)
        assertEquals(listOf(Sensors.TUNNEL_ID, Cameras.TUNNEL_ID, Satellites.TUNNEL_ID, Radio.TUNNEL_ID), modules.map { it.id })
        val satellites = modules.first { it.id == Satellites.TUNNEL_ID }
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            satellites.requiredPermissions.map { it.permission }.toSet(),
        )
        val radio = modules.first { it.id == Radio.TUNNEL_ID }
        assertEquals(emptyList<String>(), radio.requiredPermissions.map { it.permission })
        for (m in listOf(satellites, radio)) {
            assertTrue(m.id, m.rules.isEmpty())
            assertTrue(m.id, m.specialAccess.isEmpty())
            assertEquals("Explore", m.info.line.label)
            assertTrue(m is TunnelUi)
            assertTrue(m.requiredPermissions.all { it.reason.isNotBlank() })
        }
    }

    @Test
    fun satellitesScanWithoutGnssReportsAvailability() = runBlocking {
        val module = ExploreTunnels().create(context).first { it.id == Satellites.TUNNEL_ID }
        val started = System.currentTimeMillis()
        val obs = module.scan(ScanProgress.NONE)
        val took = System.currentTimeMillis() - started
        obs.forEach { Log.i(TAG, "${it.subject} / ${it.key} = ${it.value}") }
        assertTrue("finished within a minute (${took}ms)", took < 60_000)
        assertTrue(obs.all { it.tunnelId == Satellites.TUNNEL_ID })
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.none { it.value.isEmpty() })
        val summary = obs.filter { it.subject == Satellites.SUMMARY }.associate { it.key to it.value }
        assertTrue(summary.keys.all { it in Satellites.summaryKeys })
        assertNotNull(summary[Satellites.AVAILABLE])
        assertTrue(summary[Satellites.SATS_VISIBLE]!!.toInt() >= 0)
        assertTrue(summary[Satellites.SATS_USED]!!.toInt() <= summary[Satellites.SATS_VISIBLE]!!.toInt())
        for ((subject, facts) in obs.filter { it.subject != Satellites.SUMMARY }.groupBy { it.subject }) {
            assertTrue(subject, subject in Satellites.constellationNames || subject == "Unknown")
            assertEquals(subject, Satellites.perConstellation.toSet(), facts.map { it.key }.toSet())
        }
        // A position must never be among the values: nothing looks like a coordinate pair.
        assertFalse(obs.any { Regex("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}").containsMatchIn(it.value) })
    }

    @Test
    fun radioScanWithoutBluetoothOrPhonePermission() = runBlocking {
        val module = ExploreTunnels().create(context).first { it.id == Radio.TUNNEL_ID }
        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        obs.sortedWith(compareBy({ it.subject }, { it.key })).forEach { Log.i(TAG, "${it.subject} / ${it.key} = ${it.value}") }
        assertTrue(reports >= 2)
        assertTrue(obs.all { it.tunnelId == Radio.TUNNEL_ID })
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.none { it.value.isEmpty() })
        assertTrue(obs.all { it.subject in Radio.subjects })
        val errors = obs.filter { it.key == Radio.ERROR }
        assertTrue("no subsystem failed: $errors", errors.isEmpty())
        for (subject in Radio.subjects) {
            val present = obs.firstOrNull { it.subject == subject && it.key == Radio.PRESENT }?.value
            assertTrue(subject, present in setOf("true", "false"))
        }
        val cellular = obs.filter { it.subject == Radio.CELLULAR }.associate { it.key to it.value }
        if (cellular[Radio.PRESENT] == "true") {
            assertNotNull(cellular[Radio.PHONE_TYPE])
            // READ_BASIC_PHONE_STATE is a normal permission, so the data network type is readable; either way it is present.
            assertNotNull(cellular[Radio.DATA_NETWORK])
        }
        val wifi = obs.filter { it.subject == Radio.WIFI }.associate { it.key to it.value }
        if (wifi[Radio.PRESENT] == "true") assertTrue(wifi[Radio.WIFI_CONNECTED] in setOf("true", "false"))
        // Describing hardware is deterministic enough to repeat.
        val again = module.scan(ScanProgress.NONE)
        assertEquals(obs.map { it.subject to it.key }.toSet(), again.map { it.subject to it.key }.toSet())
    }
}
