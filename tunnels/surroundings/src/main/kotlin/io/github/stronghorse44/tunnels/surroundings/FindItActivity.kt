package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.ble.DeviceKey
import io.github.stronghorse44.tunnels.ble.DultProtocol
import io.github.stronghorse44.tunnels.ble.Proximity
import io.github.stronghorse44.tunnels.ble.RssiMeter
import io.github.stronghorse44.tunnels.ble.RssiSmoother
import io.github.stronghorse44.tunnels.ble.ScanFailure
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.ble.Trend
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.common.TunnelScaffold
import io.github.stronghorse44.tunnels.common.TunnelsTheme
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.AppLockGate
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One identity of the searched family as heard right now. Addresses stay in the view model's memory, never here. */
data class LiveIdentity(val key: String, val rssi: Int, val state: TrackerState, val seenAt: Long)

/** What the DULT card shows. */
data class DultState(
    val busy: Boolean = false,
    val summary: DultProtocol.Summary? = null,
    val message: String? = null,
)

data class FindItState(
    val type: TrackerType,
    val lockedKey: String?,
    val running: Boolean = false,
    val available: String? = null,
    val startedAt: Long = 0L,
    val now: Long = 0L,
    /** The identity the meter follows: the locked key, else the strongest heard. */
    val target: String? = null,
    val smoothed: Double? = null,
    val trend: Trend = Trend.STEADY,
    val lastHeardAt: Long = 0L,
    val closest: Int? = null,
    val others: List<LiveIdentity> = emptyList(),
    val stopReason: String? = null,
    val dult: DultState = DultState(),
) {
    val elapsedMs: Long get() = if (startedAt == 0L) 0L else (now - startedAt).coerceAtLeast(0L)
    val fresh: Boolean get() = running && lastHeardAt > 0 && now - lastHeardAt < FindItViewModel.STALE_MS
}

/**
 * Find-it mode: a low-latency BLE scan filtered to one tracker family, kept to the foreground and to
 * ten minutes, feeding a smoothed signal meter. Keeps BluetoothDevice handles in memory for the DULT
 * card and writes a single summary row when the session ends.
 */
class FindItViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(FindItState(TrackerType.APPLE_FINDMY, null))
    val state: StateFlow<FindItState> = _state.asStateFlow()

    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val live = ConcurrentHashMap<String, LiveIdentity>()
    private val smoother = RssiSmoother()
    private var trendSample: Double? = null
    private var trendSampledAt = 0L
    private var callback: ScanCallback? = null
    private var ticker: Job? = null
    private var recorded = false
    private var dultClient: DultClient? = null
    private var dultSummary = DultProtocol.Summary()
    private var configured = false

    fun configure(type: TrackerType, key: String?) {
        if (configured) return
        configured = true
        _state.value = FindItState(type, key)
        start()
    }

    fun start() {
        val s = _state.value
        if (s.running) return
        val context = getApplication<Application>()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val available = when {
            adapter == null -> SurroundingsKeys.AVAILABLE_NO_ADAPTER
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED -> SurroundingsKeys.AVAILABLE_NO_PERMISSION
            !adapter.isEnabled -> SurroundingsKeys.AVAILABLE_OFF
            else -> SurroundingsKeys.AVAILABLE_YES
        }
        val scanner = adapter?.bluetoothLeScanner
        if (available != SurroundingsKeys.AVAILABLE_YES || scanner == null) {
            _state.update { it.copy(available = available.takeIf { a -> a != SurroundingsKeys.AVAILABLE_YES } ?: SurroundingsKeys.AVAILABLE_OFF, running = false) }
            return
        }
        val now = System.currentTimeMillis()
        smoother.reset()
        trendSample = null
        live.clear()
        recorded = false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
            override fun onScanFailed(errorCode: Int) {
                stop(ScanFailure.describe(errorCode))
            }
        }
        val filters = TrackerSignatures.of(s.type).filters.mapNotNull { spec -> runCatching { with(BleWindow) { spec.toPlatform() } }.getOrNull() }
        if (filters.isEmpty()) {
            _state.update { it.copy(available = SurroundingsKeys.AVAILABLE_FAILED, running = false) }
            return
        }
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (_: SecurityException) {
            _state.update { it.copy(available = SurroundingsKeys.AVAILABLE_NO_PERMISSION, running = false) }
            return
        } catch (e: Exception) {
            _state.update { it.copy(available = SurroundingsKeys.AVAILABLE_FAILED, running = false) }
            return
        }
        callback = cb
        _state.update {
            it.copy(
                running = true, available = SurroundingsKeys.AVAILABLE_YES, startedAt = now, now = now, stopReason = null, smoothed = null,
                trend = Trend.STEADY, lastHeardAt = 0L, closest = null, target = it.lockedKey, others = emptyList(),
            )
        }
        ticker = viewModelScope.launch {
            while (isActive && _state.value.running) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun handle(result: ScanResult) {
        val s = _state.value
        if (!s.running) return
        try {
            val address = result.device?.address ?: return
            val record = result.scanRecord ?: return
            val match = TrackerSignatures.match(with(BleWindow) { record.toAdvertisement() }) ?: return
            if (match.type != s.type) return
            val key = DeviceKey.of(address, match.idSource)
            val now = System.currentTimeMillis()
            devices[key] = result.device
            live[key] = LiveIdentity(key, result.rssi, match.state, now)
            val target = s.target ?: s.lockedKey ?: key
            if (target == key) {
                val smoothed = smoother.add(result.rssi)
                _state.update {
                    it.copy(
                        target = target,
                        smoothed = smoothed,
                        lastHeardAt = now,
                        closest = maxOf(it.closest ?: Int.MIN_VALUE, result.rssi).takeIf { c -> c != Int.MIN_VALUE },
                    )
                }
            } else if (s.target == null) {
                _state.update { it.copy(target = target) }
            }
        } catch (_: Exception) {
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val s = _state.value
        if (now - s.startedAt >= MAX_DURATION_MS) {
            stop("Stopped after ${MAX_DURATION_MS / 60_000} minutes. Start again if you need more time.")
            return
        }
        if (getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
            stop("Bluetooth was turned off.")
            return
        }
        val smoothed = s.smoothed
        var trend = s.trend
        if (smoothed != null && now - trendSampledAt >= TREND_WINDOW_MS) {
            trend = Trend.of(trendSample, smoothed)
            trendSample = smoothed
            trendSampledAt = now
        }
        // After a gap the average starts afresh: the tag moved or slept, and old readings would mislead.
        if (smoothed != null && now - s.lastHeardAt > STALE_MS) {
            smoother.reset()
            trendSample = null
        }
        val recent = live.values.filter { now - it.seenAt < OTHERS_TTL_MS && it.key != s.target }.sortedByDescending { it.rssi }
        // When nothing is locked and the followed identity went quiet, follow the strongest voice instead.
        var target = s.target
        if (s.lockedKey == null && target != null && now - s.lastHeardAt > RETARGET_MS && recent.isNotEmpty()) {
            target = recent.first().key
            smoother.reset()
            trendSample = null
        }
        _state.update { it.copy(now = now, trend = trend, others = recent, target = target, smoothed = smoother.value) }
    }

    /** Follow another identity heard nearby. */
    fun follow(key: String) {
        smoother.reset()
        trendSample = null
        _state.update { it.copy(target = key, lockedKey = key, smoothed = null, lastHeardAt = 0L) }
    }

    fun stop(reason: String?) {
        val cb = callback
        callback = null
        ticker?.cancel()
        ticker = null
        if (cb != null) {
            runCatching { getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        val s = _state.value
        if (s.running || cb != null) {
            _state.update { it.copy(running = false, stopReason = reason, now = System.currentTimeMillis()) }
            record(s.type, (System.currentTimeMillis() - s.startedAt).coerceAtLeast(0L) / 60_000, s.closest)
        }
    }

    /** The one row a session leaves: minutes searched and the strongest signal. */
    private fun record(type: TrackerType, minutes: Long, closest: Int?) {
        if (recorded) return
        recorded = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                TunnelsStore.get(getApplication()).recordEvent(
                    SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.EVENT_FINDIT, SurroundingsKeys.typeSubject(type), SurroundingsKeys.findItSummary(type, minutes, closest),
                )
            }
        }
    }

    // DULT (experimental): talks to the device behind the followed identity.

    fun dultQuery() = dult { client ->
        dultSummary = client.readInformation()
        val got = listOfNotNull(dultSummary.manufacturer, dultSummary.model, dultSummary.category, dultSummary.batteryLevel)
        _state.update { it.copy(dult = it.dult.copy(summary = dultSummary, message = if (got.isEmpty()) "Connected, but the tag answered none of the information requests." else null)) }
    }

    fun dultSound(start: Boolean) = dult { client ->
        val opcode = if (start) DultProtocol.SOUND_START else DultProtocol.SOUND_STOP
        val r = client.request(opcode)
        val message = when {
            r == null -> "No answer from the tag."
            r is DultProtocol.Response.CommandResponse && r.ok -> if (start) "The tag accepted the request and should be ringing." else "Stopped."
            r is DultProtocol.Response.CommandResponse -> DultProtocol.explainRefusal(opcode, r.status)
            else -> "Unexpected answer."
        }
        if (start && r is DultProtocol.Response.CommandResponse && r.ok) dultSummary = dultSummary.copy(soundPlayed = true)
        _state.update { it.copy(dult = it.dult.copy(summary = dultSummary, message = message)) }
    }

    fun dultIdentifier() = dult { client ->
        val r = client.request(DultProtocol.GET_IDENTIFIER)
        val message = when (r) {
            null -> "No answer from the tag."
            is DultProtocol.Response.Identifier -> {
                dultSummary = dultSummary.with(r)
                "The tag handed out a ${r.length}-byte encrypted identifier. Only its maker can turn it into a serial; Tunnels keeps nothing of it. " +
                    "For a readable serial use NFC or the tag's own label."
            }
            is DultProtocol.Response.CommandResponse -> DultProtocol.explainRefusal(DultProtocol.GET_IDENTIFIER, r.status)
            else -> "Unexpected answer."
        }
        _state.update { it.copy(dult = it.dult.copy(summary = dultSummary, message = message)) }
    }

    private fun dult(block: suspend (DultClient) -> Unit) {
        if (_state.value.dult.busy) return
        val key = _state.value.target
        val device = key?.let { devices[it] }
        if (device == null) {
            _state.update { it.copy(dult = it.dult.copy(message = "No tag is being followed yet: wait for a signal first.")) }
            return
        }
        _state.update { it.copy(dult = it.dult.copy(busy = true, message = null)) }
        viewModelScope.launch {
            try {
                val existing = dultClient?.takeIf { it.connected }
                if (existing == null) dultClient?.close()
                val client = existing ?: DultClient(getApplication(), device).also { dultClient = it }
                if (existing == null) {
                    val error = withContext(Dispatchers.IO) { client.open() }
                    if (error != null) {
                        client.close()
                        dultClient = null
                        _state.update { it.copy(dult = it.dult.copy(busy = false, message = error)) }
                        return@launch
                    }
                }
                withContext(Dispatchers.IO) { block(client) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dult = it.dult.copy(message = "DULT failed: ${e.javaClass.simpleName}")) }
            } finally {
                _state.update { it.copy(dult = it.dult.copy(busy = false)) }
                recordDult()
            }
        }
    }

    private var lastDultLine: String? = null

    private fun recordDult() {
        val line = dultSummary.line()
        if (line == lastDultLine || dultSummary == DultProtocol.Summary()) return
        lastDultLine = line
        val type = _state.value.type
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TunnelsStore.get(getApplication()).recordEvent(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.EVENT_DULT, SurroundingsKeys.typeSubject(type), line) }
        }
    }

    override fun onCleared() {
        stop(null)
        dultClient?.close()
        dultClient = null
        devices.clear()
        super.onCleared()
    }

    companion object {
        const val MAX_DURATION_MS = 10 * 60_000L
        const val TICK_MS = 500L
        /** A reading older than this no longer drives the haptics. */
        const val STALE_MS = 3_000L
        const val TREND_WINDOW_MS = 1_500L
        const val OTHERS_TTL_MS = 15_000L
        const val RETARGET_MS = 8_000L
    }
}

