package io.github.stronghorse44.tunnels.deepmode

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.posture.PostureKeys
import io.github.stronghorse44.tunnels.posture.PostureObservations
import io.github.stronghorse44.tunnels.posture.PostureState
import io.github.stronghorse44.tunnels.posture.PostureText
import io.github.stronghorse44.tunnels.posture.Reading
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import rikka.shizuku.Shizuku

/** Request code for Shizuku.requestPermission; echoed back in the result listener. */
const val SHIZUKU_REQUEST_CODE = 6001

private const val MAX_RECENT_ROWS = 20

/** Custom content of the deep_mode tunnel: Shizuku status, the posture card, then which apps touched sensors lately. */
@Composable
fun DeepModePanel(state: TunnelScreenState) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ShizukuStatus.cached(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShizukuStatusCard(onStatus = { status = it })
        PostureCard(state.observations, status)
        RecentSensorAccess(state.observations)
    }
}

/**
 * Installed / running / granted, with the one button that moves the user forward: open Shizuku when it
 * is not running, ask it for permission when it is. Re-reads status on resume and when the binder arrives.
 */
@Composable
fun ShizukuStatusCard(onGranted: (() -> Unit)? = null, onStatus: ((ShizukuStatus) -> Unit)? = null) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ShizukuStatus.read(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        ShizukuStatus.invalidate()
        status = ShizukuStatus.read(context)
        onStatus?.invoke(status)
        if (status.granted) onGranted?.invoke()
    }
    LifecycleResumeEffect(Unit) { refresh(); onPauseOrDispose { } }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { refresh() }
        val dead = Shizuku.OnBinderDeadListener { refresh() }
        val result = Shizuku.OnRequestPermissionResultListener { code, grant ->
            if (code == SHIZUKU_REQUEST_CODE) {
                note = if (grant == android.content.pm.PackageManager.PERMISSION_GRANTED) null else "Shizuku denied the request. Allow Tunnels in the Shizuku app."
                refresh()
            }
        }
        runCatching {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(result)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
                Shizuku.removeRequestPermissionResultListener(result)
            }
        }
    }

    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                status.version?.let { Text("v$it", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = line) }
            }
            StatusRow("Installed", status.installed)
            StatusRow("Running", status.running, offText = "stopped")
            StatusRow("Granted to Tunnels", status.granted, offText = "not yet")
            when {
                !status.installed -> Text(
                    "Install the Shizuku app (moe.shizuku.privileged.api) from its website or F-Droid. Deep mode needs nothing else.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
                !status.running -> {
                    OutlinedButton(onClick = {
                        val intent = ShizukuStatus.launchIntent(context)
                        try {
                            if (intent != null) context.startActivity(intent) else note = "Shizuku has no launcher screen on this phone."
                        } catch (_: ActivityNotFoundException) {
                            note = "Shizuku could not be opened."
                        }
                    }) { Text("Open Shizuku") }
                    Text(
                        "Shizuku stops at every reboot. In the Shizuku app, choose \"Start via Wireless debugging\" (Developer options must be on), then come back here.",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                !status.granted -> {
                    Button(onClick = {
                        try {
                            if (Shizuku.isPreV11()) note = "This Shizuku is too old. Update it to version 11 or newer."
                            else Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                        } catch (e: Exception) {
                            note = e.message ?: "Shizuku did not take the request."
                        }
                    }) { Text("Connect") }
                    Text(DeepModeTunnel.SHIZUKU_REASON, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                }
                else -> Text(
                    "Connected. Scans run shell commands in a helper process that exits when the app does; results are summarised, never stored raw.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
        }
    }
}

@Composable
private fun StatusRow(label: String, on: Boolean, offText: String = "no") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (on) "yes" else offText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (on) StatusColors.ok else StatusColors.warn,
        )
    }
}

/**
 * The GrapheneOS posture readings of the last Deep mode scan, one row per item. Unknown is dim, never amber: it is not a
 * finding. A setting no app can read (the duress PIN; the USB-C port on this build) is a reminder row with a Settings
 * button, not a finding.
 */
