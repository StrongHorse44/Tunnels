package io.github.stronghorse44.tunnels.emissions

import android.content.Context
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.deepmode.ShizukuShell
import io.github.stronghorse44.tunnels.deepmode.ShizukuStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Emissions: what the phone itself transmits (cellular, Wi-Fi and its scanning, Bluetooth and its scanning, NFC, UWB,
 * hotspot), how much of it is off, Silence and Restore through Shizuku or Android's own panels, the apps that used a
 * radio recently, a Faraday bag test and what full silence takes. A scan needs no permission; the bag test asks for
 * its two when it starts. Stores summaries only (states and counts, never an app, address or time).
 */
class EmissionsTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = EmissionsKeys.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = emptyList()
    override val rules: List<FindingRule> = EmissionsRules.all

    internal val shell = ShizukuShell(context)
    internal val silencer = Silencer(context, shell)
    internal val bagTest = BagTestRunner(context)

    private val _recentUse = MutableStateFlow<Map<RadioUse.Kind, List<RadioUse.Use>>?>(null)

    /** The apps that used a radio in the last scan, by kind, for the screen only. Held in memory, never stored. */
    internal val recentUse: StateFlow<Map<RadioUse.Kind, List<RadioUse.Use>>?> = _recentUse.asStateFlow()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        progress.report(0, 1, "transmitters")
        val probe = EmissionsProbe.read(context, shell, withApps = true)
        _recentUse.value = probe.recentUse
        progress.report(1, 1, "done")
        return EmissionsObservations.toObservations(probe.reading, id)
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val emitters = EmissionsRules.emittersFor(draft)
        // Cached: this runs for every finding on each emission of the findings flow, on the main thread.
        val granted = ShizukuStatus.cached(context).granted
        return buildList {
            if (granted && emitters.isNotEmpty()) {
                add(FindingAction.Perform("Turn off", destructive = true, reversible = true) { silencer.turnOff(emitters) })
            }
            addAll(EmissionsRules.settingsActions(draft))
        }
    }

    override val showObservations: Boolean get() = false

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        EmissionsPanel(this, state, actions)
    }
}
