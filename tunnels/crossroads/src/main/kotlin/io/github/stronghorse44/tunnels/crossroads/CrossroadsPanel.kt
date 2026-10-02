package io.github.stronghorse44.tunnels.crossroads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.crossrules.CrossJoin
import io.github.stronghorse44.tunnels.crossrules.CrossKeys
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** What Crossroads joins, how old each source's data is, and a way to refresh a source. */
@Composable
fun CrossroadsPanel(state: TunnelScreenState) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    // When each source tunnel's newest data was taken, straight from the store (rescanned sources move often).
    val dates by produceState<Map<String, String>>(emptyMap(), state.lastScan) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dao = TunnelsRuntime.get(context).store.dao
                CrossKeys.Sources.ALL.associateWith { id ->
                    dao.latestSnapshotIdFor(id)?.let { dao.snapshot(it) }?.takenAt
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: CrossKeys.NONE
                }
            }.getOrDefault(emptyMap())
        }
    }
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.INSPECT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where the tunnels meet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Some risks only show when two tunnels agree: an accessibility service in an app from a file, ad SDKs in an app " +
                    "holding your location, a microphone used while the app sat unopened. Crossroads joins the latest data of the " +
                    "tunnels below and re-joins it whenever one of them is scanned. Each finding names the tunnels its evidence came from.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (state.lastScan == null) {
                Text("Scan to join what the other tunnels have found so far.", style = MaterialTheme.typography.bodyMedium)
            }
            CrossKeys.Sources.ALL.sortedBy { order.indexOf(it) }.forEach { id ->
                val title = TunnelCatalog.byId(id)?.title ?: id
                val day = dates[id]?.takeIf { it != CrossKeys.NONE }?.let(::parse)
                val maxDays = if (id == CrossKeys.Sources.TRAFFIC) CrossJoin.FRESH_TRAFFIC.toDays() else if (id in ACTIVITY) CrossJoin.FRESH_ACTIVITY.toDays() else null
                val age = day?.let { ChronoUnit.DAYS.between(it, today) }
                val stale = age != null && maxDays != null && age > maxDays
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                dates.isEmpty() -> "reading…"
                                day == null -> "never scanned: its facts are missing here"
                                stale -> "data from $day: too old to join, scan it again"
                                else -> "data from $day"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (day == null || stale) StatusColors.warn else GlassColors.dim,
                        )
                    }
                    TextButton(onClick = { context.startActivity(TunnelActivity.intent(context, id)) }) { Text("Open") }
                }
            }
        }
    }
}

private val order = listOf(
    CrossKeys.Sources.PERMISSIONS, CrossKeys.Sources.APK, CrossKeys.Sources.TRAFFIC, CrossKeys.Sources.TIMELINE, CrossKeys.Sources.DEEP,
)

private val ACTIVITY = setOf(CrossKeys.Sources.TIMELINE, CrossKeys.Sources.DEEP)

private fun parse(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (_: DateTimeParseException) {
    null
}
