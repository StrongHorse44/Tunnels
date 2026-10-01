package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.stronghorse44.tunnels.ble.Advertisement
import io.github.stronghorse44.tunnels.ble.BleUuid
import io.github.stronghorse44.tunnels.ble.CellHeuristics
import io.github.stronghorse44.tunnels.ble.CellSummary
import io.github.stronghorse44.tunnels.ble.CellTech
import io.github.stronghorse44.tunnels.ble.DeviceKey
import io.github.stronghorse44.tunnels.ble.ScanFilterSpec
import io.github.stronghorse44.tunnels.ble.SightingRecord
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.ble.WifiHeuristics
import io.github.stronghorse44.tunnels.ble.WifiNetwork
import io.github.stronghorse44.tunnels.ble.WifiSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "Surroundings"

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Android hands out Wi-Fi scan results and the cell list only while the device's location toggle is on. */
private fun Context.locationEnabled(): Boolean =
    runCatching { getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true

/**
 * What one BLE scan window produced: availability, how many distinct devices advertised, which were
 * trackers. [devicesTotal] counts every advertiser only for an unfiltered window; a filtered one sees
 * trackers alone.
 */
data class BleWindowResult(
    val available: String,
    val devicesTotal: Int,
    val sightings: List<SightingRecord>,
)

/**
 * One BLE scan window. Addresses live only in memory for the length of the window: each tracker leaves
 * as a [SightingRecord] under a pseudonymous key, everything else only adds to the device count.
 *
 * Screen-off behaviour: since Android 8.1 the Bluetooth stack suspends an unfiltered scan while the
 * screen is off and resumes it on screen-on, silently (no onScanFailed). A window that must work from a
 * pocket, like the background monitor's, therefore passes [TrackerSignatures.scanFilters]; the manual
 * scan runs with the screen on and may stay unfiltered to count every device around.
 */
object BleWindow {
    private class Hit(val type: TrackerType, var state: TrackerState, var battery: String?) {
        var rssiSum = 0L
        var count = 0
    }

    /**
     * Listens for [durationMs]. [filters] null means unfiltered (every advertiser counted; screen must be
     * on); a list restricts the stack to those advertisements, which is what keeps a scan alive with the
     * screen off.
     */
    suspend fun scan(
        context: Context,
        session: String,
        durationMs: Long,
        filters: List<ScanFilterSpec>? = null,
        onSecond: (Int, Int) -> Unit = { _, _ -> },
    ): BleWindowResult {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BleWindowResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, 0, emptyList())
        if (!context.granted(Manifest.permission.BLUETOOTH_SCAN)) return BleWindowResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, 0, emptyList())
        if (!adapter.isEnabled) return BleWindowResult(SurroundingsKeys.AVAILABLE_OFF, 0, emptyList())
        val scanner = adapter.bluetoothLeScanner ?: return BleWindowResult(SurroundingsKeys.AVAILABLE_OFF, 0, emptyList())

        val addresses = ConcurrentHashMap.newKeySet<String>()
        val hits = ConcurrentHashMap<String, Hit>()
        var failure: Int? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                failure = errorCode
            }

            private fun handle(result: ScanResult) {
                try {
                    val address = result.device?.address ?: return
                    addresses += address
                    val record = result.scanRecord ?: return
                    val match = TrackerSignatures.match(record.toAdvertisement()) ?: return
                    val key = DeviceKey.of(address, match.idSource)
                    val hit = hits.getOrPut(key) { Hit(match.type, match.state, match.battery) }
                    synchronized(hit) {
                        hit.rssiSum += result.rssi
                        hit.count++
                        // Separated beats everything else within one window: a tag that said it is away is away.
                        if (match.state == TrackerState.SEPARATED || hit.state == TrackerState.UNKNOWN) hit.state = match.state
                        match.battery?.let { hit.battery = it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "scan result skipped: ${e.javaClass.simpleName}")
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val platformFilters = filters?.mapNotNull { spec ->
            try {
                spec.toPlatform()
            } catch (e: Exception) {
                Log.w(TAG, "filter skipped: ${e.javaClass.simpleName}")
                null
            }
        }
        // A filter list that came out empty would silently mean "unfiltered"; refuse rather than scan the wrong way.
        if (filters != null && platformFilters.isNullOrEmpty()) return BleWindowResult(SurroundingsKeys.AVAILABLE_FAILED, 0, emptyList())
        try {
            scanner.startScan(platformFilters, settings, callback)
        } catch (_: SecurityException) {
            return BleWindowResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, 0, emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.javaClass.simpleName}")
            return BleWindowResult(SurroundingsKeys.AVAILABLE_FAILED, 0, emptyList())
        }
        try {
            val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
            for (s in 1..seconds) {
                delay(durationMs / seconds)
                onSecond(s, seconds)
                if (failure != null) break
            }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed: ${e.javaClass.simpleName}")
            }
        }
        val now = System.currentTimeMillis()
        val sightings = hits.map { (key, hit) ->
            SightingRecord(
                session = session,
                at = now,
                type = hit.type,
                key = key,
                state = hit.state,
                rssi = if (hit.count == 0) 0 else (hit.rssiSum / hit.count).toInt(),
                count = hit.count,
                battery = hit.battery,
            )
        }
        val available = if (failure != null && addresses.isEmpty()) SurroundingsKeys.AVAILABLE_FAILED else SurroundingsKeys.AVAILABLE_YES
        return BleWindowResult(available, addresses.size, sightings)
    }

    /** The platform ScanFilter for one spec; same semantics as [ScanFilterSpec.accepts]. */
    private fun ScanFilterSpec.toPlatform(): ScanFilter = when (this) {
        is ScanFilterSpec.ManufacturerData -> {
            val m = mask
            if (m == null) ScanFilter.Builder().setManufacturerData(companyId, data).build()
            else ScanFilter.Builder().setManufacturerData(companyId, data, m).build()
        }
        is ScanFilterSpec.ServiceUuid -> ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BleUuid.full(short16))).build()
        is ScanFilterSpec.ServiceData -> {
            val uuid = ParcelUuid.fromString(BleUuid.full(short16))
            val m = mask
            if (m == null) ScanFilter.Builder().setServiceData(uuid, data).build()
            else ScanFilter.Builder().setServiceData(uuid, data, m).build()
        }
    }

    private fun android.bluetooth.le.ScanRecord.toAdvertisement(): Advertisement {
        val mfr = HashMap<Int, ByteArray>()
        manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) sparse.valueAt(i)?.let { mfr[sparse.keyAt(i)] = it }
        }
        return Advertisement(
            manufacturerData = mfr,
            serviceUuids = serviceUuids.orEmpty().map { it.uuid.toString().lowercase() },
            serviceData = serviceData.orEmpty().entries.associate { it.key.uuid.toString().lowercase() to it.value },
        )
    }
}

