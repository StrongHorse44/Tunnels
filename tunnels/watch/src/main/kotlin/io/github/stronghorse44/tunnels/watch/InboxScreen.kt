package io.github.stronghorse44.tunnels.watch

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.runtime.Choice
import io.github.stronghorse44.tunnels.runtime.FindingCard
import io.github.stronghorse44.tunnels.runtime.ToolScaffold
import io.github.stronghorse44.tunnels.runtime.TunnelActivity
import io.github.stronghorse44.tunnels.runtime.severityColor
import io.github.stronghorse44.tunnels.watchrules.Inbox
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import java.text.DateFormat
import java.util.Date

private val Mono = FontFamily.Monospace

/** Every open finding, most severe first, with its actions; and the background checks panel. */
@Composable
fun InboxScreen(vm: InboxViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.open() }
    LifecycleResumeEffect(Unit) {
        vm.refreshSystem()
        onPauseOrDispose { }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tint = LineColors.of(MetroLine.FILES)
    // Asked when checks are switched on (rule #6); checks run whatever the answer, findings then wait here.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setEnabled(true)
    }
    val visible = remember(state.findings, state.filter, state.lastVisit, state.tunnel) {
        Inbox.filter(state.findings, state.filter, state.lastVisit, state.tunnel)
    }

    ToolScaffold("Findings", "every tunnel · most severe first", tint, onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SummaryPanel(state, tint) }
                item {
                    ChecksPanel(
                        state = state,
                        tint = tint,
                        onToggle = { on ->
                            if (on && !state.notificationsAllowed) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.setEnabled(on)
                        },
                        onInterval = vm::setInterval,
                        onNotifyAt = vm::setNotifyAt,
                        onExtra = vm::toggleExtra,
                        onCheck = vm::checkNow,
                        onNotificationSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        },
                    )
                }
                if (state.findings.isNotEmpty()) item { FilterRow(state, tint, vm::setFilter, vm::setTunnel) }
                if (state.loaded && visible.isEmpty()) {
                    item {
                        Text(
                            when {
                                state.findings.isEmpty() -> "No open findings. Open a tunnel and scan it, or run a check above."
                                state.filter == Inbox.Filter.NEW -> "Nothing new since your last visit."
                                else -> "Nothing matches this filter."
                            },
                            color = GlassColors.dim,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
                items(visible, key = { it.id }) { f ->
                    InboxCard(
                        f = f,
                        isNew = Inbox.isNew(f, state.lastVisit),
                        onOpenTunnel = { context.startActivity(TunnelActivity.intent(context, f.tunnelId)) },
                        onAction = vm::perform,
                        onDismiss = { vm.dismiss(f) },
                    )
                }
            }
            state.message?.let { msg ->
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp), action = { TextButton(onClick = vm::clearMessage) { Text("OK") } }) { Text(msg) }
            }
        }
    }
}

