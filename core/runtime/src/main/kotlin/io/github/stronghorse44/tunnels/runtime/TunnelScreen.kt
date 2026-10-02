package io.github.stronghorse44.tunnels.runtime

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import io.github.stronghorse44.tunnels.common.PrismButton
import io.github.stronghorse44.tunnels.common.drawBeadDrop
import io.github.stronghorse44.tunnels.common.rememberReducedMotion
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.stronghorse44.tunnels.common.DepthBackground
import io.github.stronghorse44.tunnels.common.GloryScan
import io.github.stronghorse44.tunnels.common.SpectrumButton
import io.github.stronghorse44.tunnels.common.StratumColors
import io.github.stronghorse44.tunnels.common.WellView
import io.github.stronghorse44.tunnels.metro.Depth
import io.github.stronghorse44.tunnels.model.Stratum
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

/** How long the bead takes to lift off the band, fall and splash when a scan starts. */
private const val DROP_MILLIS = 1_100

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
    val title = info?.title ?: tunnelId
    val module = state.module
    var openFinding by remember { mutableStateOf<String?>(null) }
    // Where the tunnel's bead sits on the header band and where the scan's glory will bloom, in root coordinates.
    var beadAt by remember { mutableStateOf<Offset?>(null) }
    var gloryAt by remember { mutableStateOf<Offset?>(null) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    val still = rememberReducedMotion()
    val drop = remember { Animatable(1f) }
    LaunchedEffect(state.scan.running) {
        if (state.scan.running && !still) {
            drop.snapTo(0f)
            drop.animateTo(1f, tween(DROP_MILLIS, easing = LinearEasing))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        TunnelScaffold(title, line, onBack, stratum = info?.stratum, tunnelId = tunnelId, onLitBead = { beadAt = it }) { padding ->
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
                        item { ScanPanel(state, title, info?.stratum, line, vm::scan, onGlory = { gloryAt = it }) }
                        if (ui != null) item { ui.Content(state, vm) }
                        if (state.findings.isNotEmpty()) {
                            item { SectionTitle("Findings", state.findings.size) }
                            items(state.findings, key = { it.id }) { f ->
                                FindingCard(f, vm::perform, { vm.dismiss(f) }, onOpen = { openFinding = f.id })
                            }
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

        // The scan opens with the tunnel's bead falling from the band into the water where the glory blooms.
        val from = beadAt
        val to = gloryAt
        if (from != null && to != null && drop.value < 1f) {
            Canvas(Modifier.fillMaxSize()) { drawBeadDrop(from - rootAt, to - rootAt, drop.value) }
        }

        // A finding opens one level further down, in darker water than its tunnel.
        val index = state.findings.indexOfFirst { it.id == openFinding }
        if (index >= 0) {
            BackHandler { openFinding = null }
            FindingDetail(
                f = state.findings[index],
                position = index,
                count = state.findings.size,
                tunnelTitle = title,
                depth = Depth.ofTunnel(info?.stratum) + 1f,
                onAction = vm::perform,
                onDismiss = { vm.dismiss(state.findings[index]); openFinding = null },
                onStep = { step -> openFinding = state.findings[(index + step).mod(state.findings.size)].id },
                onBack = { openFinding = null },
            )
        }
    }
}

@Composable
private fun ScanPanel(
    state: TunnelScreenState,
    title: String,
    stratum: Stratum?,
    line: MetroLine,
    onScan: () -> Unit,
    onGlory: (Offset) -> Unit,
) {
    // The panel fades as the bead falls and the glory takes its place; the list below eases down to make room.
    Crossfade(state.scan.running, Modifier.animateContentSize(tween(600)), animationSpec = tween(450), label = "scan") { running ->
        if (running) ScanningGlory(state, title, onGlory) else ScanControls(state, stratum, line, onScan)
    }
}

@Composable
private fun ScanningGlory(state: TunnelScreenState, title: String, onGlory: (Offset) -> Unit) {
    val sc = state.scan
    // Counts are the tunnel's own items (apps, libraries), not tunnels; once all are read the scan is still saving.
    val now = when {
        sc.itemsTotal > 0 && sc.itemsDone >= sc.itemsTotal -> "saving"
        sc.item.isNotBlank() -> sc.item
        else -> "starting"
    }
    GloryScan(sc.itemsDone, sc.itemsTotal, "${title.lowercase()} · $now", enterDelayMillis = (DROP_MILLIS * 0.72f).toInt(), onCenter = onGlory)
}

@Composable
private fun ScanControls(state: TunnelScreenState, stratum: Stratum?, line: MetroLine, onScan: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val color = if (stratum != null) StratumColors.of(stratum) else LineColors.of(line)
    GlassPanel(Modifier.fillMaxWidth(), tint = color) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.module?.info?.blurb ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.lastScan?.let { "Last scan ${fmt.format(Date(it.toEpochMilli()))}" } ?: "Not scanned yet",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                PrismButton("Scan", onScan)
            }
            state.lastResult?.let { r ->
                Text(
                    "${r.observations} observations · ${r.newFindings} new findings · ${r.clearedFindings} cleared",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim,
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

private fun kindLabel(kind: String) = kind.lowercase().replace('_', ' ')

private fun isDestructive(a: FindingAction) = a is FindingAction.RequestUninstall || (a is FindingAction.Perform && a.destructive)

/**
 * A finding in a list: what and how bad, its first action, and the way to the rest. With [onOpen] (a tunnel's own
 * list) that leads down to the full finding; without it (the inbox, an app's page) the card opens in place, with the
 * whole evidence and every action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindingCard(f: Finding, onAction: (FindingAction) -> Unit, onDismiss: () -> Unit, onOpen: (() -> Unit)? = null) {
    val color = severityColor(f.severity)
    var expanded by remember(f.id) { mutableStateOf(false) }
    val open = onOpen ?: { expanded = !expanded }
    GlassPanel(Modifier.fillMaxWidth().animateContentSize(), tint = color) {
        Column(Modifier.clickable(onClick = open).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).border(3.dp, color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(f.severity.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
                Spacer(Modifier.width(8.dp))
                Text(kindLabel(f.kind), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                Spacer(Modifier.weight(1f))
                if (f.sticky) TextButton(onClick = onDismiss) { Text("Dismiss", color = GlassColors.dim) }
            }
            Text(f.subject, style = MaterialTheme.typography.titleSmall)
            Text(
                f.evidence,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.actions.forEachIndexed { i, a ->
                        when {
                            isDestructive(a) -> OutlinedButton(
                                onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth(), shape = CircleShape,
                                border = BorderStroke(1.dp, StatusColors.blocker.copy(alpha = 0.7f)),
                            ) { Text(a.label, color = Color(0xFFFFB3C0)) }
                            i == 0 -> SpectrumButton(a.label, { onAction(a) }, Modifier.fillMaxWidth())
                            else -> PrismButton(a.label, { onAction(a) }, Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = { expanded = false }, modifier = Modifier.fillMaxWidth()) { Text("Less ‹", color = GlassColors.dim) }
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    f.actions.firstOrNull()?.let { a -> PrismButton(a.label, { onAction(a) }) }
                    TextButton(onClick = open) {
                        val more = f.actions.size - 1
                        Text(if (more > 0) "$more more action${if (more == 1) "" else "s"} ›" else "Details ›", color = GlassColors.text)
                    }
                }
            }
        }
    }
}

/**
 * One finding, full screen, one level below its tunnel: the subject sits in the glory at the bottom of the
 * well, then what was found, the evidence, and every action, strongest first.
 */
@Composable
private fun FindingDetail(
    f: Finding,
    position: Int,
    count: Int,
    tunnelTitle: String,
    depth: Float,
    onAction: (FindingAction) -> Unit,
    onDismiss: () -> Unit,
    onStep: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appPage = remember(f.subject) { AppPage.isApp(f.subject) && AppPage.available(context) }
    val color = severityColor(f.severity)
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    DepthBackground(depth) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to $tunnelTitle", tint = GlassColors.text) }
                Text(tunnelTitle.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.4.sp, color = GlassColors.dim, modifier = Modifier.weight(1f))
                if (count > 1) {
                    TextButton(onClick = { onStep(-1) }) { Text("‹", color = GlassColors.text) }
                    Text("${position + 1} / $count", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                    TextButton(onClick = { onStep(1) }) { Text("›", color = GlassColors.text) }
                }
            }
            WellView(Modifier.fillMaxWidth(0.6f)) {
                Box(
                    Modifier.fillMaxSize(0.62f).clip(RoundedCornerShape(30)).background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        f.subject.substringAfterLast('.').take(1).uppercase().ifBlank { "?" },
                        fontWeight = FontWeight.Black, fontSize = 20.sp, color = GlassColors.void,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${f.severity.name} · ${kindLabel(f.kind).uppercase()}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.4.sp, color = GlassColors.void,
                modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(f.subject, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(f.evidence, style = MaterialTheme.typography.bodyMedium, color = GlassColors.text.copy(alpha = 0.88f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth()) {
                Ledger("first seen", fmt.format(Date(f.firstSeen.toEpochMilli())))
                Ledger("last seen", fmt.format(Date(f.lastSeen.toEpochMilli())))
                Ledger("tunnel", tunnelTitle)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                f.actions.forEachIndexed { i, a ->
                    when {
                        i == 0 && !isDestructive(a) -> SpectrumButton(a.label, { onAction(a) }, Modifier.fillMaxWidth())
                        isDestructive(a) -> OutlinedButton(
                            onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth(), shape = CircleShape,
                            border = BorderStroke(1.dp, StatusColors.blocker.copy(alpha = 0.7f)),
                        ) { Text(a.label, color = Color(0xFFFFB3C0)) }
                        else -> PrismButton(a.label, { onAction(a) }, Modifier.fillMaxWidth())
                    }
                }
                if (appPage) {
                    TextButton(onClick = { AppPage.open(context, f.subject) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Everything about this app ›", color = GlassColors.text)
                    }
                }
                if (f.sticky) TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss", color = GlassColors.dim) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Ledger(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.weight(0.4f))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
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