data class WifiProbeResult(val available: String, val summaries: List<WifiSummary>)

/** Reads the cached Wi-Fi scan results (one fresh scan is requested first, within Android's throttle) and summarises them. */
object WifiProbe {
    private const val REDACTED_BSSID = "02:00:00:00:00:00"

    /** Asks for a fresh scan. Android throttles this; a refused request just means the cache is used. */
    fun requestScan(context: Context) {
        if (!context.granted(Manifest.permission.NEARBY_WIFI_DEVICES)) return
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            wifi.startScan()
        } catch (e: Exception) {
            Log.w(TAG, "startScan refused: ${e.javaClass.simpleName}")
        }
    }

    fun read(context: Context): WifiProbeResult {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) return WifiProbeResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, emptyList())
        val wifi = context.getSystemService(WifiManager::class.java) ?: return WifiProbeResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, emptyList())
        if (!context.granted(Manifest.permission.NEARBY_WIFI_DEVICES)) return WifiProbeResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, emptyList())
        if (!wifi.isWifiEnabled) return WifiProbeResult(SurroundingsKeys.AVAILABLE_OFF, emptyList())
        // scanResults stays location-gated even with NEARBY_WIFI_DEVICES: with the toggle off it is just an empty list.
        if (!context.locationEnabled()) return WifiProbeResult(SurroundingsKeys.AVAILABLE_LOCATION_OFF, emptyList())
        val results = try {
            wifi.scanResults.orEmpty()
        } catch (_: SecurityException) {
            return WifiProbeResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "scanResults failed: ${e.javaClass.simpleName}")
            return WifiProbeResult(SurroundingsKeys.AVAILABLE_FAILED, emptyList())
        }
        val (currentBssid, currentSubject) = current(wifi)
        val networks = results.mapNotNull { r ->
            try {
                val bssid = r.BSSID ?: return@mapNotNull null
                val subject = WifiHeuristics.subjectOf(ssidOf(r))
                val isCurrent = (currentBssid != null && bssid.equals(currentBssid, ignoreCase = true)) ||
                    (currentBssid == null && currentSubject != null && currentSubject != WifiHeuristics.HIDDEN && subject == currentSubject)
                WifiNetwork(subject, bssid, r.capabilities, r.frequency, isCurrent)
            } catch (_: Exception) {
                null
            }
        }
        return WifiProbeResult(SurroundingsKeys.AVAILABLE_YES, WifiHeuristics.summarise(networks))
    }

    private fun ssidOf(r: android.net.wifi.ScanResult): String? {
        val modern = runCatching { r.wifiSsid?.toString() }.getOrNull()
        if (modern != null) return modern
        @Suppress("DEPRECATION")
        return r.SSID
    }

    /** The connected network's BSSID (null when Android redacts it) and SSID subject. Used in memory only. */
    private fun current(wifi: WifiManager): Pair<String?, String?> = try {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val bssid = info?.bssid?.takeIf { it.isNotEmpty() && !it.equals(REDACTED_BSSID, ignoreCase = true) }
        val subject = info?.ssid?.let(WifiHeuristics::subjectOf)
        bssid to subject
    } catch (_: Exception) {
        null to null
    }
}

