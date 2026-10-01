package io.github.stronghorse44.tunnels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassBackground
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.glass
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** Home: a console readout over a metro map, each line in its own glass tube. */
@Composable
fun MetroHome(onOpenTunnel: (String) -> Unit) {
    val context = LocalContext.current
    var placeholder by remember { mutableStateOf<TunnelInfo?>(null) }
    val offline = remember { declaresNoInternet(context) }
    val version = remember { versionName(context) }
    val store by produceState<TunnelsStore?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { TunnelsStore.get(context) }.getOrNull() }
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

    fun status(t: TunnelInfo): String = when {
        t.id == TunnelCatalog.INSTALLER -> lastInstall?.let { "ready · last: ${it.summary}" } ?: "ready"
        t.id == TunnelCatalog.UNZIP -> lastUnzip?.let { "ready · last: ${it.subject}" } ?: "ready"
        t.line == MetroLine.EXPLORE -> "phase ${t.phase} · curiosity"
        else -> "phase ${t.phase} · under construction"
    }

    GlassBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.statusBarsPadding().height(8.dp))
                ConsoleHeader(version, offline, keyLevel, TunnelCatalog.all.count { it.isLive }, TunnelCatalog.all.count { !it.isLive })
            }
            items(MetroLine.entries) { line ->
                LineCard(line, TunnelCatalog.onLine(line), ::status) { t ->
                    if (t.isLive) onOpenTunnel(t.id) else placeholder = t
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
        }
    }

    placeholder?.let { t ->
        AlertDialog(
            onDismissRequest = { placeholder = null },
            containerColor = Color(0xFF0D121A),
            title = { Text(t.title) },
            text = {
                Text(
                    "${t.blurb}.\n\nStation under construction on the ${t.line.label} line. Opens in phase ${t.phase}.",
                    color = GlassColors.dim,
                )
            },
            confirmButton = { TextButton(onClick = { placeholder = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ConsoleHeader(version: String, offline: Boolean, keyLevel: String, live: Int, planned: Int) {
    GlassPanel(Modifier.fillMaxWidth(), tint = LineColors.of(MetroLine.FILES)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("TUNNELS", fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 7.sp, color = GlassColors.text)
            Spacer(Modifier.height(10.dp))
            ConsoleLine("build", "v$version")
            ConsoleLine("net", "NONE", if (offline) "[ok]" to StatusColors.ok else "[!!]" to StatusColors.blocker)
            ConsoleLine("store", "SQLCipher", "[$keyLevel]" to StatusColors.info)
            ConsoleLine("map", "$live live · $planned planned")
            BlinkingPrompt()
        }
    }
}

@Composable
private fun ConsoleLine(key: String, value: String, tag: Pair<String, Color>? = null) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = GlassColors.dim)) { append("> ") }
            withStyle(SpanStyle(color = GlassColors.text)) { append(key) }
            withStyle(SpanStyle(color = GlassColors.dim.copy(alpha = 0.5f))) { append(" " + ".".repeat((9 - key.length).coerceAtLeast(2)) + " ") }
            withStyle(SpanStyle(color = GlassColors.text)) { append(value) }
            if (tag != null) withStyle(SpanStyle(color = tag.second)) { append("  ${tag.first}") }
        },
        fontFamily = Mono,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BlinkingPrompt() {
    val phase by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "cursor",
    )
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = GlassColors.dim)) { append("> ") }
            withStyle(SpanStyle(color = LineColors.of(MetroLine.FILES).copy(alpha = if (phase < 0.5f) 1f else 0f))) { append("█") }
        },
        fontFamily = Mono,
        fontSize = 13.sp,
    )
}

@Composable
private fun LineCard(line: MetroLine, tunnels: List<TunnelInfo>, status: (TunnelInfo) -> String, onStation: (TunnelInfo) -> Unit) {
    val color = LineColors.of(line)
    GlassPanel(Modifier.fillMaxWidth(), tint = color) {
        Column(Modifier.padding(start = 6.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(Modifier.padding(start = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line.code,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier
                        .border(1.5.dp, color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("${line.label.uppercase()} LINE", fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = GlassColors.text)
                if (line == MetroLine.EXPLORE) {
                    Text("  curiosity only", fontFamily = Mono, fontSize = 11.sp, color = GlassColors.dim)
                }
                Spacer(Modifier.weight(1f))
                Text("${tunnels.count { it.isLive }}/${tunnels.size}", fontFamily = Mono, fontSize = 12.sp, color = GlassColors.dim)
            }
            tunnels.forEachIndexed { i, t ->
                StationRow(t, color, first = i == 0, last = i == tunnels.lastIndex, status = status(t)) { onStation(t) }
            }
        }
    }
}

@Composable
private fun StationRow(t: TunnelInfo, color: Color, first: Boolean, last: Boolean, status: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        TubeSegment(color, first, last, t.isLive, Modifier.width(44.dp).fillMaxHeight())
        Box(Modifier.weight(1f).padding(vertical = 5.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(50), if (t.isLive) color else Color.White)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        color = if (t.isLive) GlassColors.text else GlassColors.text.copy(alpha = 0.55f),
                        fontWeight = if (t.isLive) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        status,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = if (t.isLive) color else GlassColors.dim.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (t.isLive) Text("›", color = color, fontSize = 24.sp)
            }
        }
    }
}

/** One station's piece of the glass tube: glass body, colored liquid (dashed where unbuilt), and the station bead. */
@Composable
private fun TubeSegment(color: Color, first: Boolean, last: Boolean, live: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val top = Offset(cx, if (first) cy else 0f)
        val bottom = Offset(cx, if (last) cy else size.height)
        val glassW = 18.dp.toPx()
        val liquidW = 6.dp.toPx()
        val endCap = if (first || last) StrokeCap.Round else StrokeCap.Butt

        if (!(first && last)) {
            drawLine(Color.White.copy(alpha = 0.10f), top, bottom, glassW, endCap)
            if (live) drawLine(color.copy(alpha = 0.22f), top, bottom, liquidW * 2.4f, StrokeCap.Round)
            drawLine(
                color.copy(alpha = if (live) 0.95f else 0.45f), top, bottom, liquidW, StrokeCap.Round,
                pathEffect = if (live) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 6.dp.toPx())),
            )
            val sheen = Offset(-glassW * 0.28f, 0f)
            drawLine(Color.White.copy(alpha = 0.30f), top + sheen, bottom + sheen, 1.5.dp.toPx(), endCap)
        }

        val center = Offset(cx, cy)
        if (live) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center, 18.dp.toPx()), 18.dp.toPx(), center)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, color), center + Offset(-2.dp.toPx(), -2.dp.toPx()), 10.dp.toPx()),
                8.dp.toPx(),
                center,
            )
            drawCircle(Color.White.copy(alpha = 0.9f), 2.2.dp.toPx(), center + Offset(-2.5.dp.toPx(), -2.5.dp.toPx()))
        } else {
            drawCircle(GlassColors.void, 7.dp.toPx(), center)
            drawCircle(color.copy(alpha = 0.7f), 7.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
}.getOrNull() ?: "?"

private fun declaresNoInternet(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    )
    info.requestedPermissions?.contains(Manifest.permission.INTERNET) != true
}.getOrDefault(false)
