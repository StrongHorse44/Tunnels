package io.github.stronghorse44.tunnels.traffic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.stronghorse44.tunnels.dns.BlockPolicy
import io.github.stronghorse44.tunnels.dns.Blocklists
import io.github.stronghorse44.tunnels.dns.TrackerDomains
import io.github.stronghorse44.tunnels.dns.TrackerKind
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.dns.Upstream
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.runtime.Choice
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIST_MAX = 60

/** Session card (START/STOP, live counters, plain-language refusals), blocking, and the per-app list of the last scan. */
@Composable
fun TrafficPanel(state: TunnelScreenState, actions: TunnelScreenActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by DnsVpnService.state.collectAsStateWithLifecycle()
    val policy by DnsVpnService.policy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // The stored choice, read once; a running session loaded the same value when it started.
        withContext(Dispatchers.IO) { runCatching { TunnelsStore.get(context).setting(BlockPolicy.KEY) }.getOrNull() }
            ?.let { DnsVpnService.setPolicy(BlockPolicy.decode(it)) }
    }
    val upstream by DnsVpnService.upstream.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { TunnelsStore.get(context).setting(Upstream.KEY) }.getOrNull() }
            ?.let { DnsVpnService.setUpstream(Upstream.decode(it)) }
    }
    fun saveUpstream(u: Upstream) {
        DnsVpnService.setUpstream(u)
        scope.launch(Dispatchers.IO) { runCatching { TunnelsStore.get(context).putSetting(Upstream.KEY, u.encode()) } }
    }
    fun save(p: BlockPolicy) {
        DnsVpnService.setPolicy(p)
        scope.launch(Dispatchers.IO) { runCatching { TunnelsStore.get(context).putSetting(BlockPolicy.KEY, p.encode()) } }
    }
    SessionCard(session, actions)
    Spacer(Modifier.height(10.dp))
    BlockingCard(session, policy, ::save)
    Spacer(Modifier.height(10.dp))
    EncryptionCard(session, upstream, ::saveUpstream)
    Spacer(Modifier.height(10.dp))
    AppList(state.observations, policy, ::save)
}

