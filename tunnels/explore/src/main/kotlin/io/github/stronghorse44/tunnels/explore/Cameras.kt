package io.github.stronghorse44.tunnels.explore

import io.github.stronghorse44.tunnels.model.Observation

/**
 * What CameraCharacteristics says about one camera, copied into plain Kotlin. Integer codes keep the
 * platform's values (`CameraCharacteristics.LENS_FACING_*`, `INFO_SUPPORTED_HARDWARE_LEVEL_*`,
 * `REQUEST_AVAILABLE_CAPABILITIES_*`); [Cameras] names them. Nothing here is an image.
 */
data class CameraFacts(
    val id: String,
    /** The logical camera this lens belongs to when it is not openable on its own. */
    val physicalOf: String? = null,
    val facing: Int? = null,
    val hwLevel: Int? = null,
    /** Largest JPEG output, width × height. */
    val maxJpeg: Pair<Int, Int>? = null,
    val focalLengthsMm: List<Float> = emptyList(),
    val apertures: List<Float> = emptyList(),
    val sensorSizeMm: Pair<Float, Float>? = null,
    val flash: Boolean? = null,
    val ois: Boolean? = null,
    val capabilities: List<Int> = emptyList(),
    val physicalIds: List<String> = emptyList(),
    /** Highest upper bound among the regular AE target FPS ranges. */
    val maxFps: Int? = null,
    /** Highest upper bound among the constrained high-speed ranges, when the camera has any. */
    val maxHighSpeedFps: Int? = null,
    val zoomRange: Pair<Float, Float>? = null,
    val orientationDeg: Int? = null,
    /** Why the characteristics could not be read, when they could not. */
    val error: String? = null,
)

/** Cameras tunnel: observation schema and the pure builder from [CameraFacts] to [Observation]s. */
object Cameras {
    const val TUNNEL_ID = "cameras"
    const val SUMMARY = "summary"

    /** Cameras (public ids plus physical lenses) beyond this many are counted in the summary instead of listed. */
    const val MAX_CAMERAS = 16

    const val FACING = "facing"
    const val HW_LEVEL = "hwLevel"
    const val MAX_RESOLUTION = "maxResolution"
    const val FOCAL_LENGTHS = "focalLengths:mm"
    const val APERTURES = "apertures"
    const val SENSOR_SIZE = "sensorSize:mm"
    const val FLASH = "flash"
    const val OIS = "ois"
    const val CAPABILITIES = "capabilities"
    const val PHYSICAL_IDS = "physicalIds"
    const val PHYSICAL_OF = "physicalOf"
    const val FPS_MAX_VIDEO = "fps:maxVideo"
    const val ZOOM_RANGE = "zoomRatio:range"
    const val ORIENTATION = "orientation:deg"
    const val ERROR = "error"

    /** Every key a camera subject may carry. */
    val perCamera = listOf(
        FACING, HW_LEVEL, MAX_RESOLUTION, FOCAL_LENGTHS, APERTURES, SENSOR_SIZE, FLASH, OIS, CAPABILITIES,
        PHYSICAL_IDS, PHYSICAL_OF, FPS_MAX_VIDEO, ZOOM_RANGE, ORIENTATION, ERROR,
    )

    const val COUNT = "count"
    const val LOGICAL_COUNT = "logicalCount"
    const val PHYSICAL_COUNT = "physicalCount"
    const val SKIPPED = "skipped"
    val summaryKeys = listOf(COUNT, LOGICAL_COUNT, PHYSICAL_COUNT, SKIPPED)

    private const val SUBJECT_PREFIX = "camera "

    fun subject(id: String) = "$SUBJECT_PREFIX$id"

    /** The camera id a subject stands for, or null for the summary. */
    fun idOf(subject: String): String? = subject.takeIf { it.startsWith(SUBJECT_PREFIX) }?.removePrefix(SUBJECT_PREFIX)

    /** `CameraCharacteristics.LENS_FACING_*`. */
    fun facingName(code: Int?): String = when (code) {
        0 -> "front"
        1 -> "back"
        2 -> "external"
        null -> "unknown"
        else -> "facing $code"
    }

    /** `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_*`. */
    fun hwLevelName(code: Int?): String = when (code) {
        0 -> "limited"
        1 -> "full"
        2 -> "legacy"
        3 -> "level3"
        4 -> "external"
        null -> "unknown"
        else -> "level $code"
    }