data class CellProbeResult(val available: String, val cell: CellSummary?)

/** The registered cell's technology, the operator code and the neighbour count. No cell ids, no position. */
object CellProbe {
    private const val FRESH_TIMEOUT_MS = 5_000L

    suspend fun read(context: Context): CellProbeResult {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return CellProbeResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, null)
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return CellProbeResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, null)
        if (!context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return CellProbeResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, null)
        // allCellInfo is empty while the location toggle is off; say so instead of reporting an empty cell.
        if (!context.locationEnabled()) return CellProbeResult(SurroundingsKeys.AVAILABLE_LOCATION_OFF, null)
        val infos = try {
            fresh(tm) ?: tm.allCellInfo.orEmpty()
        } catch (_: SecurityException) {
            return CellProbeResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, null)
        } catch (e: Exception) {
            Log.w(TAG, "allCellInfo failed: ${e.javaClass.simpleName}")
            return CellProbeResult(SurroundingsKeys.AVAILABLE_FAILED, null)
        }
        val registered = infos.firstOrNull { it.isRegistered }
        val operator = CellHeuristics.operatorOf(runCatching { tm.networkOperator }.getOrNull())
        return CellProbeResult(SurroundingsKeys.AVAILABLE_YES, CellSummary(techOf(registered), operator, infos.count { !it.isRegistered }))
    }

    /** Asks the modem for a fresh list; null when it does not answer in time, so the cached list is used. */
    private suspend fun fresh(tm: TelephonyManager): List<CellInfo>? = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            try {
                tm.requestCellInfoUpdate(
                    { it.run() },
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun techOf(info: CellInfo?): CellTech = when (info) {
        is CellInfoNr -> CellTech.NR
        is CellInfoLte -> CellTech.LTE
        is CellInfoWcdma, is CellInfoTdscdma -> CellTech.UMTS
        is CellInfoGsm -> CellTech.GSM
        else -> CellTech.UNKNOWN
    }
}
