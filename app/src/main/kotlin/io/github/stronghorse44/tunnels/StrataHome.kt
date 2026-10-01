package io.github.stronghorse44.tunnels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.StrataColors
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelInfo
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val layers = listOf(
    Triple(Stratum.SURFACE, "Surface", "0 m"),
    Triple(Stratum.TOPSOIL, "Topsoil", "−1 m"),
    Triple(Stratum.BEDROCK, "Bedrock", "−40 m"),
    Triple(Stratum.CORE, "Core", "−6,371 km"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrataHome(onOpenTunnel: (String) -> Unit) {
    val context = LocalContext.current
    var placeholder by remember { mutableStateOf<TunnelInfo?>(null) }
    var showExplore by rememberSaveable { mutableStateOf(false) }
    val offline = remember { declaresNoInternet(context) }
    val keyLevel by produceState("…") {
        value = withContext(Dispatchers.IO) {
            runCatching { TunnelsStore.get(context); TunnelsStore.keySecurityLevel() }.getOrDefault("unavailable")
        }
    }

    val open: (TunnelInfo) -> Unit = { t -> if (t.isLive) onOpenTunnel(t.id) else placeholder = t }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Sky above the surface.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2A3A), Color(0xFF2E4A5E))))
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TUNNELS", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Color.White)
                    Text(
                        if (offline) "Offline · no internet permission" else "Warning: internet permission present",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (offline) StatusColors.ok else StatusColors.blocker,
                    )
                    Text("Encrypted store · key in $keyLevel", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
                OutlinedButton(onClick = { showExplore = true }) { Text("Explore", color = Color.White) }
            }
        }

        layers.forEach { (stratum, name, depth) ->
            StratumBand(stratum, name, depth, TunnelCatalog.inStratum(stratum), open)
        }
        Spacer(Modifier.navigationBarsPadding())
    }

    placeholder?.let { t ->
        AlertDialog(
            onDismissRequest = { placeholder = null },
            title = { Text(t.title) },
            text = { Text("${t.blurb}.\n\nThis tunnel isn't dug yet. It's planned for phase ${t.phase}.") },
            confirmButton = { TextButton(onClick = { placeholder = null }) { Text("OK") } },
        )
    }

    if (showExplore) {
        ModalBottomSheet(onDismissRequest = { showExplore = false }, containerColor = StrataColors.explore) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text("Explore", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Curiosity only. Nothing here is a security finding.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.height(12.dp))
                TunnelCatalog.inStratum(Stratum.EXPLORE).forEach { TunnelRow(it, onClick = { showExplore = false; open(it) }) }
            }
        }
    }
}

@Composable
private fun StratumBand(stratum: Stratum, name: String, depth: String, tunnels: List<TunnelInfo>, onOpen: (TunnelInfo) -> Unit) {
    val base = StrataColors.of(stratum)
    val fill = if (stratum == Stratum.CORE) {
        Brush.verticalGradient(listOf(StrataColors.core, StrataColors.coreGlow))
    } else {
        Brush.verticalGradient(listOf(base, base.copy(red = base.red * 0.85f, green = base.green * 0.85f, blue = base.blue * 0.85f)))
    }
    Box(Modifier.fillMaxWidth().background(fill)) {
        Grain(stratum, Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(name.uppercase(), fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(depth, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tunnels.forEach { TunnelRow(it, onClick = { onOpen(it) }) }
            }
        }
    }
}

@Composable
private fun TunnelRow(t: TunnelInfo, onClick: () -> Unit) {
    val alpha = if (t.isLive) 1f else 0.55f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = if (t.isLive) 0.32f else 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White.copy(alpha = alpha), fontWeight = if (t.isLive) FontWeight.SemiBold else FontWeight.Normal)
            Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = alpha * 0.75f))
        }
        Text(
            if (t.isLive) "Descend ›" else "Phase ${t.phase}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = alpha),
        )
    }
}

/** Deterministic speckle so each layer reads as rock rather than a flat color. */
@Composable
private fun Grain(stratum: Stratum, modifier: Modifier) {
    Canvas(modifier) {
        val rnd = Random(stratum.ordinal * 7919)
        val count = (size.width * size.height / 900f).toInt().coerceAtMost(600)
        repeat(count) {
            val light = rnd.nextBoolean()
            drawCircle(
                color = if (light) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f),
                radius = 1f + rnd.nextFloat() * 2.5f,
                center = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height),
            )
        }
    }
}

private fun declaresNoInternet(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    )
    info.requestedPermissions?.contains(Manifest.permission.INTERNET) != true
}.getOrDefault(false)

