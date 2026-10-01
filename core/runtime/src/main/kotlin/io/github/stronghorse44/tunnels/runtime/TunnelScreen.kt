package io.github.stronghorse44.tunnels.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import java.text.DateFormat
import java.util.Date

fun severityColor(s: Severity): Color = when (s) {
    Severity.INFO -> StatusColors.info
    Severity.NOTICE -> StatusColors.ok
    Severity.WARN -> StatusColors.warn
    Severity.CRITICAL -> StatusColors.blocker
}

/** The generic tunnel screen: gate, scan controls, custom content, findings with actions, observations by subject. */
@Composable
fun TunnelScreen(vm: TunnelViewModel, tunnelId: String, onBack: () -> Unit) {
    LaunchedEffect(tunnelId) { vm.open(tunnelId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val info = TunnelCatalog.byId(tunnelId)
    val line = info?.line ?: MetroLine.FILES
    val module = state.module

    TunnelScaffold(info?.title ?: tunnelId, line, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (module == null) {
                Text(state.error ?: "Opening…", Modifier.padding(16.dp), color = GlassColors.dim)
                return@TunnelScaffold
            }
            TunnelGate(module) {
                val ui = module as? TunnelUi
                val grouped = remember(state.observations) { state.observations.groupBy { it.subject }.toSortedMap() }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { ScanPanel(state, line, vm::scan) }
                    if (ui != null) item { ui.Content(state, vm) }
                    if (state.findings.isNotEmpty()) {
                        item { SectionTitle("Findings", state.findings.size) }
                        items(state.findings, key = { it.id }) { f -> FindingCard(f, vm::perform, { vm.dismiss(f) }) }
                    } else if (state.lastScan != null && module.rules.isNotEmpty()) {
                        item { Text("No findings.", color = StatusColors.ok, modifier = Modifier.padding(horizontal = 6.dp)) }
                    }
                    if (ui?.showObservations != false && grouped.isNotEmpty()) {
                        item { SectionTitle("Observations", grouped.size) }
                        items(grouped.entries.toList(), key = { it.key }) { (subject, obs) -> SubjectCard(subject, obs, line) }
                    }
                }
            }
            state.message?.let { msg ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp), action = { TextButton(onClick = vm::clearMessage) { Text("OK") } }) { Text(msg) }
            }
        }
    }
}

@Composable
private fun ScanPanel(state: TunnelScreenState, line: MetroLine, onScan: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.module?.info?.blurb ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.lastScan?.let { "Last scan ${fmt.format(Date(it.toEpochMilli()))}" } ?: "Not scanned yet",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                Button(onClick = onScan, enabled = !state.scan.running) { Text(if (state.scan.running) "Scanning…" else "Scan") }
            }
            if (state.scan.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.scan.label, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            }
            state.lastResult?.let { r ->
                if (!state.scan.running) Text(
                    "${r.observations} observations · ${r.newFindings} new findings · ${r.clearedFindings} cleared",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LineColors.of(line),
                )
            }
            state.error?.let { Text(it, color = StatusColors.blocker, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Text("$title · $count", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GlassColors.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindingCard(f: Finding, onAction: (FindingAction) -> Unit, onDismiss: () -> Unit) {
    val color = severityColor(f.severity)
    GlassPanel(Modifier.fillMaxWidth(), tint = color) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(f.severity.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
                Spacer(Modifier.width(8.dp))
                Text(f.kind.lowercase().replace('_', ' '), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                Spacer(Modifier.weight(1f))
                if (f.sticky) TextButton(onClick = onDismiss) { Text("Dismiss", color = GlassColors.dim) }
            }
            Text(f.subject, style = MaterialTheme.typography.titleSmall)
            Text(f.evidence, style = MaterialTheme.typography.bodyMedium)
            // Every action stays reachable (deep mode offers several per finding); they wrap onto more rows.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                f.actions.forEachIndexed { i, a ->
                    if (i == 0) Button(onClick = { onAction(a) }) { Text(a.label) }
                    else OutlinedButton(onClick = { onAction(a) }) { Text(a.label) }
                }
            }
        }
    }
}

@Composable
private fun SubjectCard(subject: String, obs: List<Observation>, line: MetroLine) {
    var open by remember { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subject, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${obs.size}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LineColors.of(line))
                Text(if (open) "  ▾" else "  ▸", color = GlassColors.dim)
            }
            if (open) {
                Spacer(Modifier.height(4.dp))
                obs.sortedBy { it.key }.forEach { o ->
                    Row {
                        Text(o.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.weight(0.45f))
                        Text(o.value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }
}
