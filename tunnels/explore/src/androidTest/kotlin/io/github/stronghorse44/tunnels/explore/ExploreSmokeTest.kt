package io.github.stronghorse44.tunnels.explore

import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.ScanProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the emulator's real sensors and cameras through the provider, as the engine would, and checks the schema. */
@RunWith(AndroidJUnit4::class)
class ExploreSmokeTest {
    private companion object {
        const val TAG = "ExploreSmokeTest"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerRegistersSensorsAndCameras() {
        val modules = ExploreTunnels().create(context).filter { it.id == Sensors.TUNNEL_ID || it.id == Cameras.TUNNEL_ID }
        assertEquals(listOf(Sensors.TUNNEL_ID, Cameras.TUNNEL_ID), modules.map { it.id })
        for (m in modules) {
            assertTrue(m.id, m.requiredPermissions.isEmpty())
            assertTrue(m.id, m.specialAccess.isEmpty())
            assertTrue(m.id, m.rules.isEmpty())
            assertEquals("Explore", m.info.line.label)
            assertTrue(m is io.github.stronghorse44.tunnels.runtime.TunnelUi)
        }
    }

    @Test
    fun sensorsScanMatchesSensorManager() = runBlocking {
        val module = ExploreTunnels().create(context).first { it.id == Sensors.TUNNEL_ID }
        val expected = context.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        obs.sortedWith(compareBy({ it.subject }, { it.key })).forEach { Log.i(TAG, "${it.subject} / ${it.key} = ${it.value}") }

        assertTrue("progress reported", reports >= 1)
        assertTrue(obs.all { it.tunnelId == Sensors.TUNNEL_ID })
        assertEquals("identities are unique", obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.none { it.value.isEmpty() })
        val summary = obs.filter { it.subject == Sensors.SUMMARY }.associate { it.key to it.value }
        assertEquals(expected.size.toString(), summary[Sensors.COUNT])
        assertEquals(expected.count { it.isWakeUpSensor }.toString(), summary[Sensors.WAKE_UP_COUNT])
        assertTrue(summary.keys.all { it in Sensors.summaryKeys })

        val sensors = obs.filter { it.subject != Sensors.SUMMARY }.groupBy { it.subject }
        assertEquals(minOf(expected.size, Sensors.MAX_SENSORS), sensors.size)
        for ((subject, facts) in sensors) {
            assertEquals(subject, Sensors.perSensor.toSet(), facts.map { it.key }.toSet())
            val byKey = facts.associate { it.key to it.value }
            assertTrue(subject, byKey[Sensors.WAKE_UP] in setOf("true", "false"))
            assertTrue(subject, byKey[Sensors.DYNAMIC] in setOf("true", "false"))
            assertTrue(subject, byKey[Sensors.CATEGORY] in SensorCategory.entries.map { it.name.lowercase() })
            assertNotNull(subject, byKey[Sensors.FIFO_MAX]!!.toInt())
            assertNotNull(subject, byKey[Sensors.MIN_DELAY]!!.toInt())
            assertTrue(subject, byKey[Sensors.POWER] != "?")
        }
        // Every stock emulator ships an accelerometer.
        if (expected.any { it.type == Sensor.TYPE_ACCELEROMETER }) {
            assertTrue(sensors.values.any { f -> f.any { it.key == Sensors.TYPE && it.value == "android.sensor.accelerometer" } })
        }
        // Describing hardware is deterministic.
        assertEquals(obs.toSet(), module.scan(ScanProgress.NONE).toSet())
    }

    @Test
    fun camerasScanMatchesCameraManager() = runBlocking {
        val module = ExploreTunnels().create(context).first { it.id == Cameras.TUNNEL_ID }
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager?.cameraIdList?.toList() }.getOrNull().orEmpty()
        val obs = module.scan(ScanProgress.NONE)
        obs.sortedWith(compareBy({ it.subject }, { it.key })).forEach { Log.i(TAG, "${it.subject} / ${it.key} = ${it.value}") }

        assertTrue(obs.all { it.tunnelId == Cameras.TUNNEL_ID })
        assertEquals("identities are unique", obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.none { it.value.isEmpty() })
        val summary = obs.filter { it.subject == Cameras.SUMMARY }.associate { it.key to it.value }
        assertTrue(summary.keys.all { it in Cameras.summaryKeys })
        val count = summary[Cameras.COUNT]!!.toInt()
        assertTrue(count >= 0)
        if (manager != null && ids.isNotEmpty()) assertEquals(ids.size, count)
        assertTrue(summary[Cameras.LOGICAL_COUNT]!!.toInt() in 0..count)

        val cameras = obs.filter { it.subject != Cameras.SUMMARY }.groupBy { it.subject }
        for (id in ids.take(Cameras.MAX_CAMERAS)) assertTrue(id, Cameras.subject(id) in cameras)
        for ((subject, facts) in cameras) {
            val byKey = facts.associate { it.key to it.value }
            assertTrue(subject, byKey.keys.all { it in Cameras.perCamera })
            if (Cameras.ERROR !in byKey) {
                assertTrue(subject, byKey[Cameras.FACING] in setOf("front", "back", "external") || byKey[Cameras.FACING]!!.startsWith("facing "))
                assertNotNull(subject, byKey[Cameras.HW_LEVEL])
                assertNotNull(subject, byKey[Cameras.CAPABILITIES])
                byKey[Cameras.MAX_RESOLUTION]?.let { assertTrue(it, it.contains("x") && it.endsWith(" MP")) }
            }
        }
        assertEquals(obs.toSet(), module.scan(ScanProgress.NONE).toSet())
    }
}
