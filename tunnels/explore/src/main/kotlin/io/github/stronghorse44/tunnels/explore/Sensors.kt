package io.github.stronghorse44.tunnels.explore

import io.github.stronghorse44.tunnels.model.Observation

/**
 * What SensorManager says about one sensor, copied into plain Kotlin so the observation builder and its
 * tests need no Android. Nothing here is a reading: only the hardware's description of itself.
 */
data class SensorFacts(
    val name: String,
    val type: Int,
    /** `Sensor.getStringType()`, e.g. `android.sensor.accelerometer`; blank when the HAL gives none. */
    val stringType: String,
    val vendor: String,
    val version: Int,
    val powerMa: Float,
    val resolution: Float,
    val maxRange: Float,
    val minDelayUs: Int,
    val wakeUp: Boolean,
    /** `Sensor.REPORTING_MODE_*`: 0 continuous, 1 on-change, 2 one-shot, 3 special trigger. */
    val reportingMode: Int,
    val dynamic: Boolean,
    val fifoMax: Int,
)

/** The four groups the sensors card uses, following Android's own sensor taxonomy. */
enum class SensorCategory(val label: String) {
    MOTION("Motion"),
    POSITION("Position"),
    ENVIRONMENT("Environment"),
    OTHER("Other"),
    ;

    companion object {
        fun parse(value: String?): SensorCategory = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** Sensors tunnel: observation schema and the pure builder from [SensorFacts] to [Observation]s. */
object Sensors {
    const val TUNNEL_ID = "sensors"
    const val SUMMARY = "summary"

    /** Sensors beyond this many are counted in the summary instead of listed, so the snapshot stays bounded. */
    const val MAX_SENSORS = 150

    const val CATEGORY = "category"
    const val TYPE = "type"
    const val VENDOR = "vendor"
    const val VERSION = "version"
    const val POWER = "power:mA"
    const val RESOLUTION = "resolution"
    const val MAX_RANGE = "maxRange"
    const val MIN_DELAY = "minDelay:us"
    const val WAKE_UP = "wakeUp"
    const val REPORTING_MODE = "reportingMode"
    const val DYNAMIC = "dynamic"
    const val FIFO_MAX = "fifo:max"

    /** Every key a sensor subject carries. */
    val perSensor = listOf(CATEGORY, TYPE, VENDOR, VERSION, POWER, RESOLUTION, MAX_RANGE, MIN_DELAY, WAKE_UP, REPORTING_MODE, DYNAMIC, FIFO_MAX)

    const val COUNT = "count"
    const val WAKE_UP_COUNT = "wakeUpCount"
    const val DYNAMIC_COUNT = "dynamicCount"
    const val SKIPPED = "skipped"

    fun countKey(category: SensorCategory) = "count:${category.name.lowercase()}"

    /** Every key the summary subject may carry. */
    val summaryKeys = listOf(COUNT, WAKE_UP_COUNT, DYNAMIC_COUNT, SKIPPED) + SensorCategory.entries.map(::countKey)

    private const val ANDROID_PREFIX = "android.sensor."

    private val motion = setOf(
        "accelerometer", "accelerometer_uncalibrated", "accelerometer_limited_axes", "accelerometer_limited_axes_uncalibrated",
        "gravity", "gyroscope", "gyroscope_uncalibrated", "gyroscope_limited_axes", "gyroscope_limited_axes_uncalibrated",
        "linear_acceleration", "rotation_vector", "significant_motion", "step_counter", "step_detector",
        "motion_detect", "stationary_detect", "pick_up_gesture", "wake_gesture", "glance_gesture", "tilt_detector",
    )
    private val position = setOf(
        "magnetic_field", "magnetic_field_uncalibrated", "orientation", "proximity", "game_rotation_vector",
        "geomagnetic_rotation_vector", "pose_6dof", "heading", "hinge_angle", "device_orientation",
    )
    private val environment = setOf("light", "pressure", "ambient_temperature", "relative_humidity", "temperature")

    /** The string type when the HAL reports one, else `type <int>`. */
    fun typeName(facts: SensorFacts): String = facts.stringType.trim().ifEmpty { "type ${facts.type}" }

    /** `android.sensor.light` → `light`; vendor types keep their full name. */
    fun shortType(typeName: String): String = typeName.removePrefix(ANDROID_PREFIX)

    /** Category of a sensor by its string type; vendor-specific types and unknown platform types are OTHER. */
    fun category(typeName: String): SensorCategory {
        if (!typeName.startsWith(ANDROID_PREFIX)) return SensorCategory.OTHER
        return when (shortType(typeName)) {
            in motion -> SensorCategory.MOTION
            in position -> SensorCategory.POSITION
            in environment -> SensorCategory.ENVIRONMENT
            else -> SensorCategory.OTHER
        }
    }

    fun reportingModeName(mode: Int): String = when (mode) {
        0 -> "continuous"
        1 -> "on-change"
        2 -> "one-shot"
        3 -> "special-trigger"
        else -> "mode $mode"
    }

    /**
     * One subject per sensor, in the order given. Names that collide are refined step by step: first the
     * short type, then a wake-up marker, finally a position number, so two sensors never share a subject.
     * The reserved [SUMMARY] subject is never handed to a sensor.
     */
    fun subjects(facts: List<SensorFacts>): List<String> {
        val names = facts.map { f ->
            val base = f.name.trim().ifEmpty { "Sensor ${shortType(typeName(f))}" }
            if (base.equals(SUMMARY, ignoreCase = true)) "$base (sensor)" else base
        }.toMutableList()
        val refinements: List<(Int, String) -> String> = listOf(
            { i, n -> "$n · ${shortType(typeName(facts[i]))}" },
            { i, n -> if (facts[i].wakeUp) "$n (wake-up)" else n },
            { i, n -> "$n #${i + 1}" },
        )
        for (refine in refinements) {
            val dupes = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dupes.isEmpty()) break
            for (i in names.indices) if (names[i] in dupes) names[i] = refine(i, names[i])
        }
        return names
    }

    /** Observations for every sensor up to [MAX_SENSORS], plus the summary subject. */
    fun observations(facts: List<SensorFacts>): List<Observation> {
        val listed = facts.take(MAX_SENSORS)
        val subjects = subjects(listed)
        val out = ArrayList<Observation>(listed.size * perSensor.size + summaryKeys.size)
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))