@Composable
private fun PostureCard(observations: List<Observation>, status: ShizukuStatus) {
    val snapshot = remember(observations) { PostureObservations.from(observations) }
    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Posture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                !status.granted -> Text(PostureText.needsShizuku(status.reason), style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim)
                !snapshot.isEmpty -> snapshot.readings.forEach { reading ->
                    val item = reading.item
                    if (item.reminder != null && !item.confirmed) {
                        ReminderRow(item.title, item.reminder!!, item.action ?: PostureKeys.ACTION_SECURITY)
                    } else {
                        PostureRow(reading)
                    }
                }
                // Shizuku is granted but this snapshot holds no posture (an older scan ran without it): scan again.
                else -> Text(PostureText.NOT_SCANNED, style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim)
            }
            if (status.granted && !snapshot.isEmpty) ReminderRow("Duress PIN", PostureText.DURESS_NOTE, PostureKeys.ACTION_SECURITY)
        }
    }
}

/** A setting nobody can read: what to check by hand, and a button to the Settings screen. Never a finding. */
@Composable
private fun ReminderRow(title: String, text: String, action: String) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                note = null
            } catch (_: ActivityNotFoundException) {
                note = "No screen on this phone handles that."
            }
        }) { Text("Open Security settings") }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
    }
}

@Composable
private fun PostureRow(reading: Reading) {
    val color = when (reading.state) {
        PostureState.GOOD -> StatusColors.ok
        PostureState.WEAK -> StatusColors.warn
        PostureState.UNKNOWN, PostureState.NA -> GlassColors.dim
    }
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(reading.item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(PostureText.stateWord(reading.state), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
        }
        Text(PostureText.detail(reading), style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
    }
}

private class RecentRow(val subject: String, val label: String, val parts: List<Pair<String, Int>>) {
    val newest: Int get() = parts.minOf { it.second }
}

/** Apps whose camera, microphone or location use (foreground or background) is within the last week. */
@Composable
private fun RecentSensorAccess(observations: List<Observation>) {
    val rows = remember(observations) { recentRows(observations) }
    val hasScan = observations.any { it.key == DeepKeys.AVAILABLE && it.value == "true" }
    if (!hasScan) return
    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent sensor access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Camera, microphone and location in the last 7 days, by app", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            if (rows.isEmpty()) {
                Text("No app used a sensor this week.", style = MaterialTheme.typography.bodyMedium, color = StatusColors.ok)
                return@Column
            }
            rows.take(MAX_RECENT_ROWS).forEach { row ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        row.parts.joinToString(" · ") { it.first },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (row.parts.any { it.first.endsWith("(background)") }) StatusColors.warn else Color.Unspecified,
                    )
                }
            }
            if (rows.size > MAX_RECENT_ROWS) {
                Text("and ${rows.size - MAX_RECENT_ROWS} more", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
        }
    }
}

private fun recentRows(observations: List<Observation>): List<RecentRow> =
    observations.filter { DeepKeys.isApp(it.subject) }.groupBy { it.subject }.mapNotNull { (subject, obs) ->
        val parts = ArrayList<Pair<String, Int>>()
        for (op in DeepKeys.SENSOR_OPS) {
            obs.firstOrNull { it.key == DeepKeys.lastKey(op) }?.let { o ->
                DeepKeys.ageDays(o.value)?.takeIf { it <= DeepRules.BACKGROUND_DAYS }?.let { parts += "${DeepKeys.opLabel(op)} ${o.value}" to it }
            }
            obs.firstOrNull { it.key == DeepKeys.bgLastKey(op) }?.let { o ->
                DeepKeys.ageDays(o.value)?.takeIf { it <= DeepRules.BACKGROUND_DAYS }?.let { parts += "${DeepKeys.opLabel(op)} ${o.value} (background)" to it }
            }
        }
        if (parts.isEmpty()) null
        else RecentRow(subject, obs.firstOrNull { it.key == DeepKeys.APP_LABEL }?.value ?: subject, parts.sortedBy { it.second })
    }.sortedWith(compareBy({ it.newest }, { it.label.lowercase() }))
