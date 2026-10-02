package io.github.stronghorse44.tunnels

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.DepthBackground
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.StratumColors
import io.github.stronghorse44.tunnels.common.WellView
import io.github.stronghorse44.tunnels.common.rememberReducedMotion
import io.github.stronghorse44.tunnels.metro.Depth
import io.github.stronghorse44.tunnels.metro.Well
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo
import io.github.stronghorse44.tunnels.runtime.TunnelSummary
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.runtime.severityColor
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** Implicit, same-package action the snapshots module answers once it exists. */
const val SNAPSHOTS_ACTION = "io.github.stronghorse44.tunnels.action.SNAPSHOTS"

/**
 * Home, at the surface: looking down the stairwell. Each ring is a stratum lit in its band of the corridor's
 * light, each tunnel a bead on its ring, and the open-findings count sits in the glory at the bottom. Below,
 * the strata as rows of stations, then the device's status.
 */
@Composable
fun WellHome(
    onOpenTunnel: (String) -> Unit,
    onOpenUpdates: () -> Unit = {},
    onOpenFindings: () -> Unit = {},
    onOpenChecks: () -> Unit = {},
) {
    val context = LocalContext.current
    var placeholder by remember { mutableStateOf<TunnelInfo?>(null) }
    val netOn = networkAllowed(context)
    val version = remember { versionName(context) }
    val runtime by produceState<TunnelsRuntime?>(null) {
        value = runCatching { TunnelsRuntime.get(context) }.getOrNull()
    }
    val store = runtime?.store
    val summaries by produceState<Map<String, TunnelSummary>>(emptyMap(), store) {
        store?.dao?.findingCounts()?.collect { value = TunnelSummary.from(it) }
    }
    val keyLevel by produceState("…", store) {
        if (store != null) value = withContext(Dispatchers.IO) { TunnelsStore.keySecurityLevel() }
    }
    val lastInstall by produceState<EventEntity?>(null, store) {
        store?.events(TunnelCatalog.INSTALLER, 1)?.collect { value = it.firstOrNull() }
    }
    val lastUnzip by produceState<EventEntity?>(null, store) {
        store?.events(TunnelCatalog.UNZIP, 1)?.collect { value = it.firstOrNull() }
    }

    val liveIds = runtime?.registry?.modules?.keys.orEmpty()
    // Live when the catalog says it shipped or its module registered: a stale catalog entry can't hide a built tunnel.
    fun live(t: TunnelInfo) = t.isLive || t.id in liveIds
    fun enter(t: TunnelInfo) {
        if (live(t)) onOpenTunnel(t.id) else placeholder = t
    }
    fun count(t: TunnelInfo) = summaries[t.id]?.total ?: 0
    val openFindings = summaries.values.sumOf { it.total }

    DepthBackground(Depth.HOME) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TUNNELS", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp, color = GlassColors.text)
                Spacer(Modifier.weight(1f))
                Text(
                    (if (netOn) "net on" else "net off") + " · " + keyLevel.lowercase(),
                    fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.6.sp, color = GlassColors.dim,
                )
            }
            Spacer(Modifier.height(10.dp))
            HomeWell(summaries, ::live, ::enter, openFindings)
            Spacer(Modifier.height(6.dp))
            DepthGauge(::count, ::live, ::enter)
            Spacer(Modifier.height(12.dp))
            FindingsConsole(summaries.values, onOpenFindings, onOpenChecks)
            Spacer(Modifier.height(12.dp))
            Console(version, netOn, keyLevel, lastInstall, lastUnzip, onOpenUpdates)
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }

    placeholder?.let { t ->
        AlertDialog(
            onDismissRequest = { placeholder = null },
            title = { Text(t.title) },
            text = {
                Text(
                    "${t.blurb}.\n\nThis tunnel is still being dug. " +
                        (t.phase?.let { "It opens in phase $it." } ?: "It is not part of this build.") +
                        if (t.line == MetroLine.EXPLORE) "\n\nExplore is curiosity only: nothing here is a security finding." else "",
                    color = GlassColors.dim,
                )
            },
            confirmButton = { TextButton(onClick = { placeholder = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun HomeWell(summaries: Map<String, TunnelSummary>, live: (TunnelInfo) -> Boolean, open: (TunnelInfo) -> Unit, openFindings: Int) {
    val beads = remember { Well.beads(TunnelCatalog.all) }
    val measurer = rememberTextMeasurer()
    val still = rememberReducedMotion()
    val pulse by rememberInfiniteTransition(label = "beads").animateFloat(
        0.25f, 0.75f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "pulse",
    )
    val ink = GlassColors.void
    WellView(
        Modifier
            .fillMaxWidth()
            .pointerInput(beads) {
                detectTapGestures { p ->
                    val side = size.width.toFloat()
                    Well.hit(beads, p.x / side, p.y / side, 0.06f)?.let { open(it.tunnel) }
                }
            },
        overlay = { side ->
            val u = side / 260f
            Well.rings.forEach { ring ->
                val s = ring.stratum ?: return@forEach
                val layout = measurer.measure(
                    StratumColors.label(s).uppercase(),
                    TextStyle(fontFamily = Mono, fontSize = (6.5f * u).toSp(), letterSpacing = (1.1f * u).toSp(), fontWeight = FontWeight.Medium, color = ink),
                )
                drawText(layout, topLeft = Offset(ring.cx * side - layout.size.width / 2f, (ring.cy + ring.r) * side - 9.5f * u - layout.size.height))
            }
            beads.forEach { b ->
                val c = Offset(b.x * side, b.y * side)
                val r = Well.BEAD_RADIUS * side
                val summary = summaries[b.tunnel.id]
                val hot = (summary?.total ?: 0) > 0
                if (hot) {
                    val color = summary?.worst?.let(::severityColor) ?: Color.White
                    drawCircle(color.copy(alpha = if (still) 0.6f else pulse), r * 2.4f, c)
                }
                if (live(b.tunnel)) {
                    drawCircle(Color.White, if (hot) r * 1.2f else r * 0.8f, c)
                    drawCircle(ink, if (hot) r * 1.2f else r * 0.8f, c, style = Stroke((if (hot) 1.6f else 1f) * u))
                } else {
                    drawCircle(Color.White.copy(alpha = 0.7f), r * 0.8f, c, style = Stroke(1.2f * u))
                }
            }
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$openFindings", fontSize = 26.sp, fontWeight = FontWeight.Light, color = ink)
            Text(if (openFindings == 0) "CLEAR" else "OPEN", fontFamily = Mono, fontSize = 7.sp, letterSpacing = 1.4.sp, color = ink)
        }
    }
}

/**
 * The groups as a depth gauge: one line down the left, each group's stretch in its own colour shading into the
 * next, a stop at each group's name, and its stations hanging off the line as plain rows with their counts.
 */
@Composable
private fun DepthGauge(count: (TunnelInfo) -> Int, live: (TunnelInfo) -> Boolean, open: (TunnelInfo) -> Unit) {
    val groups = Well.STRATA + Stratum.EXPLORE
    Column(Modifier.fillMaxWidth()) {
        groups.forEachIndexed { i, s ->
            val next = groups.getOrNull(i + 1)?.let(StratumColors::of) ?: StratumColors.of(s)
            GaugeGroup(s, next, TunnelCatalog.all.filter { it.stratum == s }, count, live, open, last = i == groups.lastIndex)
        }
    }
}

private val GaugeX = 9.dp
private val Gutter = 28.dp

@Composable
private fun GaugeGroup(
    s: Stratum,
    next: Color,
    tunnels: List<TunnelInfo>,
    count: (TunnelInfo) -> Int,
    live: (TunnelInfo) -> Boolean,
    open: (TunnelInfo) -> Unit,
    last: Boolean,
) {
    val color = StratumColors.of(s)
    val side = s == Stratum.EXPLORE
    val total = tunnels.sumOf(count)
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val x = GaugeX.toPx()
            val top = 12.dp.toPx()
            val bottom = if (last) size.height - 10.dp.toPx() else size.height + top
            drawLine(
                Brush.verticalGradient(listOf(color, next), startY = top, endY = bottom), Offset(x, top), Offset(x, bottom),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (side) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        },
    ) {
        Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(Gutter).height(16.dp)) {
                val c = Offset(GaugeX.toPx(), size.height / 2f)
                drawCircle(color, 5.dp.toPx(), c)
                drawCircle(Color.White.copy(alpha = 0.85f), 5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
            Text(StratumColors.label(s).uppercase(), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.8.sp, color = color)
            Text(
                if (side) "  curiosity only" else if (total > 0) "  $total open" else "  clear",
                fontSize = 11.sp, color = GlassColors.dim,
            )
        }
        tunnels.forEach { t ->
            val n = count(t)
            val on = live(t)
            Row(
                Modifier.fillMaxWidth().clickable { open(t) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.width(Gutter).height(12.dp)) {
                    val y = size.height / 2f
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(GaugeX.toPx() + 4.dp.toPx(), y), Offset(size.width - 6.dp.toPx(), y), 1.dp.toPx())
                }
                Text(
                    t.title, fontSize = 14.sp, color = if (on) GlassColors.text else GlassColors.dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    if (n > 0) "$n" else "—", fontFamily = Mono, fontSize = 13.sp,
                    color = if (n > 0) GlassColors.text else GlassColors.dim.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Console(version: String, netOn: Boolean, keyLevel: String, lastInstall: EventEntity?, lastUnzip: EventEntity?, onOpenUpdates: () -> Unit) {
    val context = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ConsoleLine("build", "v$version")
            ConsoleLine("net", if (netOn) "on · sessions only" else "off")
            ConsoleLine("store", "SQLCipher · $keyLevel")
            lastInstall?.let { ConsoleLine("install", it.summary) }
            lastUnzip?.let { ConsoleLine("unzip", it.subject) }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenUpdates) { Text("update ›", fontFamily = Mono, color = StratumColors.of(Stratum.BEDROCK)) }
                TextButton(onClick = {
                    val intent = Intent(SNAPSHOTS_ACTION).setPackage(context.packageName)
                    if (context.packageManager.resolveActivity(intent, 0) != null) context.startActivity(intent)
                    else Toast.makeText(context, "Snapshots arrive with phase 1.", Toast.LENGTH_SHORT).show()
                }) { Text("snapshots ›", fontFamily = Mono, color = StratumColors.of(Stratum.CORE)) }
            }
        }
    }
}

@Composable
private fun ConsoleLine(key: String, value: String) {
    Row {
        Text(key, fontFamily = Mono, fontSize = 12.sp, color = GlassColors.dim, modifier = Modifier.width(64.dp))
        Text(value, fontFamily = Mono, fontSize = 12.sp, color = GlassColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
}.getOrNull() ?: "?"

/**
 * Whether this app may reach the network right now. INTERNET is declared only for the Traffic and Home
 * network sessions and the updater's checks (rule #1); GrapheneOS's Network toggle revokes it, which is what
 * this reads.
 */
private fun networkAllowed(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
