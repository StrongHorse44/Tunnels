package io.github.stronghorse44.tunnels.traffic

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.delay

private const val LIST_MAX = 60

/** Session card (START/STOP, live counters, plain-language refusals) and the per-app list of the last scan. */
@Composable
fun TrafficPanel(state: TunnelScreenState, actions: TunnelScreenActions) {
    val session by DnsVpnService.state.collectAsStateWithLifecycle()
    SessionCard(session, actions)
    Spacer(Modifier.height(10.dp))
    AppList(state.observations)
}

@Composable
private fun SessionCard(session: SessionState, actions: TunnelScreenActions) {
    val context = LocalContext.current
    val line = LineColors.of(MetroLine.NETWORK)
    var refusal by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Once a session ends, re-scan so the list below picks up what it recorded. The service flips
    // running only after its final rows are stored, so this scan sees the whole session.
    var wasRunning by remember { mutableStateOf(session.running) }
    LaunchedEffect(session.running) {
        if (wasRunning && !session.running) actions.scan()
        wasRunning = session.running
    }
    LaunchedEffect(session.running) {
        while (session.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) DnsVpnService.start(context) else refusal = "VPN consent was not given, so no session started."
    }
    fun startSession() {
        refusal = null
        // The other-VPN check comes first: asking the system for consent while another VPN is connected
        // would disconnect it (VpnStatus.consentIntent refuses to ask in that case as a second guard).
        if (VpnStatus.anyVpnActive(context)) {
            refusal = VpnStatus.OTHER_VPN_MESSAGE
            return
        }
        val intent = VpnStatus.consentIntent(context)
        if (intent != null) consent.launch(intent) else DnsVpnService.start(context)
    }

    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DNS logging session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            session.ending -> "Stopping, writing the summary"
                            session.running -> "Running for ${SessionFormat.elapsed(now - session.startedAt)} · stops in ${SessionFormat.remainingMinutes(session.startedAt, now, DnsVpnService.MAX_DURATION_MS)} min"
                            else -> "Not running"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.running) StatusColors.ok else GlassColors.dim,
                    )
                }
                if (session.running) {
                    OutlinedButton(onClick = { DnsVpnService.stop(context) }, enabled = !session.ending) { Text("STOP") }
                } else {
                    Button(onClick = { startSession() }) { Text("START") }
                }
            }
            if (session.running) {
                Text(SessionFormat.counters(session.totals), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = line)
                Text(
                    session.resolver?.let { "Forwarding to $it" } ?: "Waiting for a network to forward to",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassColors.dim,
                )
            } else {
                Text(
                    "Only DNS lookups enter the tunnel; all other traffic flows as usual. Per app, Tunnels keeps counts and " +
                        "registrable domain names, never the queries themselves. A session stops by itself after ${DnsVpnService.MAX_DURATION_MS / 60_000} minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
                if (session.totals.queries > 0) {
                    Text("Last session: ${SessionFormat.counters(session.totals)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                }
            }
            (refusal ?: session.message)?.let { msg ->
                val warn = refusal != null || msg.contains("Could not") || msg.contains("revoked") || msg == DnsVpnService.FORWARDER_FAILED_MESSAGE
                Text(msg, style = MaterialTheme.typography.bodySmall, color = if (warn) StatusColors.warn else GlassColors.dim)
            }
        }
    }
}

private class AppRow(val subject: String, val domains: Int, val queries: Int, val trackers: Int, val top: List<String>, val trackerTop: List<String>, val encrypted: Int)

@Composable
private fun AppList(observations: List<Observation>) {
    val context = LocalContext.current
    val rows = remember(observations) {
        observations.groupBy { it.subject }
            .filterKeys { it != TrafficKeys.SUMMARY }
            .map { (subject, obs) ->
                AppRow(
                    subject = subject,
                    domains = TrafficKeys.intValue(obs, TrafficKeys.DOMAINS30) ?: 0,
                    queries = TrafficKeys.intValue(obs, TrafficKeys.QUERIES30) ?: 0,
                    trackers = TrafficKeys.intValue(obs, TrafficKeys.TRACKER_DOMAINS30) ?: 0,
                    top = TrafficKeys.list(TrafficKeys.value(obs, TrafficKeys.TOP)),
                    trackerTop = TrafficKeys.list(TrafficKeys.value(obs, TrafficKeys.TRACKER_TOP)),
                    encrypted = TrafficKeys.intValue(obs, TrafficKeys.ENCRYPTED30) ?: 0,
                )
            }
            .sortedWith(compareByDescending<AppRow> { it.trackers }.thenByDescending { it.queries })
    }
    val summary = remember(observations) { observations.filter { it.subject == TrafficKeys.SUMMARY } }
    val sessions = TrafficKeys.intValue(summary, TrafficKeys.SESSIONS_COUNT30) ?: 0
    val labels = remember(rows) { mutableMapOf<String, String>() }
    val pm = context.packageManager
    fun label(subject: String): String = labels.getOrPut(subject) {
        if (!TrafficKeys.isPackageSubject(subject)) subject
        else runCatching { pm.getApplicationInfo(subject, 0).loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: subject
    }

    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Last 30 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (summary.isEmpty()) "Scan to read the recorded sessions."
                else "${SessionFormat.plural(sessions, "session", "sessions")} · ${SessionFormat.plural(rows.size, "app", "apps")} seen",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (summary.isNotEmpty() && rows.isEmpty()) {
                Text("No lookups recorded yet. Start a session, use the phone for a while, then scan.", style = MaterialTheme.typography.bodyMedium)
            }
            rows.take(LIST_MAX).forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label(row.subject), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (row.trackers > 0) {
                            Text("${row.trackers} tracker${if (row.trackers == 1) "" else "s"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StatusColors.warn)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("${row.queries}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = LineColors.of(MetroLine.NETWORK))
                    }
                    if (label(row.subject) != row.subject) Text(row.subject, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                    Text(
                        buildString {
                            append("${row.domains} domains")
                            if (row.top.isNotEmpty()) append(": ").append(row.top.joinToString(", "))
                            if (row.encrypted > 0) append(" · ${row.encrypted} encrypted DNS attempts")
                        },
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                    if (row.trackerTop.isNotEmpty()) {
                        Text("Trackers: ${row.trackerTop.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                    }
                }
            }
            if (rows.size > LIST_MAX) {
                Text("and ${rows.size - LIST_MAX} more", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
        }
    }
}
