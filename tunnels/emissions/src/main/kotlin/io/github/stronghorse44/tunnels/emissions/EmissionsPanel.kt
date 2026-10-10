package io.github.stronghorse44.tunnels.emissions

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.PrismButton
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.StratumColors
import io.github.stronghorse44.tunnels.deepmode.ShizukuConnectActivity
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val tint get() = StratumColors.of(Stratum.SURFACE)

private fun stateColor(s: EmitterState): Color = when (s) {
    EmitterState.ON -> StatusColors.warn
    EmitterState.OFF -> StatusColors.ok
    EmitterState.ABSENT, EmitterState.UNKNOWN -> GlassColors.dim
}

private fun levelColor(l: QuietLevel): Color = when (l) {
    QuietLevel.SILENT -> StatusColors.ok
    QuietLevel.QUIET_UNREAD -> StatusColors.info
    QuietLevel.PARTLY, QuietLevel.LOUD -> StatusColors.warn
}

/**
 * The Emissions screen. Every state is read back from the last scan's observations; Silence, Restore and each Turn off
 * go through [TunnelScreenActions.perform] as destructive actions, so the screen scans again and shows the result.
 */
@Composable
internal fun EmissionsPanel(tunnel: EmissionsTunnel, state: TunnelScreenState, screen: TunnelScreenActions) {
    val context = LocalContext.current
    val reading = remember(state.observations) { state.observations.takeIf { it.isNotEmpty() }?.let(EmissionsObservations::from) }
    // Shizuku comes and goes (it stops at every restart): read it live whenever the screen resumes.
    var access by remember { mutableStateOf(ShellAccess.NOT_RUNNING) }
    LifecycleResumeEffect(Unit) {
        access = EmissionsProbe.shellAccess(context)
        onPauseOrDispose { }
    }
    val remembered by produceState(emptySet<Emitter>(), state.lastScan, state.message) { value = tunnel.silencer.remembered() }
    val recent by tunnel.recentUse.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reading == null) {
            Card {
                Text("What is this phone transmitting?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Scan to read each transmitter: cellular, Wi-Fi and its scanning, Bluetooth and its scanning, NFC, UWB and " +
                        "the hotspot. No permission is needed.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            }
            FullSilenceCard()
            return@Column
        }
        val live = reading.copy(shell = access)
        SilenceCard(tunnel, live, remembered, screen)
        TransmittersCard(tunnel, live, screen)
        AppsCard(live, recent)
        BagTestCard(tunnel, live)
        FullSilenceCard()
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun CardHeader(title: String, aside: String? = null, asideColor: Color = GlassColors.dim) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = asideColor)
    }
}

@Composable
private fun Dim(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SilenceCard(tunnel: EmissionsTunnel, r: EmissionsReading, remembered: Set<Emitter>, screen: TunnelScreenActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report = remember(r) { SilenceReport.of(r) }
    Card {
        CardHeader("How quiet", report.level.label, levelColor(report.level))
        Text(report.headline, style = MaterialTheme.typography.bodyLarge)
        Dim(report.howFar)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.shell == ShellAccess.READY) {
                if (report.on.isNotEmpty() || r.unknown.isNotEmpty()) {
                    PrismButton("Silence", onClick = {
                        screen.perform(FindingAction.Perform("Silence", destructive = true) { tunnel.silencer.silence() })
                    })
                }
                if (remembered.isNotEmpty()) {
                    PrismButton("Restore", onClick = {
                        screen.perform(FindingAction.Perform("Restore", destructive = true) { tunnel.silencer.restore() })
                    })
                }
            } else if (report.on.isNotEmpty()) {
                PrismButton("Remember what is on", onClick = { scope.launch { tunnel.silencer.remember(r) } })
            }
        }
        if (r.shell != ShellAccess.READY) {
            Dim(Silencer.notReady(r.shell) + " With Shizuku, Silence turns everything off in one tap and Restore brings it back.")
            Row {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(ShizukuConnectActivity.ACTION).setPackage(context.packageName)) }
                }) { Text("Connect Shizuku") }
            }
            if (remembered.isNotEmpty()) {
                Dim("Before you silenced, these were on. Turn each back on from its Settings screen:")
                remembered.forEach { e ->
                    val route = EmitterSettings.routeFor(e)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { screen.perform(FindingAction.OpenSettings(route.action, route.label)) }) { Text(route.label) }
                    }
                }
                TextButton(onClick = { scope.launch { tunnel.silencer.forget(); screen.scan() } }) { Text("Done, forget the list") }
            }
        }
    }
}

