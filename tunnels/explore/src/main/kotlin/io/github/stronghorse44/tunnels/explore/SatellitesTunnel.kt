package io.github.stronghorse44.tunnels.explore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay

/**
 * Satellites: which GNSS constellations are overhead, how many satellites each shows and how strong
 * they are, from GnssStatus for up to 20 seconds. The GNSS engine only runs while something requests
 * location, so the scan requests updates and throws every position away unread. Explore line: no
 * rules, no actions.
 */
class SatellitesTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = Satellites.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = listOf(
        PermissionSpec(Manifest.permission.ACCESS_FINE_LOCATION, "GNSS status is a location API; no position is stored"),
        PermissionSpec(Manifest.permission.ACCESS_COARSE_LOCATION, "Android requires this next to precise location; no position is stored"),
    )
    override val rules: List<FindingRule> = emptyList()

    override fun actionsFor(draft: FindingDraft): List<FindingAction> = emptyList()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return Satellites.observations(GnssFacts(Satellites.AVAILABLE_NO_PROVIDER, 0, 0, emptyList()))
        val hardware = hardware(manager)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Satellites.observations(hardware.copy(available = Satellites.AVAILABLE_NO_PERMISSION))
        }
        if (runCatching { !manager.hasProvider(LocationManager.GPS_PROVIDER) }.getOrDefault(true)) {
            return Satellites.observations(hardware.copy(available = Satellites.AVAILABLE_NO_PROVIDER))
        }
        if (runCatching { !manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(true)) {
            return Satellites.observations(hardware.copy(available = Satellites.AVAILABLE_LOCATION_OFF))
        }

        var best: List<SatelliteSample> = emptyList()
        var updates = 0
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                updates++
                val samples = (0 until status.satelliteCount).map { i ->
                    SatelliteSample(status.getConstellationType(i), status.getCn0DbHz(i), status.usedInFix(i))
                }
                // Keep the richest status of the window.
                if (samples.size >= best.size) best = samples
            }
        }
        // Positions are received and dropped: only the satellite status is read.
        val listener = LocationListener { _: Location -> }
        val executor = context.mainExecutor
        try {
            manager.registerGnssStatusCallback(executor, callback)
            val request = LocationRequest.Builder(1_000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(LISTEN_MS + 2_000L).build()
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, executor, listener)
        } catch (_: SecurityException) {
            runCatching { manager.unregisterGnssStatusCallback(callback) }
            return Satellites.observations(hardware.copy(available = Satellites.AVAILABLE_NO_PERMISSION))
        } catch (e: Exception) {
            Log.w(TAG, "GNSS listen failed: ${e.javaClass.simpleName}")
            runCatching { manager.unregisterGnssStatusCallback(callback) }
            return Satellites.observations(hardware.copy(available = Satellites.AVAILABLE_FAILED))
        }
        val seconds = (LISTEN_MS / 1000).toInt()
        try {
            for (s in 1..seconds) {
                delay(1_000L)
                progress.report(s, seconds, if (best.isEmpty()) "listening for satellites" else "${best.size} satellites, ${best.count { it.usedInFix }} in fix")
            }
        } finally {
            runCatching { manager.removeUpdates(listener) }
            runCatching { manager.unregisterGnssStatusCallback(callback) }
        }
        progress.report(seconds, seconds, "done")
        val facts = hardware.copy(
            available = if (updates == 0) Satellites.AVAILABLE_NO_STATUS else Satellites.AVAILABLE_YES,
            listenSeconds = seconds,
            statusUpdates = updates,
            constellations = Satellites.summarise(best),
        )
        return Satellites.observations(facts)
    }

    /** Capabilities and hardware description need no permission and no listening. */
    private fun hardware(manager: LocationManager): GnssFacts {
        val caps = runCatching { manager.gnssCapabilities }.getOrNull()
        return GnssFacts(
            available = Satellites.AVAILABLE_FAILED,
            listenSeconds = 0,
            statusUpdates = 0,
            constellations = emptyList(),
            hasMeasurements = caps?.let { runCatching { it.hasMeasurements() }.getOrNull() },
            hasNavigationMessages = caps?.let { runCatching { it.hasNavigationMessages() }.getOrNull() },
            yearOfHardware = runCatching { manager.gnssYearOfHardware }.getOrNull(),
            hardwareModel = runCatching { manager.gnssHardwareModelName }.getOrNull(),
        )
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        SatellitesContent(state)
    }

    companion object {
        private const val TAG = "Satellites"
        const val LISTEN_MS = 20_000L
    }
}

@Composable
private fun SatellitesContent(state: TunnelScreenState) {
    val subjects = remember(state.observations) { ExploreFormat.bySubject(state.observations) }
    val summary = subjects[Satellites.SUMMARY].orEmpty()
    val constellations = remember(subjects) { subjects.filterKeys { it != Satellites.SUMMARY } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExploreNote()
        if (state.lastScan == null && summary.isEmpty()) {
            ExploreEmpty("Scan to listen for satellites for ${SatellitesTunnel.LISTEN_MS / 1000} seconds. Works best outdoors; no position is kept.")
            return@Column
        }
        if (state.scan.running) ExploreEmpty(state.scan.label.substringAfter(": ", state.scan.label))
        val available = summary[Satellites.AVAILABLE]
        if (available != null && available != Satellites.AVAILABLE_YES) {
            ExploreEmpty(
                when (available) {
                    Satellites.AVAILABLE_NO_STATUS -> "No satellite status arrived in ${summary[Satellites.LISTEN_SECONDS]} seconds. Indoors the sky is out of reach; try near a window or outside."
                    Satellites.AVAILABLE_LOCATION_OFF -> "Location is off. GNSS only runs while location is enabled."
                    else -> "GNSS unavailable: $available."
                },
            )
        }
        val ordered = constellations.entries.sortedBy { Satellites.constellationNames.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
        ordered.forEach { (name, facts) ->
            GlassPanel(Modifier.fillMaxWidth(), tint = exploreTint) {
                CardColumn {
                    CardTitle(name, "${facts[Satellites.SATS_USED] ?: 0}/${facts[Satellites.SATS_VISIBLE] ?: 0} in fix")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("C/N0 avg ${facts[Satellites.CN0_AVG]} dB-Hz", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                        Text("max ${facts[Satellites.CN0_MAX]} dB-Hz", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = exploreTint)
                    }
                }
            }
        }
        if (summary.isNotEmpty()) {
            GlassPanel(Modifier.fillMaxWidth()) {
                CardColumn {
                    CardTitle("Receiver", "${summary[Satellites.SATS_VISIBLE] ?: 0} satellites")
                    summary[Satellites.HARDWARE_MODEL]?.let { FactRow("model", it) }
                    summary[Satellites.YEAR_OF_HARDWARE]?.let { FactRow("hardware year", it) }
                    summary[Satellites.CAPABILITIES]?.let { FactRow("capabilities", it.replace(",", ", ")) }
                    summary[Satellites.STATUS_UPDATES]?.let { FactRow("status updates", "$it in ${summary[Satellites.LISTEN_SECONDS]} s") }
                }
            }
        }
    }
}
