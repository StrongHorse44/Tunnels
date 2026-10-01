package io.github.stronghorse44.tunnels.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatellitesTest {
    private fun value(obs: List<io.github.stronghorse44.tunnels.model.Observation>, subject: String, key: String) =
        obs.firstOrNull { it.subject == subject && it.key == key }?.value

    @Test
    fun constellationNamesFollowGnssStatusCodes() {
        assertEquals("GPS", Satellites.constellationName(1))
        assertEquals("SBAS", Satellites.constellationName(2))
        assertEquals("GLONASS", Satellites.constellationName(3))
        assertEquals("QZSS", Satellites.constellationName(4))
        assertEquals("BeiDou", Satellites.constellationName(5))
        assertEquals("Galileo", Satellites.constellationName(6))
        assertEquals("NavIC", Satellites.constellationName(7))
        assertEquals("Unknown", Satellites.constellationName(0))
        assertEquals("Unknown", Satellites.constellationName(42))
    }

    @Test
    fun summariseGroupsAndAverages() {
        val samples = listOf(
            SatelliteSample(1, 30f, true),
            SatelliteSample(1, 40f, true),
            SatelliteSample(1, 0f, false), // zero C/N0 means "not tracked": excluded from the averages, counted as visible
            SatelliteSample(6, 25f, false),
            SatelliteSample(5, Float.NaN, false),
            SatelliteSample(3, 20f, true),
        )
        val facts = Satellites.summarise(samples)
        assertEquals(listOf("GPS", "GLONASS", "Galileo", "BeiDou"), facts.map { it.name })
        val gps = facts.single { it.name == "GPS" }
        assertEquals(3, gps.visible)
        assertEquals(2, gps.usedInFix)
        assertEquals(35f, gps.cn0Avg)
        assertEquals(40f, gps.cn0Max)
        val beidou = facts.single { it.name == "BeiDou" }
        assertEquals(1, beidou.visible)
        assertEquals(0f, beidou.cn0Avg)
        assertEquals(0f, beidou.cn0Max)
        assertTrue(Satellites.summarise(emptyList()).isEmpty())
    }

    @Test
    fun observationsCarryTheSchema() {
        val facts = GnssFacts(
            available = Satellites.AVAILABLE_YES,
            listenSeconds = 20,
            statusUpdates = 18,
            constellations = Satellites.summarise(listOf(SatelliteSample(1, 33.26f, true), SatelliteSample(1, 41.04f, false), SatelliteSample(6, 28f, true))),
            hasMeasurements = true,
            hasNavigationMessages = false,
            yearOfHardware = 2023,
            hardwareModel = "BCM47765",
        )
        val obs = Satellites.observations(facts)
        assertTrue(obs.all { it.tunnelId == Satellites.TUNNEL_ID })
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        assertEquals("yes", value(obs, Satellites.SUMMARY, Satellites.AVAILABLE))
        assertEquals("20", value(obs, Satellites.SUMMARY, Satellites.LISTEN_SECONDS))
        assertEquals("18", value(obs, Satellites.SUMMARY, Satellites.STATUS_UPDATES))
        assertEquals("3", value(obs, Satellites.SUMMARY, Satellites.SATS_VISIBLE))
        assertEquals("2", value(obs, Satellites.SUMMARY, Satellites.SATS_USED))
        assertEquals("measurements=true,navigationMessages=false", value(obs, Satellites.SUMMARY, Satellites.CAPABILITIES))
        assertEquals("2023", value(obs, Satellites.SUMMARY, Satellites.YEAR_OF_HARDWARE))
        assertEquals("BCM47765", value(obs, Satellites.SUMMARY, Satellites.HARDWARE_MODEL))
        assertEquals("2", value(obs, "GPS", Satellites.SATS_VISIBLE))
        assertEquals("1", value(obs, "GPS", Satellites.SATS_USED))
        assertEquals("37.2", value(obs, "GPS", Satellites.CN0_AVG))
        assertEquals("41", value(obs, "GPS", Satellites.CN0_MAX))
        assertEquals("28", value(obs, "Galileo", Satellites.CN0_AVG))
        assertTrue(obs.filter { it.subject == Satellites.SUMMARY }.all { it.key in Satellites.summaryKeys })
        assertTrue(obs.filter { it.subject != Satellites.SUMMARY }.all { it.key in Satellites.perConstellation })
    }

    @Test
    fun unavailableReceiverStillReportsItself() {
        val obs = Satellites.observations(GnssFacts(Satellites.AVAILABLE_NO_PERMISSION, 0, 0, emptyList(), yearOfHardware = 0, hardwareModel = ""))
        assertEquals(setOf(Satellites.SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("no permission", value(obs, Satellites.SUMMARY, Satellites.AVAILABLE))
        assertEquals("0", value(obs, Satellites.SUMMARY, Satellites.SATS_VISIBLE))
        assertNull(value(obs, Satellites.SUMMARY, Satellites.YEAR_OF_HARDWARE))
        assertNull(value(obs, Satellites.SUMMARY, Satellites.HARDWARE_MODEL))
        assertNull(value(obs, Satellites.SUMMARY, Satellites.CAPABILITIES))
        assertTrue(obs.none { it.value.isEmpty() })
    }
}
