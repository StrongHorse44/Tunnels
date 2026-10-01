package io.github.stronghorse44.tunnels.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsTest {
    private fun sensor(
        name: String,
        stringType: String = "android.sensor.accelerometer",
        type: Int = 1,
        wakeUp: Boolean = false,
        vendor: String = "Bosch",
        power: Float = 0.15f,
        dynamic: Boolean = false,
    ) = SensorFacts(
        name = name, type = type, stringType = stringType, vendor = vendor, version = 2, powerMa = power,
        resolution = 0.0012f, maxRange = 39.2f, minDelayUs = 2500, wakeUp = wakeUp, reportingMode = 0, dynamic = dynamic, fifoMax = 3000,
    )

    private fun value(obs: List<io.github.stronghorse44.tunnels.model.Observation>, subject: String, key: String) =
        obs.firstOrNull { it.subject == subject && it.key == key }?.value

    @Test
    fun categoriesFollowAndroidTaxonomy() {
        assertEquals(SensorCategory.MOTION, Sensors.category("android.sensor.accelerometer"))
        assertEquals(SensorCategory.MOTION, Sensors.category("android.sensor.step_counter"))
        assertEquals(SensorCategory.POSITION, Sensors.category("android.sensor.magnetic_field_uncalibrated"))
        assertEquals(SensorCategory.POSITION, Sensors.category("android.sensor.proximity"))
        assertEquals(SensorCategory.ENVIRONMENT, Sensors.category("android.sensor.light"))
        assertEquals(SensorCategory.ENVIRONMENT, Sensors.category("android.sensor.pressure"))
        assertEquals(SensorCategory.OTHER, Sensors.category("android.sensor.heart_rate"))
        assertEquals(SensorCategory.OTHER, Sensors.category("com.google.sensor.double_twist"))
        assertEquals(SensorCategory.OTHER, Sensors.category("type 65560"))
        assertEquals(SensorCategory.OTHER, SensorCategory.parse(null))
        assertEquals(SensorCategory.MOTION, SensorCategory.parse("motion"))
    }

    @Test
    fun typeNameFallsBackToTheInt() {
        assertEquals("android.sensor.light", Sensors.typeName(sensor("Light", stringType = "android.sensor.light", type = 5)))
        assertEquals("type 65560", Sensors.typeName(sensor("Vendor thing", stringType = "  ", type = 65560)))
        assertEquals("light", Sensors.shortType("android.sensor.light"))
        assertEquals("com.google.sensor.double_twist", Sensors.shortType("com.google.sensor.double_twist"))
    }

    @Test
    fun reportingModesAreNamed() {
        assertEquals("continuous", Sensors.reportingModeName(0))
        assertEquals("on-change", Sensors.reportingModeName(1))
        assertEquals("one-shot", Sensors.reportingModeName(2))
        assertEquals("special-trigger", Sensors.reportingModeName(3))
        assertEquals("mode 9", Sensors.reportingModeName(9))
    }

    @Test
    fun collidingNamesGetUniqueSubjects() {
        val facts = listOf(
            sensor("LSM6DSO", stringType = "android.sensor.accelerometer"),
            sensor("LSM6DSO", stringType = "android.sensor.gyroscope"),
            sensor("LSM6DSO", stringType = "android.sensor.gyroscope", wakeUp = true),
            sensor("Light", stringType = "android.sensor.light"),
            sensor("Light", stringType = "android.sensor.light"),
            sensor("Light", stringType = "android.sensor.light"),
            sensor("", stringType = "android.sensor.pressure"),
            sensor("summary", stringType = "android.sensor.proximity"),
        )
        val subjects = Sensors.subjects(facts)
        assertEquals(subjects.size, subjects.toSet().size)
        assertEquals("LSM6DSO · accelerometer", subjects[0])
        assertEquals("LSM6DSO · gyroscope", subjects[1])
        assertEquals("LSM6DSO · gyroscope (wake-up)", subjects[2])
        // Three identical non-wake-up light sensors fall through to position numbers.
        assertTrue(subjects[3], subjects[3].startsWith("Light · light #"))
        assertEquals("Sensor pressure", subjects[6])
        assertEquals("summary (sensor)", subjects[7])
        assertFalse(Sensors.SUMMARY in subjects)
    }

    @Test
    fun observationsFollowTheSchema() {
        val facts = listOf(
            sensor("Accel", stringType = "android.sensor.accelerometer", power = 0.15f),
            sensor("Light", stringType = "android.sensor.light", wakeUp = true, vendor = " ", power = 0.001f),
            sensor("Twist", stringType = "com.google.sensor.double_twist", type = 65540, dynamic = true),
        )
        val obs = Sensors.observations(facts)
        assertTrue(obs.all { it.tunnelId == Sensors.TUNNEL_ID })
        val subjects = obs.map { it.subject }.toSet()
        assertEquals(setOf("Accel", "Light", "Twist", Sensors.SUMMARY), subjects)
        for (s in subjects - Sensors.SUMMARY) {
            assertEquals(Sensors.perSensor.toSet(), obs.filter { it.subject == s }.map { it.key }.toSet())
        }
        assertTrue(obs.filter { it.subject == Sensors.SUMMARY }.all { it.key in Sensors.summaryKeys })
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)

        assertEquals("motion", value(obs, "Accel", Sensors.CATEGORY))
        assertEquals("0.15", value(obs, "Accel", Sensors.POWER))
        assertEquals("39.2", value(obs, "Accel", Sensors.MAX_RANGE))
        assertEquals("0.0012", value(obs, "Accel", Sensors.RESOLUTION))
        assertEquals("2500", value(obs, "Accel", Sensors.MIN_DELAY))
        assertEquals("continuous", value(obs, "Accel", Sensors.REPORTING_MODE))
        assertEquals("3000", value(obs, "Accel", Sensors.FIFO_MAX))
        assertEquals("unknown", value(obs, "Light", Sensors.VENDOR))
        assertEquals("true", value(obs, "Light", Sensors.WAKE_UP))
        assertEquals("environment", value(obs, "Light", Sensors.CATEGORY))
        assertEquals("other", value(obs, "Twist", Sensors.CATEGORY))
        assertEquals("true", value(obs, "Twist", Sensors.DYNAMIC))

        assertEquals("3", value(obs, Sensors.SUMMARY, Sensors.COUNT))
        assertEquals("1", value(obs, Sensors.SUMMARY, Sensors.WAKE_UP_COUNT))
        assertEquals("1", value(obs, Sensors.SUMMARY, Sensors.DYNAMIC_COUNT))
        assertEquals("1", value(obs, Sensors.SUMMARY, Sensors.countKey(SensorCategory.MOTION)))
        assertEquals("0", value(obs, Sensors.SUMMARY, Sensors.countKey(SensorCategory.POSITION)))
        assertEquals("1", value(obs, Sensors.SUMMARY, Sensors.countKey(SensorCategory.ENVIRONMENT)))
        assertEquals("1", value(obs, Sensors.SUMMARY, Sensors.countKey(SensorCategory.OTHER)))
        assertNull(value(obs, Sensors.SUMMARY, Sensors.SKIPPED))
    }

    @Test
    fun hugeSensorListsAreCapped() {
        val facts = (1..Sensors.MAX_SENSORS + 25).map { sensor("S$it") }
        val obs = Sensors.observations(facts)
        val listed = obs.map { it.subject }.toSet() - Sensors.SUMMARY
        assertEquals(Sensors.MAX_SENSORS, listed.size)
        assertEquals("${Sensors.MAX_SENSORS + 25}", value(obs, Sensors.SUMMARY, Sensors.COUNT))
        assertEquals("25", value(obs, Sensors.SUMMARY, Sensors.SKIPPED))
        assertTrue(obs.size <= Sensors.MAX_SENSORS * Sensors.perSensor.size + Sensors.summaryKeys.size)
    }

    @Test
    fun emptyDeviceStillHasASummary() {
        val obs = Sensors.observations(emptyList())
        assertEquals(setOf(Sensors.SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("0", value(obs, Sensors.SUMMARY, Sensors.COUNT))
    }
}
