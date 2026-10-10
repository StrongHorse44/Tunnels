package io.github.stronghorse44.tunnels.emissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "EmissionsBag"

/**
 * Takes the bag test's two samples (see [BagTest]): outside the bag, a countdown to put the phone in, then inside it.
 * Cellular comes from the modem's service state and signal level (no permission), Wi-Fi from one fresh scan
 * (NEARBY_WIFI_DEVICES, and Android's location switch) and the connected network, Bluetooth from an unfiltered scan
 * (BLUETOOTH_SCAN; the screen stays on during the test, or Android pauses it). Counts only, held in memory; addresses
 * are counted in a set that is dropped when the window ends.
 */
class BagTestRunner(private val context: Context) {
    enum class Phase(val label: String) {
        OUTSIDE("Listening outside the bag"),
        COUNTDOWN("Put the phone in the bag and close it"),
        INSIDE("Listening inside the bag"),
    }

    data class Progress(val phase: Phase, val secondsLeft: Int)

    /** Permissions the test asks for when it starts; without them those channels are reported as not tested. */
    val permissions: List<String> = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)

    suspend fun measure(onProgress: (Progress) -> Unit): BagTest.Result {
        val outside = sample(BagTest.OUTSIDE_MILLIS, settleMillis = 0L, Phase.OUTSIDE, onProgress)
        for (s in BagTest.COUNTDOWN_SECONDS downTo 1) {
            onProgress(Progress(Phase.COUNTDOWN, s))
            delay(1_000L)
        }
        buzz(1)
        val inside = sample(BagTest.INSIDE_MILLIS, BagTest.SETTLE_MILLIS, Phase.INSIDE, onProgress)
        buzz(2)
        return BagTest.judge(outside, inside)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * One window of [durationMillis]. The cellular and connected-Wi-Fi values are the last ones of the window; the
     * Wi-Fi scan is asked for and the Bluetooth devices counted only after [settleMillis].
     */
    private suspend fun sample(durationMillis: Long, settleMillis: Long, phase: Phase, onProgress: (Progress) -> Unit): BagTest.Sample {
        val cell = CellWatch(context).apply { start() }
        val ble = BleCount(context)
        val wifi = WifiScanWatch(context)
        try {
            val start = SystemClock.elapsedRealtime()
            var scanning = false
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed >= durationMillis) break
                if (!scanning && elapsed >= settleMillis) {
                    scanning = true
                    if (granted(Manifest.permission.BLUETOOTH_SCAN)) ble.start()
                    if (granted(Manifest.permission.NEARBY_WIFI_DEVICES)) wifi.request()
                }
                onProgress(Progress(phase, ((durationMillis - elapsed + 999) / 1_000).toInt()))
                delay(250L)
            }
            return BagTest.Sample(
                cellInService = cell.inService,
                cellLevel = cell.level,
                wifiNetworks = wifi.freshCount(),
                wifiLinked = wifiLinked(),
                bleDevices = ble.count(),
            )
        } finally {
            cell.stop()
            ble.stop()
            wifi.stop()
        }
    }

    /** Connected to a Wi-Fi network now; null when Wi-Fi is off. */
    private fun wifiLinked(): Boolean? {
        val wm = context.getSystemService(WifiManager::class.java) ?: return null
        if (!runCatching { wm.isWifiEnabled }.getOrDefault(false)) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }.getOrNull()
    }

    private fun buzz(times: Int) {
        val vibrator = runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull() ?: return
        // Off/on pairs: one buzz when listening starts in the bag, two when it is done.
        val pattern = buildList { repeat(times) { i -> add(if (i == 0) 0L else 250L); add(350L) } }.toLongArray()
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** The modem's latest service state and signal level, from a telephony callback (no permission). */
    private class CellWatch(private val context: Context) {
        @Volatile var inService: Boolean? = null
        @Volatile var level: Int? = null
        private var tm: TelephonyManager? = null
        private val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener, TelephonyCallback.SignalStrengthsListener {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                inService = serviceState.state == ServiceState.STATE_IN_SERVICE
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                level = signalStrength.level
            }
        }

        fun start() {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return
            val manager = context.getSystemService(TelephonyManager::class.java) ?: return
            try {
                manager.registerTelephonyCallback(context.mainExecutor, callback)
                tm = manager
            } catch (e: Exception) {
                // Without the callback the level alone still tells: signal strength needs no permission.
                Log.w(TAG, "telephony callback refused: ${e.javaClass.simpleName}")
                level = runCatching { manager.signalStrength?.level }.getOrNull()
                inService = level?.let { it > 0 }
            }
        }

        fun stop() {
            tm?.let { runCatching { it.unregisterTelephonyCallback(callback) } }
            tm = null
        }
    }

    /** An unfiltered Bluetooth LE scan that counts distinct advertisers; addresses never leave the set. */
    private class BleCount(private val context: Context) {
        private val seen = ConcurrentHashMap.newKeySet<String>()
        private var started = false
        private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.device?.address?.let { seen += it }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> r.device?.address?.let { seen += it } }
            }
        }

        fun start() {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return
            val s = adapter.bluetoothLeScanner ?: return
            try {
                s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                scanner = s
                started = true
            } catch (e: Exception) {
                Log.w(TAG, "BLE scan refused: ${e.javaClass.simpleName}")
            }
        }

        /** Devices heard; null when the scan never ran. */
        fun count(): Int? = if (started) seen.size else null

        fun stop() {
            scanner?.let { s -> runCatching { s.stopScan(callback) } }
            scanner = null
            seen.clear()
        }
    }

    /** Asks for one Wi-Fi scan and counts the networks in results newer than the request. */
    private class WifiScanWatch(private val context: Context) {
        @Volatile private var completed = false
        private var requestedAtMicros = 0L
        private var receiver: BroadcastReceiver? = null

        fun request() {
            val wm = context.getSystemService(WifiManager::class.java) ?: return
            if (!runCatching { wm.isWifiEnabled }.getOrDefault(false)) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true) completed = true
                }
            }
            try {
                context.registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
                receiver = r
                requestedAtMicros = SystemClock.elapsedRealtime() * 1_000L
                @Suppress("DEPRECATION")
                wm.startScan()
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi scan refused: ${e.javaClass.simpleName}")
            }
        }

        /** Networks in a scan that finished after the request; null when no fresh scan came back. */
        fun freshCount(): Int? {
            if (!completed) return null
            val wm = context.getSystemService(WifiManager::class.java) ?: return null
            val results = try {
                wm.scanResults.orEmpty()
            } catch (_: SecurityException) {
                return null
            }
            return results.count { it.timestamp >= requestedAtMicros }
        }

        fun stop() {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
        }
    }
}
