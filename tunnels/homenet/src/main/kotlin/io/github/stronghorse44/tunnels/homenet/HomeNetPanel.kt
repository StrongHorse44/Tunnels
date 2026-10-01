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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.lan.LanHost
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanSummary
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.RouterInfo
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

private val line = LineColors.of(MetroLine.NETWORK)

/** Own-network confirmation, scan stages, the router card and the host list grouped by kind. */
@Composable
fun HomeNetPanel(state: TunnelScreenState, actions: TunnelScreenActions, gate: NetworkGate) {
    var generation by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { generation++; onPauseOrDispose { } }
    val wifi = remember(generation, state.scan.running) { runCatching { gate.current() }.getOrDefault(WifiState.OFFLINE) }
    var confirmed by remember(generation, wifi.ssid) { mutableStateOf(wifi.ssid?.let(gate::isConfirmed) ?: false) }
    val summary = remember(state.observations) { LanSummary.from(state.observations) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NetworkCard(
            wifi = wifi,
            confirmed = confirmed,
            scanning = state.scan.running,
            scanLabel = state.scan.label,
            onConfirm = { wifi.ssid?.let { gate.confirm(it); confirmed = true } },
            onForget = { wifi.ssid?.let { gate.forget(it); confirmed = false } },
            onScan = actions::scan,
        )
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
            HostsCard(summary)
        }
    }
}

@Composable
private fun NetworkCard(
    wifi: WifiState,
    confirmed: Boolean,
    scanning: Boolean,
    scanLabel: String,
    onConfirm: () -> Unit,
    onForget: () -> Unit,
    onScan: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val ssid = wifi.ssid
            when {
                !wifi.onWifi -> {
                    Text("Not connected to Wi-Fi.", style = MaterialTheme.typography.bodyLarge)
                    Text("Tunnels scans only the Wi-Fi network you confirm as yours, never mobile data.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                }
                ssid == null -> {
                    Text("Connected to Wi-Fi, but Android did not share its name.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Without the network name Tunnels cannot tell your network from someone else's, so scanning stays off. " +
                            "Check the Nearby devices permission for Tunnels and reconnect to the Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                    )
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (confirmed) StatusColors.ok else StatusColors.warn))
                        Spacer(Modifier.width(8.dp))
                        Text(ssid, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        wifi.gateway?.hostAddress?.let { Text("gw $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim) }
                    }
                    if (confirmed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Confirmed as your network.", style = MaterialTheme.typography.bodySmall, color = StatusColors.ok, modifier = Modifier.weight(1f))
                            TextButton(onClick = onForget, enabled = !scanning) { Text("Forget", color = GlassColors.dim) }
                        }
                    } else {
                        Text(
                            "Only scan a network you own or administer. Scanning someone else's network can be illegal. " +
                                "Tunnels asks once per network and remembers only a hash of its name.",
                            style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                        )
                        OutlinedButton(onClick = onConfirm) { Text("This is my network") }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onScan, enabled = confirmed && !scanning) { Text(if (scanning) "Scanning…" else "Scan my network") }
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
                when (router.dnsHijack) {
                    LanKeys.TRUE -> "detected: invents answers"
                    LanKeys.FALSE -> "not detected"
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
                when (router.dnsIsGateway) {
                    LanKeys.TRUE -> "the router itself"
                    LanKeys.FALSE -> "another server"
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
            FactRow("Open ports", if (router.openPorts.isEmpty()) "none of the ${PortCatalog.ports.size} checked" else router.openPorts.joinToString(", "), GlassColors.text)
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
private fun HostsCard(summary: LanSummary) {
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val tail = buildList {
                summary.durationSec?.let { add("${it}s") }
                if (summary.partialStages.isNotEmpty()) add("cut short: ${summary.partialStages.joinToString(", ")}")
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
                hosts.forEach { HostRow(it) }
            }
        }
    }
}

@Composable
private fun HostRow(host: LanHost) {
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
    }
}

private fun gateReasonText(reason: String?): String = when (reason) {
    LanKeys.REASON_NO_WIFI -> "the phone was not on Wi-Fi."
    LanKeys.REASON_SSID_UNKNOWN -> "the Wi-Fi name was not readable."
    LanKeys.REASON_NOT_CONFIRMED -> "this network had not been confirmed as yours."
    LanKeys.REASON_NO_PERMISSION -> "the Nearby devices permission was missing."
    else -> "the network could not be verified as yours."
}
