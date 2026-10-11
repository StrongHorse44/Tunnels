package io.github.stronghorse44.tunnels.homenet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.lan.DeviceCensus
import io.github.stronghorse44.tunnels.lan.HostKind
import io.github.stronghorse44.tunnels.lan.Labels
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanHost
import io.github.stronghorse44.tunnels.lan.LanSweep
import io.github.stronghorse44.tunnels.lan.LookCloser
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.PortClues
import io.github.stronghorse44.tunnels.lan.Greetings
import io.github.stronghorse44.tunnels.lan.Presence
import io.github.stronghorse44.tunnels.lan.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** "Camera", "Phone", "Smart-home device": a kind as one device's label. */
internal fun kindWords(kind: HostKind): String = when (kind) {
    HostKind.ROUTER -> "Router"
    HostKind.CAMERA -> "Camera"
    HostKind.NAS -> "Storage (NAS)"
    HostKind.COMPUTER -> "Computer"
    HostKind.PHONE -> "Phone or tablet"
    HostKind.TV -> "TV or streamer"
    HostKind.SPEAKER -> "Speaker"
    HostKind.PRINTER -> "Printer"
    HostKind.IOT -> "Smart-home device"
    HostKind.UNKNOWN -> "Not identified yet"
}

/**
 * One device's detail (RJ, 2026-10-11): what the scan saw, what an earlier look found, and "Look closer", which asks this
 * device directly ([LookCloserProbe]) and shows what it says about itself. Also opens its web page or the router's, and
 * says how to track down a device Tunnels cannot name. Its notes come first: the name the user gives it, how often it is
 * here, Watch and Mine. [notes] is null while they are read or when the store cannot be; [onNotesChanged] re-reads them.
 */
@Composable
internal fun DeviceDetailDialog(
    host: LanHost,
    tunnel: HomeNetworkTunnel?,
    scanning: Boolean,
    isGateway: Boolean,
    notes: NotesView?,
    onNotesChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember(host.ip) { mutableStateOf(false) }
    var step by remember(host.ip) { mutableStateOf<String?>(null) }
    var findings by remember(host.ip) { mutableStateOf<LookCloser.Findings?>(null) }
    var error by remember(host.ip) { mutableStateOf<String?>(null) }
    var showSteps by remember(host.ip) { mutableStateOf(false) }
    var note by remember(host.ip) { mutableStateOf<String?>(null) }
    var notesNote by remember(host.ip) { mutableStateOf<String?>(null) }
    var draft by remember(host.ip) { mutableStateOf(host.label.orEmpty()) }
    var saving by remember(host.ip) { mutableStateOf(false) }

    val open = (findings?.open ?: host.openPorts).toSet()
    val webPort = LookCloserProbe.HTTP_PORTS.firstOrNull { it in open }
    val tlsPort = LookCloserProbe.TLS_PORTS.firstOrNull { it in open }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(host.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact(listOfNotNull(kindWords(host.kind), host.vendor ?: host.closer?.vendor, host.ip).distinct().joinToString(" · "))

                Section("Your notes")
                if (tunnel == null || notes == null) {
                    Line("Your notes for this network cannot be read right now.")
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(Labels.MAX_LENGTH) },
                        label = { Text("Your name for it") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                notesNote = tunnel.notes.setLabel(notes.tag, host.noteKey, draft)
                                saving = false
                                onNotesChanged()
                            }
                        },
                        enabled = !saving && Labels.clean(draft) != host.label,
                    ) { Text(if (Labels.clean(draft) == null && host.label != null) "Remove the name" else "Save the name") }

                    val entry = notes.entry(host)
                    Line(
                        if (entry == null) "Counted from your next scan: how often it is here, never when."
                        else Presence.words(entry, notes.book) + " · " + Presence.pattern(entry, notes.book, NotesView.today()).words,
                    )

                    val watched = notes.isWatched(host)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Watch this device", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Your next scans say when it comes back, opens or closes a port, or changes its name.",
                                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                            )
                        }
                        Switch(
                            checked = watched,
                            enabled = !saving,
                            onCheckedChange = { on ->
                                saving = true
                                scope.launch {
                                    notesNote = tunnel.notes.setWatched(notes.tag, host.noteKey, on, Watch.Now(host.openPorts, host.name))
                                    saving = false
                                    onNotesChanged()
                                }
                            },
                        )
                    }

                    val primary = host.primaryId
                    when {
                        host.listed == true -> Line("On your list for this network.")
                        host.listed == false && primary != null -> OutlinedButton(
                            onClick = {
                                saving = true
                                scope.launch {
                                    notesNote = withContext(Dispatchers.IO) { tunnel.census.ack(DeviceCensus.subject(host.censusTitle, primary)) }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) { Text(LanGuides.MINE_LABEL) }
                    }
                    notesNote?.let { Line(it) }
                    if (primary == null && !isGateway) Text(LanGuides.BY_ADDRESS, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                    Text(LanGuides.NOTES_KEEP, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                }

                Section("What the scan saw")
                LanSweep.describeAnswer(host.sweepAnswer)?.let { Line(it) }
                if (host.openPorts.isEmpty()) Line("No port of the regular scan's list was open.")
                host.openPorts.forEach { p -> Line(portLine(p), warn = p in PortCatalog.risky) }
                if (host.services.isNotEmpty()) Line("Announces: ${host.services.joinToString(", ")}")
                host.name?.let { Line("Name: $it") }

                host.closer?.takeIf { findings == null }?.let { kept ->
                    Section("Your last look, ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(kept.at))}")
                    Line(if (kept.quiet) "Probably ${LanGuides.QUIET_GUESS} (a guess from how it answers)" else listOfNotNull(kindWords(kept.kind), kept.vendor).joinToString(" · "))
                    kept.says?.let { Line("It said: $it") }
                    if (kept.names.isNotEmpty()) Line("Names: ${kept.names.joinToString(", ")}")
                    kept.os?.let { Line("System: ${it.words}") }
                    if (kept.openPorts.isNotEmpty()) Line("Open: ${kept.openPorts.joinToString(", ")}")
                }

                Section("Look closer")
                Text(LanGuides.CLOSER_KEEPS, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    step?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim) }
                }
                error?.let { Line(it, warn = true) }
                note?.let { Line(it) }
                findings?.let { f -> FindingsView(f) }

                Section("Can't tell what it is?")
                TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide the steps" else "How to track it down") }
                if (showSteps) LanGuides.TRACK_DOWN.forEachIndexed { i, s -> Line("${i + 1}. $s") }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (tunnel != null) {
                    Button(
                        onClick = {
                            running = true
                            error = null
                            scope.launch {
                                val known = listOfNotNull(host.name, host.vendor) + host.services
                                val outcome = tunnel.closer.look(host.ip, known) { step = it }
                                findings = outcome.findings ?: findings
                                error = outcome.error
                                running = false
                                if (outcome.findings != null && outcome.error == null) note = "Kept for 30 days. The device list places it at your next scan."
                            }
                        },
                        enabled = !running && !scanning,
                    ) { Text(if (findings == null) "Look closer" else "Look again") }
                    if (webPort != null || tlsPort != null) {
                        OutlinedButton(onClick = {
                            note = if (webPort != null) tunnel.openDevicePage(host.ip, webPort, https = false) else tunnel.openDevicePage(host.ip, tlsPort!!, https = true)
                        }) { Text("Open its web page") }
                    }
                    OutlinedButton(onClick = { note = tunnel.openRouterAdmin() }) { Text(LanGuides.ROUTER_ADMIN_LABEL) }
                }
                TextButton(onClick = onDismiss, enabled = !running) { Text("Close") }
            }
        },
    )
}

