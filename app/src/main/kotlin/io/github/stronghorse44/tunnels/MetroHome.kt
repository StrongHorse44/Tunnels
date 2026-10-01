package io.github.stronghorse44.tunnels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.runtime.TunnelSummary
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** Home: a console readout above a clickable glass metro map. */
@Composable
fun MetroHome(onOpenTunnel: (String) -> Unit) {
    val context = LocalContext.current
    var placeholder by remember { mutableStateOf<TunnelInfo?>(null) }
    val offline = remember { declaresNoInternet(context) }
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

    fun status(t: TunnelInfo): String = when (t.id) {
        TunnelCatalog.INSTALLER -> lastInstall?.let { "last: ${it.summary}" } ?: "ready"
        TunnelCatalog.UNZIP -> lastUnzip?.let { "last: ${it.subject}" } ?: "ready"
        else -> if (t.isLive) summaries[t.id]?.label() ?: "ready" else "phase ${t.phase}"
    }
    val liveIds = runtime?.registry?.modules?.keys.orEmpty()

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            ConsoleHeader(version, offline, keyLevel, TunnelCatalog.all.count { it.isLive || it.id in liveIds }, TunnelCatalog.all.count { !(it.isLive || it.id in liveIds) })
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth()) {
                MetroMap(
                    status = ::status,
                    isLive = { t -> t.isLive || t.id in liveIds },
                    onStation = { t -> if (t.isLive || t.id in liveIds) onOpenTunnel(t.id) else placeholder = t },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }

    placeholder?.let { t ->
        AlertDialog(
            onDismissRequest = { placeholder = null },
            containerColor = Color(0xFF0D121A),
            title = { Text(t.title) },
            text = {
                Text(
                    "${t.blurb}.\n\nStation under construction on the ${t.line.label} line. Opens in phase ${t.phase}." +
                        if (t.line == MetroLine.EXPLORE) "\n\nExplore is curiosity only: nothing here is a security finding." else "",
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