        val byCategory = facts.groupingBy { category(typeName(it)) }.eachCount()
        listed.forEachIndexed { i, f ->
            val subject = subjects[i]
            val type = typeName(f)
            add(subject, CATEGORY, category(type).name.lowercase())
            add(subject, TYPE, type)
            add(subject, VENDOR, f.vendor.trim().ifEmpty { "unknown" })
            add(subject, VERSION, f.version.toString())
            add(subject, POWER, ExploreFormat.num(f.powerMa))
            add(subject, RESOLUTION, ExploreFormat.num(f.resolution))
            add(subject, MAX_RANGE, ExploreFormat.num(f.maxRange))
            add(subject, MIN_DELAY, f.minDelayUs.toString())
            add(subject, WAKE_UP, f.wakeUp.toString())
            add(subject, REPORTING_MODE, reportingModeName(f.reportingMode))
            add(subject, DYNAMIC, f.dynamic.toString())
            add(subject, FIFO_MAX, f.fifoMax.toString())
        }
        add(SUMMARY, COUNT, facts.size.toString())
        add(SUMMARY, WAKE_UP_COUNT, facts.count { it.wakeUp }.toString())
        add(SUMMARY, DYNAMIC_COUNT, facts.count { it.dynamic }.toString())
        SensorCategory.entries.forEach { add(SUMMARY, countKey(it), (byCategory[it] ?: 0).toString()) }
        if (facts.size > listed.size) add(SUMMARY, SKIPPED, (facts.size - listed.size).toString())
        return out
    }
}
