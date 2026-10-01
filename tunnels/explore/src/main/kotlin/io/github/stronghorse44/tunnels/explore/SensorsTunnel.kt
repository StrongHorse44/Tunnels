package io.github.stronghorse44.tunnels.explore

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
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
 * Sensors: every sensor SensorManager lists, with the hardware's own description of each (type, vendor,
 * power, range, wake-up, FIFO). Listing needs no permission and reads no sensor values. Explore line: no
 * rules, no actions.
 */
class SensorsTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = Sensors.TUNNEL_ID
    override val requiredPermissions: List<PermissionSpec> = emptyList()
    override val rules: List<FindingRule> = emptyList()

    override fun actionsFor(draft: FindingDraft): List<FindingAction> = emptyList()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensors = manager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        val facts = ArrayList<SensorFacts>(sensors.size)
        sensors.forEachIndexed { i, sensor ->
            progress.report(i, sensors.size, sensor.name ?: "sensor")
            // One odd HAL entry must not hide the rest.
            runCatching { sensor.facts() }.onSuccess(facts::add)
        }
        progress.report(sensors.size, sensors.size, "done")
        return Sensors.observations(facts)
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        SensorsContent(state)
    }
}

/** Copies the description out of the platform object; no sensor is registered or read. */
internal fun Sensor.facts(): SensorFacts = SensorFacts(
    name = name.orEmpty(),
    type = type,
    stringType = stringType.orEmpty(),
    vendor = vendor.orEmpty(),
    version = version,
    powerMa = power,
    resolution = resolution,
    maxRange = maximumRange,
    minDelayUs = minDelay,
    wakeUp = isWakeUpSensor,
    reportingMode = reportingMode,
    dynamic = isDynamicSensor,
    fifoMax = fifoMaxEventCount,
)

@Composable
private fun SensorsContent(state: TunnelScreenState) {
    val subjects = remember(state.observations) { ExploreFormat.bySubject(state.observations) }
    val summary = subjects[Sensors.SUMMARY].orEmpty()
    val sensors = remember(subjects) { subjects.filterKeys { it != Sensors.SUMMARY } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExploreNote()
        if (sensors.isEmpty()) {
            ExploreEmpty(if (state.lastScan == null) "Scan to list every sensor on this device." else "This device lists no sensors.")
            return@Column
        }
        val grouped = sensors.entries.groupBy { SensorCategory.parse(it.value[Sensors.CATEGORY]) }
        SensorCategory.entries.forEach { category ->
            val items = grouped[category] ?: return@forEach
            GlassPanel(Modifier.fillMaxWidth(), tint = exploreTint) {
                CardColumn {
                    CardTitle(category.label, "${items.size}")
                    items.sortedBy { it.key }.forEach { (name, facts) -> SensorRow(name, facts) }
                }
            }
        }
        val wake = summary[Sensors.WAKE_UP_COUNT]
        val skipped = summary[Sensors.SKIPPED]
        FactLine(
            listOfNotNull(
                summary[Sensors.COUNT]?.let { "$it sensors" },
                wake?.let { "$it wake-up" },
                summary[Sensors.DYNAMIC_COUNT]?.takeIf { it != "0" }?.let { "$it dynamic" },
                skipped?.let { "$it more not listed" },
            ),
        )
    }
}

@Composable
private fun SensorRow(name: String, facts: Map<String, String>) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (facts[Sensors.WAKE_UP] == "true") Text("wake-up", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = exploreTint)
        }
        FactLine(
            listOfNotNull(
                facts[Sensors.TYPE]?.let(Sensors::shortType),
                facts[Sensors.VENDOR],
                facts[Sensors.POWER]?.let { "$it mA" },
            ),
            color = GlassColors.dim,
        )
    }
}