@Composable
private fun TransmittersCard(tunnel: EmissionsTunnel, r: EmissionsReading, screen: TunnelScreenActions) {
    Card {
        CardHeader("Transmitters", "${r.on.size} on")
        Emitter.entries.filter { r.state(it) != EmitterState.ABSENT }.forEach { e -> TransmitterRow(tunnel, e, r, screen) }
        val absent = Emitter.entries.filter { r.state(it) == EmitterState.ABSENT }
        if (absent.isNotEmpty()) Dim("Not on this phone: " + absent.joinToString(", ") { it.title } + ".")
        // GNSS listens only; shown so the location switch is in view, never counted as an emission.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Location (GNSS)", style = MaterialTheme.typography.bodyLarge)
                Dim("Receives only: not an emission. ${onOff(r.location)}.")
            }
            TextButton(onClick = {
                screen.perform(FindingAction.OpenSettings(EmitterSettings.ACTION_LOCATION, "Location settings"))
            }) { Text("Location") }
        }
    }
}

private fun onOff(v: Boolean?) = when (v) {
    true -> "On"
    false -> "Off"
    null -> "Unknown"
}

@Composable
private fun TransmitterRow(tunnel: EmissionsTunnel, e: Emitter, r: EmissionsReading, screen: TunnelScreenActions) {
    val reading = r.reading(e)
    val route = EmitterSettings.routeFor(e)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                Text(
                    "  " + reading.state.id,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium, color = stateColor(reading.state),
                )
            }
            Dim(e.sends)
            details(e, r)?.let { Dim(it) }
            if (!reading.confirmed) Dim("Read through Shizuku; this reading is not yet checked on this phone.")
            if (reading.state == EmitterState.UNKNOWN && r.shell != ShellAccess.READY) Dim("Needs Shizuku to read.")
        }
        when {
            reading.state == EmitterState.ON && r.shell == ShellAccess.READY && ShellPlan.silenceStep(e, r) != null ->
                TextButton(onClick = {
                    screen.perform(FindingAction.Perform("Turn off ${e.title}", destructive = true) { tunnel.silencer.turnOff(listOf(e)) })
                }) { Text("Turn off") }
            reading.state == EmitterState.ON || reading.state == EmitterState.UNKNOWN ->
                TextButton(onClick = { screen.perform(FindingAction.OpenSettings(route.action, route.label)) }) { Text("Open") }
            else -> Unit
        }
    }
}

private fun details(e: Emitter, r: EmissionsReading): String? = when (e) {
    Emitter.CELLULAR -> listOfNotNull(
        "Airplane mode ${onOff(r.airplane).lowercase()}",
        r.sim?.let { "SIM $it" },
        "mobile data ${onOff(r.mobileData).lowercase()}",
    ).joinToString(" · ")
    Emitter.HOTSPOT -> r.hotspotKinds.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Serving: ")
    Emitter.WIFI_SCANNING, Emitter.BLE_SCANNING -> "Location > Location services"
    else -> null
}