/**
 * Hosts find-it mode for one tracker family. Foreground only: leaving the screen (another app, the app
 * lock, the home screen) stops the scan, and coming back resumes it; a rotation keeps it running.
 */
class FindItActivity : ComponentActivity() {
    private val vm: FindItViewModel by viewModels()
    private var stoppedByLifecycle = false

    /** For the smoke test: what the screen currently shows. */
    internal val currentState: FindItState get() = vm.state.value

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val type = TrackerType.bySlug(intent.getStringExtra(EXTRA_TYPE).orEmpty()) ?: run { finish(); return }
        vm.configure(type, intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotBlank() })
        setContent { TunnelsTheme { AppLockGate { FindItScreen(vm, onBack = ::finish) } } }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedByLifecycle) {
            stoppedByLifecycle = false
            vm.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        if (vm.state.value.running) {
            vm.stop("Paused because the screen was left. Find-it mode only runs while you watch it.")
            stoppedByLifecycle = true
        }
    }

    companion object {
        const val EXTRA_TYPE = "io.github.stronghorse44.tunnels.surroundings.extra.TYPE"
        const val EXTRA_KEY = "io.github.stronghorse44.tunnels.surroundings.extra.KEY"

        fun intent(context: Context, type: TrackerType, key: String? = null): Intent =
            Intent(context, FindItActivity::class.java).putExtra(EXTRA_TYPE, type.slug).putExtra(EXTRA_KEY, key)
    }
}