    /** `CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_*`, as a reader would say them. */
    fun capabilityName(code: Int): String = when (code) {
        0 -> "basic"
        1 -> "manual sensor"
        2 -> "manual post-processing"
        3 -> "raw"
        4 -> "private reprocessing"
        5 -> "read sensor settings"
        6 -> "burst capture"
        7 -> "yuv reprocessing"
        8 -> "depth"
        9 -> "high-speed video"
        10 -> "motion tracking"
        11 -> "logical multi-camera"
        12 -> "monochrome"
        13 -> "secure image data"
        14 -> "system camera"
        15 -> "offline processing"
        16 -> "ultra high resolution"
        17 -> "remosaic reprocessing"
        18 -> "10-bit"
        19 -> "stream use case"
        20 -> "color space profiles"
        else -> "capability $code"
    }

    /** `CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`. */
    const val CAPABILITY_LOGICAL_MULTI_CAMERA = 11

    fun isLogical(facts: CameraFacts) = facts.physicalIds.isNotEmpty() || CAPABILITY_LOGICAL_MULTI_CAMERA in facts.capabilities

    fun resolutionText(maxJpeg: Pair<Int, Int>): String = "${maxJpeg.first}x${maxJpeg.second} · ${ExploreFormat.megapixels(maxJpeg.first, maxJpeg.second)}"

    fun aperturesText(apertures: List<Float>): String = if (apertures.isEmpty()) "none" else apertures.joinToString(", ") { "f/${ExploreFormat.num(it)}" }

    fun rangeText(range: Pair<Float, Float>): String = "${ExploreFormat.num(range.first)}–${ExploreFormat.num(range.second)}"

    /** The higher of the regular and high-speed maxima, marked when high-speed recording wins. */
    fun maxVideoFpsText(facts: CameraFacts): String? {
        val regular = facts.maxFps
        val fast = facts.maxHighSpeedFps
        return when {
            fast != null && fast > (regular ?: 0) -> "$fast (high-speed)"
            regular != null -> regular.toString()
            else -> null
        }
    }

    /** Observations for every camera up to [MAX_CAMERAS], plus the summary subject. */
    fun observations(facts: List<CameraFacts>): List<Observation> {
        val listed = facts.take(MAX_CAMERAS)
        val out = ArrayList<Observation>(listed.size * perCamera.size + summaryKeys.size)
        fun add(subject: String, key: String, value: String) = out.add(Observation(TUNNEL_ID, subject, key, value))

        for (f in listed) {
            val subject = subject(f.id)
            f.error?.let { add(subject, ERROR, it) }
            f.physicalOf?.let { add(subject, PHYSICAL_OF, it) }
            if (f.error != null && f.facing == null && f.hwLevel == null) continue
            add(subject, FACING, facingName(f.facing))
            add(subject, HW_LEVEL, hwLevelName(f.hwLevel))
            f.maxJpeg?.let { add(subject, MAX_RESOLUTION, resolutionText(it)) }
            add(subject, FOCAL_LENGTHS, ExploreFormat.nums(f.focalLengthsMm))
            add(subject, APERTURES, aperturesText(f.apertures))
            f.sensorSizeMm?.let { add(subject, SENSOR_SIZE, "${ExploreFormat.num(it.first)}x${ExploreFormat.num(it.second)}") }
            f.flash?.let { add(subject, FLASH, it.toString()) }
            f.ois?.let { add(subject, OIS, it.toString()) }
            add(subject, CAPABILITIES, if (f.capabilities.isEmpty()) "none" else f.capabilities.sorted().joinToString(", ", transform = ::capabilityName))
            if (f.physicalIds.isNotEmpty()) add(subject, PHYSICAL_IDS, f.physicalIds.joinToString(", "))
            maxVideoFpsText(f)?.let { add(subject, FPS_MAX_VIDEO, it) }
            f.zoomRange?.let { add(subject, ZOOM_RANGE, rangeText(it)) }
            f.orientationDeg?.let { add(subject, ORIENTATION, it.toString()) }
        }
        val public = facts.filter { it.physicalOf == null }
        add(SUMMARY, COUNT, public.size.toString())
        add(SUMMARY, LOGICAL_COUNT, public.count(::isLogical).toString())
        add(SUMMARY, PHYSICAL_COUNT, (facts.size - public.size).toString())
        if (facts.size > listed.size) add(SUMMARY, SKIPPED, (facts.size - listed.size).toString())
        return out
    }
}
