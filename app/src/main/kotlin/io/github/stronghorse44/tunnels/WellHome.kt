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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import io.github.stronghorse44.tunnels.common.StatusColors
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
fun WellHome(onOpenTunnel: (String) -> Unit, onOpenUpdates: () -> Unit = {}) {
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
                Pill(if (netOn) "NET ON" else "NET OFF", if (netOn) StatusColors.info else StatusColors.ok)
                Spacer(Modifier.width(6.dp))
                Pill(keyLevel.uppercase(), null)
            }
            Spacer(Modifier.height(10.dp))
            HomeWell(summaries, ::live, ::enter, openFindings)
            Spacer(Modifier.height(6.dp))
            (Well.STRATA + Stratum.EXPLORE).forEach { s ->
                StratumRow(s, TunnelCatalog.all.filter { it.stratum == s }, ::count, ::live, ::enter)
            }
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
                    StratumColors.label(s),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StratumRow(
    s: Stratum,
    tunnels: List<TunnelInfo>,
    count: (TunnelInfo) -> Int,
    live: (TunnelInfo) -> Boolean,
    open: (TunnelInfo) -> Unit,
) {
    val color = StratumColors.of(s)
    val total = tunnels.sumOf(count)
    val side = s == Stratum.EXPLORE
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                val w = 3.dp.toPx()
                drawCircle(
                    color, size.minDimension / 2f - w / 2f,
                    style = Stroke(w, pathEffect = if (side) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(StratumColors.label(s), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.4.sp, color = color)
            if (side) Text("  · curiosity only", fontSize = 11.sp, color = GlassColors.dim)
            Spacer(Modifier.weight(1f))
            if (total > 0) {
                Text(
                    "$total", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GlassColors.void,
                    modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 8.dp, vertical = 1.dp),
                )
            } else {
                Text("—", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tunnels.forEach { t -> Station(t, count(t), live(t), color, side) { open(t) } }
        }
    }
}

@Composable
private fun Station(t: TunnelInfo, findings: Int, live: Boolean, color: Color, side: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (side || !live) Color.Transparent else color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = if (live) 0.6f else 0.3f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.title, fontSize = 12.sp, color = if (live) GlassColors.text else GlassColors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (findings > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$findings", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = GlassColors.void,
                modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, dot: Color?) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = GlassColors.text)
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
