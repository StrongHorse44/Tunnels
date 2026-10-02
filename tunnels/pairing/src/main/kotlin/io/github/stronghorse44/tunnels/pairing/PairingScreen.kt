package io.github.stronghorse44.tunnels.pairing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.ToolScaffold
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dates = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

@Composable
fun PairingScreen(vm: PairingViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tint = LineColors.of(MetroLine.SYSTEM)
    val step = state.step
    BackHandler(enabled = step != Step.Home) { vm.home() }
    ToolScaffold(title = "Second phone", subtitle = "Check a phone from outside it", tint = tint, onBack = { if (step == Step.Home) onBack() else vm.home() }) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                Step.Home -> Home(state.pins, vm)
                is Step.Challenge -> VerifierChallenge(step, vm)
                is Step.Result -> ResultView(step.verdict, vm)
                Step.ScanChallenge -> {
                    Text("Point this phone at the code on the verifying phone (Tunnels, Second phone, Check another phone).", style = MaterialTheme.typography.bodyMedium)
                    CameraGate { QrScanner(vm::onChallengeScanned, Modifier.fillMaxWidth().aspectRatio(1f)) }
                }
                Step.Working -> Text("This phone's security chip is attesting a key for the challenge…", style = MaterialTheme.typography.bodyMedium)
                is Step.Answer -> AnswerView(step, vm)
                is Step.Error -> {
                    Text(step.message, style = MaterialTheme.typography.bodyMedium, color = StatusColors.warn)
                    Button(onClick = vm::home) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun Home(pins: List<Pin>, vm: PairingViewModel) {
    Text(
        "Silicon reads this phone's hardware attestation on this phone, and a compromised OS could fake what an app here sees. " +
            "A second phone you trust can check it from outside: the security chip signs the boot state, the OS signing key and the " +
            "patch level for a fresh challenge, and the second phone verifies the signature up to Google's root. Two QR codes, no network.",
        style = MaterialTheme.typography.bodyMedium,
        color = GlassColors.dim,
    )
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check another phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Use this phone as the verifier. The first check pairs the other phone; later checks prove it is the same phone and OS.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            Button(onClick = vm::startVerifying) { Text("Check another phone") }
        }
    }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Have this phone checked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Scan the code a verifying phone shows, then let it scan this phone's answer.", style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
            OutlinedButton(onClick = vm::startBeingChecked) { Text("Have this phone checked") }
        }
    }
    if (pins.isNotEmpty()) {
        Text("Phones this one has paired", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        pins.sortedByDescending { it.lastAuditAt }.forEach { pin ->
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pin.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "paired ${dates.format(pin.pairedAt)} · last checked ${dates.format(pin.lastAuditAt)}",
                            style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
                        )
                    }
                    TextButton(onClick = { vm.forget(pin) }) { Text("Forget", color = GlassColors.dim) }
                }
            }
        }
    }
}

@Composable
private fun VerifierChallenge(step: Step.Challenge, vm: PairingViewModel) {
    if (!step.scanning) {
        Text("1. On the other phone open Tunnels, Second phone, Have this phone checked, and scan this code.", style = MaterialTheme.typography.bodyMedium)
        Bright { QrCode(step.challenge.encode(), Modifier.fillMaxWidth()) }
        Button(onClick = vm::scanAnswer, modifier = Modifier.fillMaxWidth()) { Text("2. Scan its answer") }
    } else {
        Text("2. Point this phone at the other phone's answer. It cycles through its parts; hold steady until all are read.", style = MaterialTheme.typography.bodyMedium)
        CameraGate { QrScanner(vm::onAnswerScanned, Modifier.fillMaxWidth().aspectRatio(1f)) }
        if (step.total > 0) Text("Read ${step.received} of ${step.total} parts", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GlassColors.dim)
    }
}

@Composable
private fun AnswerView(step: Step.Answer, vm: PairingViewModel) {
    var index by remember(step) { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        while (step.parts.size > 1) {
            delay(PART_MS)
            index = (index + 1) % step.parts.size
        }
    }
    Text(
        if (step.kind == PairingProtocol.Kind.PAIR) "Let the verifying phone scan this. It pairs with this phone's security chip."
        else "Let the verifying phone scan this. It checks this is the phone it paired with.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Bright { QrCode(step.parts[index], Modifier.fillMaxWidth()) }
    if (step.parts.size > 1) Text("Part ${index + 1} of ${step.parts.size}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GlassColors.dim)
    if (step.kind == PairingProtocol.Kind.AUDIT) {
        TextButton(onClick = vm::pairAgain) { Text("The verifier says it is not paired? Pair again") }
    }
    Button(onClick = vm::home) { Text("Done") }
}

@Composable
private fun ResultView(verdict: Verdict, vm: PairingViewModel) {
    val (headline, color) = when (verdict.outcome) {
        Verdict.Outcome.PAIRED -> "Paired: the other phone checks out" to StatusColors.ok
        Verdict.Outcome.VERIFIED -> "Verified: same phone, same OS, checks out" to StatusColors.ok
        Verdict.Outcome.FAILED -> "Not verified" to StatusColors.blocker
    }
    Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    verdict.pin?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim) }
    GlassPanel(Modifier.fillMaxWidth(), tint = color) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            verdict.checks.forEach { c ->
                Row {
                    Text(
                        when (c.status) {
                            Verdict.Status.PASS -> "ok  "
                            Verdict.Status.WARN -> "warn"
                            Verdict.Status.FAIL -> "FAIL"
                        },
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = when (c.status) {
                            Verdict.Status.PASS -> StatusColors.ok
                            Verdict.Status.WARN -> StatusColors.warn
                            Verdict.Status.FAIL -> StatusColors.blocker
                        },
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                    }
                }
            }
        }
    }
    if (verdict.failed) {
        Text(
            "If this is the phone you paired and you did not reinstall its OS, treat it as compromised. Otherwise forget the old pairing and pair again.",
            style = MaterialTheme.typography.bodySmall, color = StatusColors.warn,
        )
    }
    Button(onClick = vm::home) { Text("Done") }
}

/**
 * Asks for the camera only here, when a scan starts, with the reason first. Shows [content] once granted.
 */
@Composable
private fun CameraGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; refused = !ok }
    if (granted) {
        content()
    } else {
        Box(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tunnels needs the camera to read the other phone's code. Frames are decoded in memory and never stored.", style = MaterialTheme.typography.bodyMedium)
                if (refused) Text("Camera access was refused. Allow it in App info, Permissions, Camera.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}

/** Full brightness and the screen kept on while a code is shown, restored afterwards. */
@Composable
private fun Bright(content: @Composable () -> Unit) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        view.keepScreenOn = true
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
        onDispose {
            view.keepScreenOn = false
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = before ?: -1f } }
        }
    }
    content()
}

private const val PART_MS = 900L
