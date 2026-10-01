package io.github.stronghorse44.tunnels.runtime

import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.model.TunnelModule

/**
 * Optional custom content for a tunnel. A [TunnelModule] that also implements this gets its content
 * rendered above the generic findings/observations sections of [TunnelScreen]; everything else gets
 * the generic screen only. Explore tunnels typically draw everything here and have no rules.
 */
interface TunnelUi {
    /** Shown above findings and observations. [state] carries the latest scan data. */
    @Composable
    fun Content(state: TunnelScreenState, actions: TunnelScreenActions)

    /** False hides the generic observations section (for tunnels that present their data themselves). */
    val showObservations: Boolean get() = true
}