private val line: Color get() = LineColors.of(MetroLine.NETWORK)

@Composable
fun FindItScreen(vm: FindItViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    TunnelScaffold("Find it · ${state.type.label}", MetroLine.NETWORK, onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MeterCard(state, vm)
            OthersCard(state, vm)
            DultCard(state, vm)
            Text(
                "Nothing of this session is stored except one line: how long you searched and the strongest signal. Addresses live in memory until you leave.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun MeterCard(state: FindItState, vm: FindItViewModel) {
    val context = LocalContext.current
    // Short pulses, faster as the signal rises; silent while the tag is not heard. The Vibrator is used
    // rather than view haptics so the pulses come through with the system touch-feedback switch off.
    LaunchedEffect(state.running) {
        val vibrator = runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()
        val tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        while (vm.state.value.running) {
            val s = vm.state.value
            val rssi = s.smoothed
            if (s.fresh && rssi != null) {
                runCatching { vibrator?.vibrate(tick) }
                delay(RssiMeter.pulseIntervalMs(rssi))
            } else {
                delay(400)
            }
        }
    }
    val rssi = state.smoothed
    val fraction = rssi?.let(RssiMeter::fraction) ?: 0f
    val proximity = rssi?.let { Proximity.of(Math.round(it).toInt()) }
    val heardOnce = state.lastHeardAt > 0L
    val tint = when {
        !state.running -> GlassColors.dim
        !state.fresh -> GlassColors.dim
        proximity == Proximity.NEAR -> StatusColors.ok
        proximity == Proximity.MEDIUM -> StatusColors.warn
        else -> line
    }
    GlassPanel(Modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Signal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.target?.let { "following ${state.type.label} · $it" } ?: "waiting for a ${state.type.label} signal",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim,
                    )
                }
                Text(SurroundingsFormat.elapsed(state.elapsedMs), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GlassColors.dim)
            }
            Text(
                if (rssi != null && state.fresh) "${Math.round(rssi)} dBm" else if (heardOnce && state.running) "lost" else "—",
                style = MaterialTheme.typography.displaySmall, color = tint, fontFamily = FontFamily.Monospace,
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp), color = tint)
            Text(
                when {
                    !state.running -> state.stopReason ?: "Stopped."
                    !heardOnce -> "Listening. A tag advertises every one to two seconds; bring the phone to where you suspect it."
                    !state.fresh -> "No signal for a few seconds: the tag moved out of range or paused."
                    else -> "${proximity!!.label.replaceFirstChar { it.uppercase() }} (${proximity.hint}) · getting ${state.trend.label}" +
                        (state.closest?.let { " · best so far $it dBm" } ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.available?.takeIf { it != SurroundingsKeys.AVAILABLE_YES }?.let {
                Text("Bluetooth: $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) OutlinedButton(onClick = { vm.stop("Stopped.") }) { Text("Stop") }
                else Button(onClick = { vm.start() }) { Text("Start again") }
            }
            Text(
                "Pulses come faster as you get closer. The meter smooths the signal: walk slowly, hold the phone still for a second, and turn around to " +
                    "find the direction it gets warmer. Low-power tags read \"near\" within about a metre.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun OthersCard(state: FindItState, vm: FindItViewModel) {
    if (state.others.isEmpty()) return
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Other ${state.type.label} identities heard", style = MaterialTheme.typography.titleSmall)
            state.others.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = line, modifier = Modifier.weight(0.35f))
                    Text("${o.rssi} dBm · ${Proximity.of(o.rssi).label} · ${SurroundingsFormat.stateLabel(o.state)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(0.65f))
                    TextButton(onClick = { vm.follow(o.key) }) { Text("Follow") }
                }
            }
            Text("A tag changes its address every few minutes, so one physical tag can appear as several identities over time.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DultCard(state: FindItState, vm: FindItViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke() else refusal = "Bluetooth connect was not allowed, so Tunnels cannot talk to the tag."
        pending = null
    }
    fun withPermission(action: () -> Unit) {
        refusal = null
        if (granted()) action() else {
            pending = action
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    val d = state.dult
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ask the tag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("experimental", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = StatusColors.warn)
            }
            Text(
                "Tags that implement the IETF unwanted-tracker draft (DULT) let any phone connect without pairing to read maker, model and " +
                    "battery and to make them ring while they are away from their owner. Which tags do is not yet verified with any; this connects " +
                    "to the identity being followed and to nothing else.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.dim,
            )
            if (!granted()) {
                Text("Needs Bluetooth connect: to open a connection to this one tag. Asked only when you tap below.", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
            }
            d.summary?.let { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Fact("maker", s.manufacturer)
                    Fact("model", s.model)
                    Fact("category", s.category)
                    Fact("battery", listOfNotNull(s.batteryLevel, s.batteryType).joinToString(", ").ifBlank { null })
                    Fact("firmware", s.firmware)
                    Fact("can", s.capabilities.joinToString(", ").ifBlank { null })
                }
            }
            (refusal ?: d.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (refusal != null) StatusColors.warn else GlassColors.text) }
            if (d.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { withPermission { vm.dultQuery() } }, enabled = !d.busy && state.target != null) { Text("Query tag") }
                OutlinedButton(onClick = { withPermission { vm.dultSound(true) } }, enabled = !d.busy && state.target != null) { Text("Play sound") }
                OutlinedButton(onClick = { withPermission { vm.dultSound(false) } }, enabled = !d.busy && state.target != null) { Text("Stop sound") }
                OutlinedButton(onClick = { withPermission { vm.dultIdentifier() } }, enabled = !d.busy && state.target != null) { Text("Read identifier") }
            }
            Text(
                "Per the draft a tag answers Play sound only while it is in separated mode, and Read identifier only for " +
                    "${DultProtocol.IDENTIFIER_READ_WINDOW_MINUTES} minutes after a user action on the tag itself (often holding its button). " +
                    "Only a one-line summary (maker, battery, whether it rang) is kept.",
                style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value == null) return
    Row {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.width(80.dp))
        Spacer(Modifier.width(6.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