/** Whether sessions block tracker lookups, which kinds, which apps are let through, and what can stop it working. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockingCard(session: SessionState, policy: BlockPolicy, onChange: (BlockPolicy) -> Unit) {
    val context = LocalContext.current
    val line = LineColors.of(MetroLine.NETWORK)
    val privateDns = remember(session.running) { VpnStatus.privateDnsHost(context) }
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Block tracker lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (!policy.enabled) "Off"
                        else "On during sessions: " + TrackerKind.entries.filter { it in policy.kinds }.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (policy.enabled) StatusColors.ok else GlassColors.dim,
                    )
                }
                Switch(checked = policy.enabled, onCheckedChange = { onChange(policy.copy(enabled = it)) })
            }
            Text(
                "During a session, a lookup of a known tracking domain gets a \"no such domain\" answer from Tunnels instead of " +
                    "going out, so the app's ads, analytics or crash reports fail while the app itself keeps working. Only " +
                    "during sessions you start; outside one, nothing is blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            if (policy.enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackerKind.entries.forEach { kind ->
                        val on = kind in policy.kinds
                        Choice(kind.label, on, line, { onChange(policy.copy(kinds = if (on) policy.kinds - kind else policy.kinds + kind)) })
                    }
                }
                Blocklists.ALL.forEach { list ->
                    val on = list.id in policy.lists
                    val count = remember(on) { if (on) BundledLists.get(context, list.id).size else 0 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also block the ${list.name} list", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                (if (on && count > 0) "$count " else "") + "${list.description}, shipped with this version · ${list.license}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassColors.dim,
                            )
                        }
                        Switch(checked = on, onCheckedChange = { onChange(policy.copy(lists = if (it) policy.lists + list.id else policy.lists - list.id)) })
                    }
                }
                BlockPolicy.CAVEATS.filterKeys { it in policy.kinds }.forEach { (kind, caveat) ->
                    Text("Blocking ${kind.label} $caveat.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                }
                if (policy.exempt.isNotEmpty()) {
                    Text("Let through: " + policy.exempt.sorted().joinToString(", ") { appLabel(context, it) }, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                }
            }
            if (policy.strict.isNotEmpty()) {
                Text(
                    "Everything blocked for: " + policy.strict.sorted().joinToString(", ") { appLabel(context, it) } +
                        if (policy.enabled) "" else " (even with blocking off)",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
            }
            privateDns?.let { host ->
                Text(
                    "Private DNS is set to $host: lookups go there encrypted and skip the session, so nothing is counted or " +
                        "blocked. Set Private DNS to Automatic in Network settings before a session, and choose $host under " +
                        "Encrypt forwarded lookups to keep them encrypted while it runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn,
                )
            }
        }
    }
}

/** Where forwarded lookups go: the network's resolver in plain text, or a DNS-over-HTTPS provider the user picks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncryptionCard(session: SessionState, upstream: Upstream, onChange: (Upstream) -> Unit) {
    val context = LocalContext.current
    val line = LineColors.of(MetroLine.NETWORK)
    val privateDns = remember(session.running) { VpnStatus.privateDnsHost(context) }
    // Offered while Private DNS still names a host; once picked it is stored as a custom endpoint and survives the switch to Automatic.
    val fromPrivateDns = remember(privateDns) { privateDns?.let(Upstream::fromPrivateDns) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(if (upstream.provider == Upstream.CUSTOM) upstream.url.orEmpty() else "") }
    var invalid by remember { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Encrypt forwarded lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Sent to " + upstream.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (upstream.encrypted) StatusColors.ok else GlassColors.dim,
            )
            Text(
                "A session forwards every lookup it does not block. Pick a provider and they go out over HTTPS to it, so your " +
                    "network only sees encrypted traffic to that provider; if it stops answering, the session ends rather than " +
                    "falling back to plain text. Android still needs Private DNS on Automatic during a session.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Network (unencrypted)", !upstream.encrypted, line, { editing = false; onChange(Upstream()) })
                Upstream.PRESETS.forEach { preset ->
                    Choice(preset.name, upstream.provider == preset.id, line, { editing = false; Upstream.preset(preset.id)?.let(onChange) })
                }
                fromPrivateDns?.let { u ->
                    Choice("Private DNS: ${Upstream.hostOf(u.url)}", upstream == u, line, { editing = false; onChange(u) })
                }
                Choice("Custom…", editing || (upstream.provider == Upstream.CUSTOM && upstream != fromPrivateDns), line, { editing = true })
            }
            Upstream.PRESETS.firstOrNull { it.id == upstream.provider }?.let { preset ->
                Text("${preset.name}: ${preset.note}.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; invalid = false },
                    label = { Text("DNS-over-HTTPS URL") },
                    placeholder = { Text("https://dns.example.net/dns-query") },
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) Text("Needs an https:// address without a query string.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        val u = Upstream.custom(draft)
                        if (u == null) invalid = true else { onChange(u); editing = false }
                    }) { Text("Use") }
                }
            }
        }
    }
}

private fun appLabel(context: android.content.Context, subject: String): String =
    if (!TrafficKeys.isPackageSubject(subject)) TrafficKeys.systemUidLabel(subject) ?: subject
    else runCatching { context.packageManager.getApplicationInfo(subject, 0).loadLabel(context.packageManager).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: subject

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
        if (!VpnStatus.networkAllowed(context)) {
            refusal = VpnStatus.NETWORK_OFF_MESSAGE
            return
        }
        // The other-VPN check comes first: asking the system for consent while another VPN is connected
        // would disconnect it (VpnStatus.consentIntent refuses to ask in that case as a second guard).
        if (VpnStatus.anyVpnActive(context)) {
            refusal = VpnStatus.otherVpnMessage(context)
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
                val networkOff = msg == VpnStatus.NETWORK_OFF_MESSAGE
                val warn = refusal != null || networkOff || msg.contains("Could not") || msg.contains("revoked") ||
                    msg.contains("stopped answering") || msg == DnsVpnService.FORWARDER_FAILED_MESSAGE
                Text(msg, style = MaterialTheme.typography.bodySmall, color = if (warn) StatusColors.warn else GlassColors.dim)
                if (networkOff) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Open Tunnels' app info") }
                }
            }
        }
    }
}

private class AppRow(val subject: String, val domains: Int, val queries: Int, val trackers: Int, val top: List<String>, val trackerTop: List<String>, val encrypted: Int, val blocked: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppList(observations: List<Observation>, policy: BlockPolicy, onChange: (BlockPolicy) -> Unit) {
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
                    blocked = TrafficKeys.intValue(obs, TrafficKeys.BLOCKED30) ?: 0,
                )
            }
            .sortedWith(compareByDescending<AppRow> { it.trackers }.thenByDescending { it.queries })
    }
    val summary = remember(observations) { observations.filter { it.subject == TrafficKeys.SUMMARY } }
    val sessions = TrafficKeys.intValue(summary, TrafficKeys.SESSIONS_COUNT30) ?: 0
    val labels = remember(rows) { mutableMapOf<String, String>() }
    val pm = context.packageManager
    fun label(subject: String): String = labels.getOrPut(subject) {
        if (!TrafficKeys.isPackageSubject(subject)) TrafficKeys.systemUidLabel(subject) ?: subject
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
                        val companies = TrackerDomains.companies(row.trackerTop)
                        val unnamed = row.trackerTop.size - companies.sumOf { it.second.size }
                        Text(
                            "Trackers: " + companies.joinToString("; ") { (company, domains) -> "$company (${domains.joinToString(", ")})" } +
                                (if (unnamed > 0) "; $unnamed more" else "") +
                                if (row.blocked > 0) " · ${row.blocked} lookups blocked" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.warn,
                        )
                    }
                    if (row.trackers > 0 && TrafficKeys.isPackageSubject(row.subject)) {
                        val all = row.subject in policy.strict
                        val exempt = row.subject in policy.exempt
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onChange(policy.blockingAll(row.subject, !all)) }) {
                                Text(if (all) "Stop blocking everything" else "Block all its trackers")
                            }
                            if (policy.enabled && !all) {
                                TextButton(onClick = { onChange(policy.exempting(row.subject, !exempt)) }) {
                                    Text(if (exempt) "Block its trackers again" else "Let its trackers through (if it breaks)")
                                }
                            }
                        }
                    }
                }
            }
            if (rows.size > LIST_MAX) {
                Text("and ${rows.size - LIST_MAX} more", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
        }
    }
}
