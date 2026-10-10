package io.github.stronghorse44.tunnels.emissions

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import io.github.stronghorse44.tunnels.deepmode.ShizukuShell
import io.github.stronghorse44.tunnels.deepmode.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Emissions"

/** One read of the phone's transmitters, and (with the shell) which apps used a radio recently. */
data class Probe(
    val reading: EmissionsReading,
    /** Package names per kind for the screen only; null when not read. Never stored. */
    val recentUse: Map<RadioUse.Kind, List<RadioUse.Use>>?,
)

/**
 * Reads what each transmitter reports. Android's own APIs first (no permission prompt: airplane mode, Wi-Fi, Wi-Fi
 * scanning, Bluetooth, NFC, location); the Shizuku shell, when the user granted it, for what apps cannot read
 * (Bluetooth scanning, UWB, tethering, the app-op history). Nothing identifying is read: no SSID, address or SIM id.
 */
object EmissionsProbe {
    fun shellAccess(context: Context): ShellAccess {
        val status = ShizukuStatus.read(context)
        return when {
            !status.installed -> ShellAccess.NOT_INSTALLED
            !status.running -> ShellAccess.NOT_RUNNING
            !status.granted -> ShellAccess.NOT_GRANTED
            else -> ShellAccess.READY
        }
    }

    /**
     * Reads everything, binding the shell for the length of the read when Shizuku is ready. [withApps] adds the
     * app-op history reads (nine commands); the tile and Silence skip them.
     */
    suspend fun read(context: Context, shell: ShizukuShell, withApps: Boolean): Probe = withContext(Dispatchers.IO) {
        val access = shellAccess(context)
        if (access != ShellAccess.READY) return@withContext Probe(readApp(context).copy(shell = access), null)
        try {
            shell.withShell { sh -> readIn(context, withApps) { sh.run(it) } }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "shell reads skipped: ${e.message}")
            Probe(readApp(context).copy(shell = ShellAccess.NOT_RUNNING), null)
        }
    }

    /** Reads everything inside a shell session the caller holds open ([run] runs one command in it). */
    suspend fun readIn(context: Context, withApps: Boolean, run: suspend (String) -> String): Probe {
        val app = withContext(Dispatchers.IO) { readApp(context) }
        val emitters = app.emitters.toMutableMap()
        fun shellReading(e: Emitter, state: EmitterState, confirmed: Boolean) {
            emitters[e] = EmitterReading(e, state, ReadSource.SHELL, confirmed)
        }
        if (app.state(Emitter.BLE_SCANNING) == EmitterState.UNKNOWN) {
            shellReading(Emitter.BLE_SCANNING, ShellReads.flag(run(ShellReads.BLE_SCANNING)), confirmed = true)
        }
        if (app.state(Emitter.UWB) == EmitterState.UNKNOWN) {
            shellReading(Emitter.UWB, ShellReads.uwb(run(ShellReads.UWB_STATUS)), ShellReads.UWB_CONFIRMED)
        }
        val tethering = ShellReads.tethering(run(ShellReads.TETHERING))
        shellReading(Emitter.HOTSPOT, tethering.state, ShellReads.TETHERING_CONFIRMED)

        var recent: Map<RadioUse.Kind, List<RadioUse.Use>>? = null
        var counts: AppUseCounts? = null
        if (withApps) {
            val uses = ArrayList<RadioUse.Use>()
            var failures = 0
            val ops = RadioUse.Kind.entries.flatMap { it.ops }
            for (op in ops) {
                val out = run(RadioUse.command(op))
                if (ShellOutput.failed(out)) failures++ else uses += RadioUse.parse(op, out)
            }
            recent = RadioUse.recent(uses, self = context.packageName)
            counts = if (failures == ops.size) AppUseCounts(emptyMap(), failed = true) else RadioUse.counts(recent)
        }
        val reading = app.copy(emitters = emitters, hotspotKinds = tethering.kinds, shell = ShellAccess.READY, appUse = counts)
        return Probe(reading, recent)
    }

    /** What Android's app APIs tell, with no shell: everything except Bluetooth scanning, UWB and tethering. */
    fun readApp(context: Context): EmissionsReading {
        val pm = context.packageManager
        val cr = context.contentResolver
        val out = LinkedHashMap<Emitter, EmitterReading>()
        fun put(e: Emitter, state: EmitterState) {
            out[e] = EmitterReading(e, state, ReadSource.APP, confirmed = true)
        }
        fun state(read: () -> Boolean): EmitterState =
            runCatching { if (read()) EmitterState.ON else EmitterState.OFF }.getOrElse { e ->
                Log.w(TAG, "read failed: ${e.javaClass.simpleName}")
                EmitterState.UNKNOWN
            }

        val airplane = runCatching { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrNull()

        // Cellular: airplane mode is the switch that silences the modem.
        val tm = context.getSystemService(TelephonyManager::class.java)
        val telephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && tm != null
        put(
            Emitter.CELLULAR,
            when {
                !telephony -> EmitterState.ABSENT
                airplane == true -> EmitterState.OFF
                airplane == false -> EmitterState.ON
                else -> EmitterState.UNKNOWN
            },
        )
        val sim = if (!telephony) null else runCatching {
            when (tm!!.simState) {
                TelephonyManager.SIM_STATE_ABSENT -> "none"
                TelephonyManager.SIM_STATE_READY -> "present"
                TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED,
                TelephonyManager.SIM_STATE_NETWORK_LOCKED, TelephonyManager.SIM_STATE_PERM_DISABLED -> "locked"
                else -> "unknown"
            }
        }.getOrNull()
        val mobileData = if (!telephony) null else try {
            tm!!.isDataEnabled
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        // Wi-Fi and Wi-Fi scanning (ACCESS_WIFI_STATE, a normal permission).
        val wm = context.getSystemService(WifiManager::class.java)
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI) || wm == null) {
            put(Emitter.WIFI, EmitterState.ABSENT)
            put(Emitter.WIFI_SCANNING, EmitterState.ABSENT)
        } else {
            put(Emitter.WIFI, state { wm.isWifiEnabled })
            @Suppress("DEPRECATION")
            put(Emitter.WIFI_SCANNING, state { wm.isScanAlwaysAvailable })
        }

        // Bluetooth; its scanning is a hidden setting that apps targeting Android 12+ usually cannot read.
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            put(Emitter.BLUETOOTH, EmitterState.ABSENT)
            put(Emitter.BLE_SCANNING, EmitterState.ABSENT)
        } else {
            put(Emitter.BLUETOOTH, state { adapter.isEnabled })
            put(Emitter.BLE_SCANNING, state { Settings.Global.getInt(cr, BLE_SCAN_ALWAYS) == 1 })
        }

        val nfc = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        put(Emitter.NFC, if (nfc == null) EmitterState.ABSENT else state { nfc.isEnabled })

        // UWB has no app API for its switch (UwbManager is a system API): the shell reads it.
        put(Emitter.UWB, if (pm.hasSystemFeature(PackageManager.FEATURE_UWB)) EmitterState.UNKNOWN else EmitterState.ABSENT)

        // Tethering state is a system API too: the shell reads it.
        out[Emitter.HOTSPOT] = EmitterReading(Emitter.HOTSPOT, EmitterState.UNKNOWN, null, confirmed = true)

        val location = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()

        return EmissionsReading(
            emitters = out,
            airplane = airplane,
            sim = sim,
            mobileData = mobileData,
            location = location,
        )
    }

    /** `Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE`, a hidden key. */
    private const val BLE_SCAN_ALWAYS = "ble_scan_always_enabled"
}