@Composable
private fun FindingsView(f: LookCloser.Findings) {
    if (f.quiet) {
        Line("Probably ${LanGuides.QUIET_GUESS} (a guess)", bold = true)
        Text(LanGuides.QUIET_WHY, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    } else {
        Line("Probably: ${kindWords(f.kind)}" + (f.vendor?.let { ", made by $it" } ?: ""), bold = true)
    }
    PortClues.of(f.open).forEach { Line("It ${it.words}.") }
    val says = listOfNotNull(
        f.httpTitle?.let { "Web page: \"$it\"" },
        f.httpServer?.let { "Web server: $it" },
        f.authRealm?.let { "Login asks for: \"$it\"" },
        f.redirect?.let { "Web page moves to: $it" },
        f.cert?.line?.let { "Certificate: $it" },
        f.ssh?.let { s -> "SSH: $s" + (Greetings.sshHint(s)?.let { ", $it" } ?: "") },
        f.rtspServer?.let { "Video server: $it" },
        f.banner?.let { "Login banner: \"$it\"" },
    )
    if (says.isNotEmpty()) {
        Section("What it says about itself")
        says.forEach { Line(it) }
    }
    val names = listOfNotNull(
        f.netbiosName?.let { "Windows name: $it" + (f.workgroup?.let { w -> " (workgroup $w)" } ?: "") },
        f.mdnsName?.let { "Its own name: $it" },
        f.routerName?.let { "Router calls it: $it" },
    )
    Section("Names")
    if (names.isEmpty()) Line("It gave no name.") else names.forEach { Line(it) }
    Section("System")
    Line(f.os?.let { "Its ping reply (TTL ${f.ttl}) is typical of ${it.words}." } ?: "It did not answer a ping, or Android did not report the reply's TTL.")
    Section("Doors")
    if (f.open.isEmpty()) Line("None of the ${PortCatalog.closer.size} ports tried was open.") else f.open.forEach { p -> Line(portLine(p), warn = p in PortCatalog.risky) }
    if (f.refused.isNotEmpty()) Line("${f.refused.size} more refused: something is there, without those services.")
}

/** "554 · Video stream (RTSP): Live video, often from a camera." */
private fun portLine(port: Int): String = PortCatalog.byPort(port)?.let { "$port · ${it.service}: ${it.note}" } ?: "$port"

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Fact(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GlassColors.dim)
}

@Composable
private fun Line(text: String, warn: Boolean = false, bold: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else null,
        color = if (warn) StatusColors.warn else GlassColors.text,
    )
}