@Composable
private fun SummaryPanel(state: InboxState, tint: androidx.compose.ui.graphics.Color) {
    val counts = remember(state.findings) { Inbox.counts(state.findings) }
    val newCount = remember(state.findings, state.lastVisit) { state.findings.count { Inbox.isNew(it, state.lastVisit) } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val total = state.findings.size
            Text(
                if (total == 0) "No open findings" else "$total open finding${if (total == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (total > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Severity.entries.reversed().forEach { s ->
                        counts[s]?.let { n -> Text(WatchPolicy.severityCount(n, s), fontFamily = Mono, fontSize = 12.sp, color = severityColor(s)) }
                    }
                }
            }
            Text(
                when {
                    state.lastVisit == null -> "From your next visit on, findings that appeared in between are marked NEW."
                    newCount == 0 -> "Nothing new since ${fmt.format(Date(state.lastVisit.toEpochMilli()))}."
                    else -> "$newCount new since ${fmt.format(Date(state.lastVisit.toEpochMilli()))}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (newCount > 0) StatusColors.info else GlassColors.dim,
            )
            Text(
                "Every finding comes with what you can do about it. Findings that mark a change stay until you dismiss them " +
                    "or 30 days pass; the others clear when the state they describe does.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun ChecksPanel(
    state: InboxState,
    tint: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onNotifyAt: (Severity) -> Unit,
    onExtra: (String) -> Unit,
    onCheck: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) { now = System.currentTimeMillis() }
    val settings = state.settings
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        WatchFormat.statusLine(settings, state.status, state.scheduled, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.enabled) StatusColors.ok else GlassColors.dim,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = onToggle, enabled = state.settingsLoaded)
            }
            if (settings.enabled) {
                Text("Every", style = MaterialTheme.typography.labelMedium, color = GlassColors.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InboxViewModel.INTERVALS.forEach { h -> Choice("$h h", settings.intervalHours == h, tint, { onInterval(h) }) }
                }
                Text("Notify about new", style = MaterialTheme.typography.labelMedium, color = GlassColors.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchPolicy.NOTIFY_LEVELS.reversed().forEach { s ->
                        val label = when (s) {
                            Severity.CRITICAL -> "Critical only"
                            Severity.WARN -> "Warn and up"
                            else -> "Notice and up"
                        }
                        Choice(label, settings.notifyAt == s, tint, { onNotifyAt(s) })
                    }
                }
                if (!state.notificationsAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Notifications are off for Tunnels, so new findings wait here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.warn,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onNotificationSettings) { Text("Allow") }
                    }
                }
                Text("Also check (needs their access; their numbers move all day, so most checks will store a snapshot)", style = MaterialTheme.typography.labelMedium, color = GlassColors.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchPolicy.OPTIONAL.forEach { id -> Choice(TunnelCatalog.byId(id)?.title ?: id, id in settings.extra, tint, { onExtra(id) }) }
                }
            } else {
                Text(
                    "Tunnels can re-check the phone on a schedule and tell you when something changed: a new certificate " +
                        "authority, a permission granted, a different signing key after an update, a hardening regression. " +
                        "Switching on asks once for notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
            }
            Text(WatchFormat.scope(), style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCheck, enabled = !state.checking && !state.scan.running) { Text(if (state.checking) "Checking…" else "Check now") }
                Spacer(Modifier.width(12.dp))
                if (state.scan.running) Text(state.scan.label, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim, maxLines = 2)
            }
            if (state.checking || state.scan.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FilterRow(state: InboxState, tint: androidx.compose.ui.graphics.Color, onFilter: (Inbox.Filter) -> Unit, onTunnel: (String?) -> Unit) {
    val tunnels = remember(state.findings) { state.findings.map { it.tunnelId }.distinct().sortedBy { TunnelCatalog.byId(it)?.title ?: it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Inbox.Filter.entries.forEach { f -> Choice(f.label, state.filter == f, tint, { onFilter(f) }) }
        }
        if (tunnels.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("All tunnels", state.tunnel == null, tint, { onTunnel(null) })
                tunnels.forEach { id -> Choice(TunnelCatalog.byId(id)?.title ?: id, state.tunnel == id, tint, { onTunnel(id) }) }
            }
        }
    }
}

@Composable
private fun InboxCard(f: Finding, isNew: Boolean, onOpenTunnel: () -> Unit, onAction: (io.github.stronghorse44.tunnels.model.FindingAction) -> Unit, onDismiss: () -> Unit) {
    val info = TunnelCatalog.byId(f.tunnelId)
    val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (info?.title ?: f.tunnelId) + " ›",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = info?.let { LineColors.of(it.line) } ?: GlassColors.dim,
                modifier = Modifier.clickable(onClick = onOpenTunnel),
            )
            if (isNew) {
                Spacer(Modifier.width(8.dp))
                Text("NEW", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = StatusColors.info)
            }
            Spacer(Modifier.weight(1f))
            Text("since ${fmt.format(Date(f.firstSeen.toEpochMilli()))}", fontFamily = Mono, fontSize = 10.sp, color = GlassColors.dim)
        }
        FindingCard(f, onAction, onDismiss)
    }
}
