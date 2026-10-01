package io.github.stronghorse44.tunnels.explore

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi

/**
 * Cameras: the characteristics of every camera, including the physical lenses behind logical ones.
 * Reading characteristics needs no permission and never opens a camera. Explore line: no rules, no actions.
 */
class CamerasTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = Cameras.TUNNEL_ID
    override val requiredPermissions: List<PermissionSpec> = emptyList()
    override val rules: List<FindingRule> = emptyList()

    override fun actionsFor(draft: FindingDraft): List<FindingAction> = emptyList()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return Cameras.observations(emptyList())
        val ids = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            return Cameras.observations(listOf(CameraFacts(id = "?", error = "Camera service unavailable: ${describe(e)}")))
        }
        val facts = ArrayList<CameraFacts>()
        ids.forEachIndexed { i, id ->
            progress.report(i, ids.size, "camera $id")
            facts += read(manager, id, physicalOf = null)
        }
        // Physical lenses that are not openable on their own still describe themselves.
        val physical = facts.flatMap { f -> f.physicalIds.map { it to f.id } }.filter { (id, _) -> ids.none { it == id } }.distinctBy { it.first }
        physical.forEachIndexed { i, (id, parent) ->
            if (facts.size >= Cameras.MAX_CAMERAS) return@forEachIndexed
            progress.report(i, physical.size, "lens $id")
            facts += read(manager, id, physicalOf = parent)
        }
        progress.report(1, 1, "done")
        return Cameras.observations(facts)
    }

    private fun read(manager: CameraManager, id: String, physicalOf: String?): CameraFacts = try {
        manager.getCameraCharacteristics(id).facts(id, physicalOf)
    } catch (e: Exception) {
        CameraFacts(id = id, physicalOf = physicalOf, error = describe(e))
    }

    private fun describe(e: Exception) = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        CamerasContent(state)
    }
}

/** One key, swallowing the odd HAL that throws on a lookup. */
private fun <T> CameraCharacteristics.safe(key: CameraCharacteristics.Key<T>): T? = runCatching { get(key) }.getOrNull()

/** Copies the description out of the platform object; the camera is never opened. */
internal fun CameraCharacteristics.facts(id: String, physicalOf: String?): CameraFacts {
    val map = safe(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val jpeg = runCatching { map?.getOutputSizes(ImageFormat.JPEG) }.getOrNull().orEmpty().maxByOrNull { it.width.toLong() * it.height }
    val highSpeed = runCatching { map?.highSpeedVideoFpsRanges }.getOrNull().orEmpty().maxOfOrNull { it.upper }
    val ois = safe(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
    return CameraFacts(
        id = id,
        physicalOf = physicalOf,
        facing = safe(CameraCharacteristics.LENS_FACING),
        hwLevel = safe(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
        maxJpeg = jpeg?.let { it.width to it.height },
        focalLengthsMm = safe(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
        apertures = safe(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
        sensorSizeMm = safe(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width to it.height },
        flash = safe(CameraCharacteristics.FLASH_INFO_AVAILABLE),
        ois = ois?.let { CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in it },
        capabilities = safe(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty(),
        physicalIds = runCatching { physicalCameraIds.toList().sorted() }.getOrDefault(emptyList()),
        maxFps = safe(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper },
        maxHighSpeedFps = highSpeed,
        zoomRange = safe(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower to it.upper },
        orientationDeg = safe(CameraCharacteristics.SENSOR_ORIENTATION),
    )
}

@Composable
private fun CamerasContent(state: TunnelScreenState) {
    val subjects = remember(state.observations) { ExploreFormat.bySubject(state.observations) }
    val summary = subjects[Cameras.SUMMARY].orEmpty()
    val cameras = remember(subjects) {
        subjects.filterKeys { it != Cameras.SUMMARY }.entries.sortedWith(compareBy({ it.value[Cameras.PHYSICAL_OF] != null }, { Cameras.idOf(it.key)?.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExploreNote()
        if (cameras.isEmpty()) {
            ExploreEmpty(if (state.lastScan == null) "Scan to describe every camera on this device." else "This device lists no cameras.")
            return@Column
        }
        cameras.forEach { (subject, facts) -> CameraCard(Cameras.idOf(subject) ?: subject, facts) }
        FactLine(
            listOfNotNull(
                summary[Cameras.COUNT]?.let { "$it cameras" },
                summary[Cameras.LOGICAL_COUNT]?.takeIf { it != "0" }?.let { "$it logical" },
                summary[Cameras.PHYSICAL_COUNT]?.takeIf { it != "0" }?.let { "$it physical lenses" },
                summary[Cameras.SKIPPED]?.let { "$it more not listed" },
            ),
        )
    }
}

@Composable
private fun CameraCard(id: String, facts: Map<String, String>) {
    val physicalOf = facts[Cameras.PHYSICAL_OF]
    val facing = facts[Cameras.FACING]
    val title = when {
        physicalOf != null -> "Lens $id · part of camera $physicalOf"
        facing != null -> "${facing.replaceFirstChar { it.uppercase() }} camera"
        else -> "Camera $id"
    }
    GlassPanel(Modifier.fillMaxWidth(), tint = exploreTint) {
        CardColumn {
            CardTitle(title, "id $id")
            facts[Cameras.ERROR]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
            facts[Cameras.MAX_RESOLUTION]?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            FactLine(
                listOfNotNull(
                    facts[Cameras.APERTURES]?.takeIf { it != "none" },
                    facts[Cameras.FOCAL_LENGTHS]?.takeIf { it != "none" }?.let { "$it mm" },
                    facts[Cameras.SENSOR_SIZE]?.let { "$it mm sensor" },
                ),
            )
            FactLine(
                listOfNotNull(
                    facts[Cameras.HW_LEVEL]?.let { "hardware $it" },
                    facts[Cameras.FLASH]?.let { if (it == "true") "flash" else "no flash" },
                    facts[Cameras.OIS]?.let { if (it == "true") "OIS" else "no OIS" },
                    facts[Cameras.FPS_MAX_VIDEO]?.let { "$it fps" },
                    facts[Cameras.ZOOM_RANGE]?.let { "zoom $it×" },
                ),
            )
            facts[Cameras.CAPABILITIES]?.takeIf { it != "none" }?.let { FactRow("capabilities", it) }
            facts[Cameras.PHYSICAL_IDS]?.let { FactRow("physical lenses", it) }
        }
    }
}