@Composable
private fun AppsCard(r: EmissionsReading, recent: Map<RadioUse.Kind, List<RadioUse.Use>>?) {
    val context = LocalContext.current
    val pm = context.packageManager
    Card {
        CardHeader("Apps using radios", "last ${RadioUse.RECENT_MINUTES} min")
        when {
            r.shell != ShellAccess.READY ->
                Dim("With Shizuku, Tunnels shows which apps used Bluetooth, Wi-Fi, location or UWB in the last ${RadioUse.RECENT_MINUTES} minutes, from Android's app-op history. Names are shown, never stored.")
            recent == null -> {
                val counts = r.appUse
                if (counts == null || counts.failed) Dim("Scan to read the app-op history.")
                else Dim(RadioUse.Kind.entries.joinToString(" · ") { "${it.title} ${counts.counts[it] ?: 0}" } + ". Scan again for the names.")
            }
            recent.values.all { it.isEmpty() } -> Dim("No app used a radio in the last ${RadioUse.RECENT_MINUTES} minutes.")
            else -> RadioUse.Kind.entries.forEach { kind ->
                val uses = recent[kind].orEmpty()
                if (uses.isEmpty()) return@forEach
                Text("${kind.title} · ${uses.size}", style = MaterialTheme.typography.labelLarge, color = tint)
                uses.forEach { u ->
                    val label = remember(u.packageName) {
                        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, PackageManager.ApplicationInfoFlags.of(0))).toString() }
                            .getOrDefault(u.packageName)
                    }
                    Row {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            u.op.lowercase().replace('_', ' ') + " · " + RadioUse.ago(u),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BagTestCard(tunnel: EmissionsTunnel, r: EmissionsReading) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<BagTestRunner.Progress?>(null) }
    var result by remember { mutableStateOf<BagTest.Result?>(null) }
    val running = job?.isActive == true

    // The Bluetooth scan stops with the screen off, so the screen stays on for the length of the test.
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun start() {
        result = null
        job = scope.launch {
            try {
                result = tunnel.bagTest.measure { progress = it }
            } finally {
                progress = null
                job = null
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }

    Card {
        CardHeader("Faraday bag test", if (running) "running" else null, StatusColors.info)
        Dim(
            "Tests the bag, not the phone. Radios stay on: Tunnels listens ${BagTest.OUTSIDE_MILLIS / 1000} s outside the bag, " +
                "gives you ${BagTest.COUNTDOWN_SECONDS} s to seal the phone in, then listens ${BagTest.INSIDE_MILLIS / 1000} s inside. " +
                "One buzz when it starts listening inside, two when you can take it out. The screen stays on; nothing is stored.",
        )
        BagTest.preflight(r).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
        val p = progress
        if (p != null) {
            Text("${p.phase.label}: ${p.secondsLeft} s", style = MaterialTheme.typography.bodyLarge, color = StatusColors.info)
            TextButton(onClick = { job?.cancel() }) { Text("Stop") }
        } else {
            PrismButton("Start bag test", onClick = {
                val missing = tunnel.bagTest.permissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) start() else launcher.launch(missing.toTypedArray())
            })
            Dim("Asks once for Nearby devices and Nearby Wi-Fi to count what it hears; without them those parts are not tested.")
        }
        result?.let { res ->
            val color = when (res.overall) {
                BagTest.Verdict.BLOCKED -> StatusColors.ok
                BagTest.Verdict.LEAKS -> StatusColors.blocker
                BagTest.Verdict.UNTESTED -> GlassColors.dim
            }
            Text(res.headline, style = MaterialTheme.typography.bodyLarge, color = color)
            res.channels.forEach { c ->
                Text(
                    "${c.channel.title}: ${c.verdict.name.lowercase()} · outside ${c.outside} · inside ${c.inside}" + (c.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BagTest.caveats.forEach { Dim(it) }
        }
    }
}

@Composable
private fun FullSilenceCard() {
    var open by remember { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Full silence: what it takes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (!open) {
            Dim("Software can switch transmitters off; only power off and a tested Faraday bag do not rely on trusting it.")
            return@Card
        }
        FullSilence.steps.forEachIndexed { i, step ->
            Text("${i + 1}. ${step.title}", style = MaterialTheme.typography.bodyLarge)
            Dim(step.body)
        }
        Text("What software cannot promise", style = MaterialTheme.typography.labelLarge, color = tint)
        FullSilence.limits.forEach { Dim("• $it") }
    }
}
