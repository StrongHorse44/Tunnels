package io.github.stronghorse44.tunnels.explore

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamerasTest {
    private fun value(obs: List<Observation>, subject: String, key: String) = obs.firstOrNull { it.subject == subject && it.key == key }?.value

    private val back = CameraFacts(
        id = "0", facing = 1, hwLevel = 3, maxJpeg = 4080 to 3072, focalLengthsMm = listOf(6.81f), apertures = listOf(1.68f),
        sensorSizeMm = 9.8f to 7.3f, flash = true, ois = true, capabilities = listOf(0, 1, 3, 11, 18, 9), physicalIds = listOf("2", "3"),
        maxFps = 60, maxHighSpeedFps = 240, zoomRange = 0.5f to 30f, orientationDeg = 90,
    )
    private val front = CameraFacts(
        id = "1", facing = 0, hwLevel = 1, maxJpeg = 3264 to 2448, focalLengthsMm = listOf(2.74f), apertures = listOf(2.2f),
        flash = false, ois = false, capabilities = listOf(0), maxFps = 30, zoomRange = 1f to 4f,
    )
    private val lens = CameraFacts(id = "2", physicalOf = "0", facing = 1, hwLevel = 0, maxJpeg = 4000 to 3000, capabilities = listOf(0))

    @Test
    fun codesAreNamed() {
        assertEquals("front", Cameras.facingName(0))
        assertEquals("back", Cameras.facingName(1))
        assertEquals("external", Cameras.facingName(2))
        assertEquals("unknown", Cameras.facingName(null))
        assertEquals("facing 7", Cameras.facingName(7))
        assertEquals("limited", Cameras.hwLevelName(0))
        assertEquals("full", Cameras.hwLevelName(1))
        assertEquals("legacy", Cameras.hwLevelName(2))
        assertEquals("level3", Cameras.hwLevelName(3))
        assertEquals("external", Cameras.hwLevelName(4))
        assertEquals("raw", Cameras.capabilityName(3))
        assertEquals("logical multi-camera", Cameras.capabilityName(11))
        assertEquals("10-bit", Cameras.capabilityName(18))
        assertEquals("capability 99", Cameras.capabilityName(99))
    }

    @Test
    fun formatting() {
        assertEquals("12 MP", ExploreFormat.megapixels(4080, 3072))
        assertEquals("8.0 MP", ExploreFormat.megapixels(3264, 2448))
        assertEquals("0.9 MP", ExploreFormat.megapixels(1280, 720))
        assertEquals("4080x3072 · 12 MP", Cameras.resolutionText(4080 to 3072))
        assertEquals("f/1.68, f/2.4", Cameras.aperturesText(listOf(1.68f, 2.4f)))
        assertEquals("none", Cameras.aperturesText(emptyList()))
        assertEquals("0.5–30", Cameras.rangeText(0.5f to 30f))
        assertEquals("0.15", ExploreFormat.num(0.15f))
        assertEquals("39.2", ExploreFormat.num(39.2f))
        assertEquals("65536", ExploreFormat.num(65536f))
        assertEquals("0", ExploreFormat.num(0f))
        assertEquals("?", ExploreFormat.num(Float.NaN))
        assertEquals("4.38, 6.8", ExploreFormat.nums(listOf(4.38f, 6.8f)))
        assertEquals("camera 0", Cameras.subject("0"))
        assertEquals("0", Cameras.idOf("camera 0"))
        assertNull(Cameras.idOf(Cameras.SUMMARY))
    }

    @Test
    fun maxVideoFpsPrefersHighSpeedWhenHigher() {
        assertEquals("240 (high-speed)", Cameras.maxVideoFpsText(back))
        assertEquals("30", Cameras.maxVideoFpsText(front))
        assertEquals("60", Cameras.maxVideoFpsText(CameraFacts(id = "x", maxFps = 60, maxHighSpeedFps = 60)))
        assertNull(Cameras.maxVideoFpsText(CameraFacts(id = "x")))
    }

    @Test
    fun observationsFollowTheSchema() {
        val obs = Cameras.observations(listOf(back, front, lens))
        assertTrue(obs.all { it.tunnelId == Cameras.TUNNEL_ID })
        assertEquals(setOf("camera 0", "camera 1", "camera 2", Cameras.SUMMARY), obs.map { it.subject }.toSet())
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.filter { it.subject != Cameras.SUMMARY }.all { it.key in Cameras.perCamera })
        assertTrue(obs.filter { it.subject == Cameras.SUMMARY }.all { it.key in Cameras.summaryKeys })

        assertEquals("back", value(obs, "camera 0", Cameras.FACING))
        assertEquals("level3", value(obs, "camera 0", Cameras.HW_LEVEL))
        assertEquals("4080x3072 · 12 MP", value(obs, "camera 0", Cameras.MAX_RESOLUTION))
        assertEquals("6.81", value(obs, "camera 0", Cameras.FOCAL_LENGTHS))
        assertEquals("f/1.68", value(obs, "camera 0", Cameras.APERTURES))
        assertEquals("9.8x7.3", value(obs, "camera 0", Cameras.SENSOR_SIZE))
        assertEquals("true", value(obs, "camera 0", Cameras.FLASH))
        assertEquals("true", value(obs, "camera 0", Cameras.OIS))
        assertEquals("basic, manual sensor, raw, high-speed video, logical multi-camera, 10-bit", value(obs, "camera 0", Cameras.CAPABILITIES))
        assertEquals("2, 3", value(obs, "camera 0", Cameras.PHYSICAL_IDS))
        assertEquals("240 (high-speed)", value(obs, "camera 0", Cameras.FPS_MAX_VIDEO))
        assertEquals("0.5–30", value(obs, "camera 0", Cameras.ZOOM_RANGE))
        assertEquals("90", value(obs, "camera 0", Cameras.ORIENTATION))
        assertNull(value(obs, "camera 0", Cameras.PHYSICAL_OF))
        assertNull(value(obs, "camera 0", Cameras.ERROR))

        assertEquals("front", value(obs, "camera 1", Cameras.FACING))
        assertNull(value(obs, "camera 1", Cameras.SENSOR_SIZE))
        assertNull(value(obs, "camera 1", Cameras.PHYSICAL_IDS))
        assertEquals("30", value(obs, "camera 1", Cameras.FPS_MAX_VIDEO))

        assertEquals("0", value(obs, "camera 2", Cameras.PHYSICAL_OF))
        assertEquals("none", value(obs, "camera 2", Cameras.FOCAL_LENGTHS))
        assertEquals("none", value(obs, "camera 2", Cameras.APERTURES))

        assertEquals("2", value(obs, Cameras.SUMMARY, Cameras.COUNT))
        assertEquals("1", value(obs, Cameras.SUMMARY, Cameras.LOGICAL_COUNT))
        assertEquals("1", value(obs, Cameras.SUMMARY, Cameras.PHYSICAL_COUNT))
        assertNull(value(obs, Cameras.SUMMARY, Cameras.SKIPPED))
    }

    @Test
    fun unreadableCameraKeepsOnlyTheError() {
        val obs = Cameras.observations(listOf(CameraFacts(id = "5", error = "CAMERA_DISCONNECTED"), front))
        assertEquals(setOf(Cameras.ERROR), obs.filter { it.subject == "camera 5" }.map { it.key }.toSet())
        assertEquals("CAMERA_DISCONNECTED", value(obs, "camera 5", Cameras.ERROR))
        assertEquals("2", value(obs, Cameras.SUMMARY, Cameras.COUNT))
        assertEquals("0", value(obs, Cameras.SUMMARY, Cameras.LOGICAL_COUNT))
    }

    @Test
    fun hugeCameraListsAreCapped() {
        val facts = (0 until Cameras.MAX_CAMERAS + 4).map { front.copy(id = "$it") }
        val obs = Cameras.observations(facts)
        assertEquals(Cameras.MAX_CAMERAS, (obs.map { it.subject }.toSet() - Cameras.SUMMARY).size)
        assertEquals("4", value(obs, Cameras.SUMMARY, Cameras.SKIPPED))
    }

    @Test
    fun noCameraServiceStillHasASummary() {
        val obs = Cameras.observations(emptyList())
        assertEquals(setOf(Cameras.SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("0", value(obs, Cameras.SUMMARY, Cameras.COUNT))
    }
}
