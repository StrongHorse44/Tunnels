package io.github.stronghorse44.tunnels.homenet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.lan.CensusMessages
import io.github.stronghorse44.tunnels.lan.DeviceCensus
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanHost
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanSummary
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.RouterInfo
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val line = LineColors.of(MetroLine.NETWORK)

/** Own-network confirmation, scan stages, the router card, the device census and the host list grouped by kind. */
@Composable
fun HomeNetPanel(state: TunnelScreenState, actions: TunnelScreenActions, gate: NetworkGate, census: CensusStore) {
    var generation by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { generation++; onPauseOrDispose { } }
    val wifi = remember(generation, state.scan.running) { runCatching { gate.current() }.getOrDefault(WifiState.OFFLINE) }
    val fingerprint = remember(wifi) { runCatching { wifi.fingerprint }.getOrNull() }
    // The confirmed list is in the encrypted store: read and written off the main thread. null = still reading.
    var confirmed by remember(generation, fingerprint?.hash) { mutableStateOf<Boolean?>(if (fingerprint == null) false else null) }
    var saveFailed by remember(generation, fingerprint?.hash) { mutableStateOf(false) }
    LaunchedEffect(generation, fingerprint?.hash) {
        if (fingerprint != null) confirmed = withContext(Dispatchers.IO) { gate.isConfirmed(fingerprint) }
    }
    val scope = rememberCoroutineScope()
    val summary = remember(state.observations) { LanSummary.from(state.observations) }
    var censusNote by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val tag = summary.networkTag

    // A scan started here has stored its snapshot: move the census pin to the newest Home-network-only one now. (A scan
    // started from Snapshots settles at the start of the next scan.) A failure is retried then; the list does not need the pin.
    val storedSnapshot = state.lastResult?.takeIf { it.stored }?.snapshotId
    LaunchedEffect(storedSnapshot, tag) {
        if (storedSnapshot != null && tag != null) withContext(Dispatchers.IO) { runCatching { census.settle(tag) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NetworkCard(
            wifi = wifi,
            fingerprint = fingerprint,
            confirmed = confirmed,
            scanning = state.scan.running,
            scanLabel = state.scan.label,
            onConfirm = {
                fingerprint?.let { fp ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { gate.confirm(fp) }
                        confirmed = saved
                        saveFailed = !saved
                    }
                }
            },
            onForget = {
                fingerprint?.let { fp ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { gate.forget(fp) }
                        // A forgotten network's list goes with it: a reset also unpins its census snapshot.
                        if (saved) withContext(Dispatchers.IO) { census.reset(fp.prefixTag) }
                        // If the list could not be changed the network may still be confirmed: read it again rather than guess.
                        confirmed = withContext(Dispatchers.IO) { gate.isConfirmed(fp) }
                        saveFailed = !saved
                    }
                }
            },
            onScan = actions::scan,
        )
        if (saveFailed) {
            GlassPanel(Modifier.fillMaxWidth(), tint = StatusColors.warn) {
                Text(
                    "Tunnels' encrypted store could not be changed, so nothing was saved. Scanning stays off until it can be.",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (summary.gate == LanKeys.GATE_UNCONFIRMED) {
            GlassPanel(Modifier.fillMaxWidth(), tint = StatusColors.warn) {
                Text(
                    "The last scan stopped at the gate: " + gateReasonText(summary.gateReason),
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (summary.scanned) {
            summary.router?.let { RouterCard(it) }
            if (summary.censusState != null) {
                CensusCard(
                    summary = summary,
                    scanning = state.scan.running,
                    note = censusNote,
                    onAllMine = { actions.perform(allMine(census, tag)) },
                    onReset = { confirmReset = true },
                )
            }
            HostsCard(summary, onMine = { host -> actions.perform(mine(census, host)) })
        }
    }
    if (confirmReset && tag != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Start the list again?") },
            text = {
                Text(
                    "This forgets every device you added for this network. The next scan asks you to check them again. " +
                        "Devices you added in the last 30 days that are not yet in a scan are forgotten too.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        scope.launch { censusNote = withContext(Dispatchers.IO) { census.reset(tag) } }
                    },
                ) { Text("Start again", color = StatusColors.blocker) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep the list") } },
        )
    }
}

/** "These are all mine" for the network [tag]: the same action the set-up finding carries. */
private fun allMine(census: CensusStore, tag: String?) = FindingAction.Perform(LanGuides.ALL_MINE_LABEL) {
    if (tag == null) CensusMessages.NO_SCAN_FOR_SETUP else withContext(Dispatchers.IO) { census.setup(tag) }
}

/** "Mine" for one host row: the same action as its finding, built from the same subject so the finding is dismissed too. */
private fun mine(census: CensusStore, host: LanHost) = FindingAction.Perform(LanGuides.MINE_LABEL) {
    val primary = host.primaryId
    if (primary == null) CensusMessages.NOT_IN_SCAN
    else withContext(Dispatchers.IO) { census.ack(DeviceCensus.subject(host.censusTitle, primary)) }
}

@Composable
private fun NetworkCard(
    wifi: WifiState,
    fingerprint: NetworkFingerprint?,
    confirmed: Boolean?,
    scanning: Boolean,
    scanLabel: String,
    onConfirm: () -> Unit,
    onForget: () -> Unit,
    onScan: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                !wifi.onWifi -> {
                    Text("Not connected to Wi-Fi.", style = MaterialTheme.typography.bodyLarge)
                    Text("Tunnels scans only the Wi-Fi network you confirm as yours, never mobile data.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                }
                fingerprint == null -> {
                    Text("Connected to Wi-Fi, but Android shared no router or address for it.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tunnels tells networks apart by their router, DHCP and DNS addresses. Without any of them it cannot tell your " +
                            "network from someone else's, so scanning stays off. Wait for the connection to finish, or reconnect to the Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (confirmed == true) StatusColors.ok else StatusColors.warn))
                        Spacer(Modifier.width(8.dp))
                        Text(fingerprint.ssid ?: "Wi-Fi network", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(fingerprint.prefixTag, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                    }
                    Text(fingerprint.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                    if (confirmed == null) {
                        Text("Checking…", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                    } else if (confirmed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Confirmed as your network.", style = MaterialTheme.typography.bodySmall, color = StatusColors.ok, modifier = Modifier.weight(1f))
                            TextButton(onClick = onForget, enabled = !scanning) { Text("Forget", color = GlassColors.dim) }
                        }
                    } else {
                        Text(
                            "Only scan a network you own or administer. Scanning someone else's network can be illegal. " +
                                "Tunnels asks once per network and remembers only a hash of the setup shown above.",
                            style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                        )
                        OutlinedButton(onClick = onConfirm) { Text("This is my network") }
                    }
                    Text(
                        (if (fingerprint.ssid == null) "Android hides the Wi-Fi name from apps without location access, so " else "") +
                            "Tunnels recognises a network by its router, DHCP and DNS addresses. Two networks with the same setup look " +
                            "the same to it: check which Wi-Fi you are on before scanning.",
                        style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onScan, enabled = confirmed == true && !scanning) { Text(if (scanning) "Scanning…" else "Scan my network") }
                        Spacer(Modifier.width(12.dp))
                        StageRow(scanning, scanLabel)
                    }
                    Text(
                        "About a minute: finds devices (mDNS, SSDP), checks ${PortCatalog.ports.size} common ports on each, then tests the router for UPnP and DNS rewriting.",
                        style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRow(scanning: Boolean, label: String) {
    val stages = listOf(LanScanner.STAGE_DISCOVERY, LanScanner.STAGE_PORTS, LanScanner.STAGE_ROUTER)
    val active = stages.indexOfFirst { label.contains(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stages.forEachIndexed { i, stage ->
            val color = when {
                !scanning -> GlassColors.dim.copy(alpha = 0.5f)
                i < active -> StatusColors.ok
                i == active -> line
                else -> GlassColors.dim.copy(alpha = 0.5f)
            }
            Text(stage, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
        }
    }
}

@Composable
private fun RouterCard(router: RouterInfo) {
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Router", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                router.ip?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = line) }
            }
            router.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim) }
            FactRow(
                "UPnP port mapping",
                when (router.upnpIgd) {
                    LanKeys.TRUE -> "on: any device can open ports"
                    LanKeys.FALSE -> "not advertised"
                    else -> "could not tell"
                },
                when (router.upnpIgd) {
                    LanKeys.TRUE -> StatusColors.warn
                    LanKeys.FALSE -> StatusColors.ok
                    else -> GlassColors.dim
                },
            )
            FactRow(
                "DNS rewriting",
                when {
                    router.dnsHijack == LanKeys.TRUE -> "detected: invents answers"
                    router.dnsHijack == LanKeys.FALSE -> "not detected"
                    router.dnsLocal == LanKeys.FALSE -> "not checked: resolver is outside your network"
                    router.dnsLocal == LanKeys.UNKNOWN -> "not checked: no resolver configured"
                    else -> "resolver did not answer"
                },
                when (router.dnsHijack) {
                    LanKeys.TRUE -> StatusColors.blocker
                    LanKeys.FALSE -> StatusColors.ok
                    else -> GlassColors.dim
                },
            )
            FactRow(
                "DNS server",
                when {
                    router.dnsIsGateway == LanKeys.TRUE -> "the router itself"
                    router.dnsLocal == LanKeys.TRUE -> "another device on your network"
                    router.dnsLocal == LanKeys.FALSE -> "a server outside your network; Tunnels never sends DNS off the LAN"
                    else -> "unknown"
                },
                GlassColors.text,
            )
            FactRow(
                "Private DNS on this phone",
                when (router.privateDns) {
                    LanKeys.TRUE -> "on: lookups bypass the router"
                    LanKeys.FALSE -> "off"
                    else -> "unknown"
                },
                if (router.privateDns == LanKeys.TRUE) StatusColors.ok else GlassColors.text,
            )
            val routerPorts = router.openPorts
            FactRow(
                "Open ports",
                when {
                    routerPorts == null -> "not checked"
                    routerPorts.isEmpty() -> "none of the ${PortCatalog.ports.size} checked"
                    else -> routerPorts.joinToString(", ")
                },
                GlassColors.text,
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun CensusCard(summary: LanSummary, scanning: Boolean, note: String?, onAllMine: () -> Unit, onReset: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = if (summary.censusState == DeviceCensus.STATE_UNAVAILABLE) StatusColors.warn else line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device census", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (summary.censusState) {
                DeviceCensus.STATE_SET -> {
                    Text(
                        "${summary.listedCount} on your list · ${summary.unknownCount} not on it",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (summary.unknownCount > 0) StatusColors.warn else StatusColors.ok,
                    )
                    if (summary.full) {
                        Text(
                            "The list is full (${DeviceCensus.MAX_KNOWN}): devices added after that stay \"not on your list\".",
                            style = MaterialTheme.typography.bodySmall, color = StatusColors.warn,
                        )
                    }
                    TextButton(onClick = onReset, enabled = !scanning) { Text("Start the list again", color = StatusColors.blocker) }
                }
                DeviceCensus.STATE_UNSET -> {
                    val seen = summary.devicesSeen
                    Text(
                        "Not set up for this network: " + (if (seen == 1) "1 device" else "$seen devices") +
                            " seen. Check them below, then tap These are all mine.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onAllMine, enabled = seen > 0 && !scanning) { Text(LanGuides.ALL_MINE_LABEL) }
                }
                else -> Text(
                    "The device list could not be read from Tunnels' encrypted store; nothing was judged.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim) }
            Text(
                "Only devices that announce themselves on the network (mDNS or SSDP) can be listed. Phones and other devices that " +
                    "stay silent will not appear, and Tunnels does not sweep your network's addresses to look for them.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
            Text(
                "The list is kept in one pinned Home network snapshot per network (see Snapshots); the census only pins a snapshot " +
                    "that holds nothing but Home network, and it also moves or removes the pin on such a snapshot of this network that " +
                    "you pinned by hand. After tapping Mine, scan once to save it there before you export.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun HostsCard(summary: LanSummary, onMine: (LanHost) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val tail = buildList {
                summary.durationSec?.let { add("${it}s") }
                if (summary.partialStages.isNotEmpty()) add("cut short: ${summary.partialStages.joinToString(", ")}")
                if (summary.droppedOutOfScope > 0) add("${summary.droppedOutOfScope} outside this network ignored")
                if (summary.linkLocalOnly > 0) add("${summary.linkLocalOnly} offered only a link-local address (not probed)")
                if (summary.overCap > 0) add("Device cap (${LanScanner.MAX_HOSTS}) reached: ${summary.overCap} more not checked")
            }
            Text(
                "${summary.totalHosts} found · ${summary.riskyHosts} with risky services" + tail.joinToString("") { " · $it" },
                style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
            )
            if (summary.hosts.isEmpty()) {
                Text("Nothing answered. Devices that sleep or hide from discovery will not show up.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            summary.hostsByKind().forEach { (kind, hosts) ->
                Spacer(Modifier.height(2.dp))
                Text("${kind.title} · ${hosts.size}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                hosts.forEach { HostRow(it, onMine) }
            }
        }
    }
}

@Composable
private fun HostRow(host: LanHost, onMine: (LanHost) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.04f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(host.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (host.riskyPorts.isNotEmpty()) {
                Text(host.riskyPorts.joinToString(","), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StatusColors.warn)
                Spacer(Modifier.width(6.dp))
            }
            val safe = host.openPorts.filter { it !in host.riskyPorts }
            if (safe.isNotEmpty()) Text(safe.joinToString(","), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
        }
        val details = buildList {
            if (host.title != host.ip) add(host.ip)
            host.vendor?.takeIf { it != host.title }?.let { add(it) }
            if (host.services.isNotEmpty()) add(host.services.take(6).joinToString(" "))
            if (host.upnp) add("upnp")
        }
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
        }
        if (host.listed == false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("not on your list", style = MaterialTheme.typography.labelMedium, color = StatusColors.warn, modifier = Modifier.weight(1f))
                // A host that gave nothing to recognise it by has no identity to add.
                if (host.primaryId != null) TextButton(onClick = { onMine(host) }) { Text("Mine") }
            }
        }
    }
}

private fun gateReasonText(reason: String?): String = when (reason) {
    LanKeys.REASON_NO_WIFI -> "the phone was not on Wi-Fi."
    LanKeys.REASON_NETWORK_UNKNOWN -> "the network's router and address were not readable."
    LanKeys.REASON_NOT_CONFIRMED -> "this network had not been confirmed as yours."
    LanKeys.REASON_STORE_UNAVAILABLE -> "Tunnels' encrypted store could not be read, so it could not tell whether this network is yours."
    LanKeys.REASON_NO_PERMISSION -> "the Nearby devices permission was missing."
    else -> "the network could not be verified as yours."
}
