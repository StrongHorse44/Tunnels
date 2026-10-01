package io.github.stronghorse44.tunnels.explore

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi

/**
 * Radio: what the cellular modem, Wi-Fi, Bluetooth, NFC and UWB hardware say about themselves. Almost
 * everything is permission-free; the current data network type is the one fact behind READ_PHONE_STATE.
 * Nothing identifying (IMEI, SIM serial, SSID, addresses) is read. Explore line: no rules, no actions.
 */
class RadioTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = Radio.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = listOf(
        PermissionSpec(Manifest.permission.READ_PHONE_STATE, "To show the current data network type"),
    )
    override val rules: List<FindingRule> = emptyList()

    override fun actionsFor(draft: FindingDraft): List<FindingAction> = emptyList()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val out = ArrayList<Observation>()
        val steps = listOf<Pair<String, (MutableList<Observation>) -> Unit>>(
            Radio.CELLULAR to ::cellular,
            Radio.WIFI to ::wifi,
            Radio.BLUETOOTH to ::bluetooth,
            Radio.NFC to ::nfc,
            Radio.UWB to ::uwb,
        )
        steps.forEachIndexed { i, (subject, read) ->
            progress.report(i, steps.size, subject)
            try {
                read(out)
            } catch (e: Exception) {
                // One subsystem must not hide the others.
                out += Observation(id, subject, Radio.ERROR, e.javaClass.simpleName)
            }
        }
        progress.report(steps.size, steps.size, "done")
        return out.distinctBy { it.identity }
    }

    private fun MutableList<Observation>.add(subject: String, key: String, value: String?) {
        if (!value.isNullOrBlank()) add(Observation(id, subject, key, value))
    }

    private fun cellular(out: MutableList<Observation>) {
        val s = Radio.CELLULAR
        val present = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        out.add(s, Radio.PRESENT, present.toString())
        if (!present) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        out.add(s, Radio.SIM_COUNTRY, runCatching { tm.simCountryIso }.getOrNull()?.ifBlank { "none" } ?: "none")
        out.add(s, Radio.OPERATOR_NAME, runCatching { tm.networkOperatorName }.getOrNull()?.ifBlank { "none" } ?: "none")
        out.add(s, Radio.PHONE_TYPE, Radio.phoneTypeName(runCatching { tm.phoneType }.getOrDefault(0)))
        out.add(s, Radio.MODEMS, runCatching { tm.activeModemCount }.getOrNull()?.toString())
        out.add(s, Radio.DATA_ENABLED, try { tm.isDataEnabled.toString() } catch (_: SecurityException) { "no permission" })
        out.add(s, Radio.DATA_NETWORK, try { Radio.networkTypeName(tm.dataNetworkType) } catch (_: SecurityException) { "no permission" })
        out.add(s, Radio.CARRIER_PRIVILEGES, runCatching { tm.hasCarrierPrivileges() }.getOrDefault(false).toString())
        val strength = runCatching { tm.signalStrength }.getOrNull()
        if (strength != null) {
            out.add(s, Radio.SIGNAL_LEVEL, Radio.signalLevelLabel(strength.level))
            for (css in runCatching { strength.cellSignalStrengths }.getOrNull().orEmpty()) {
                val dbm = runCatching { css.dbm }.getOrNull() ?: continue
                if (Radio.validDbm(dbm)) out.add(s, Radio.SIGNAL_DBM_PREFIX + Radio.signalKind(css.javaClass.simpleName), dbm.toString())
            }
        }
    }

    private fun wifi(out: MutableList<Observation>) {
        val s = Radio.WIFI
        val present = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        out.add(s, Radio.PRESENT, present.toString())
        if (!present) return
        val wm = context.getSystemService(WifiManager::class.java)
        if (wm != null) {
            // Adapter facts need ACCESS_WIFI_STATE (a normal permission the app holds); absent, they are skipped.
            try {
                out.add(s, Radio.ENABLED, wm.isWifiEnabled.toString())
                out.add(s, Radio.WIFI_5GHZ, wm.is5GHzBandSupported.toString())
                out.add(s, Radio.WIFI_6GHZ, wm.is6GHzBandSupported.toString())
                out.add(s, Radio.WIFI_WPA3, wm.isWpa3SaeSupported.toString())
                out.add(s, Radio.WIFI_OWE, wm.isEnhancedOpenSupported.toString())
            } catch (_: SecurityException) {
            }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = try {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        } catch (_: SecurityException) {
            null
        }
        val info = caps?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.transportInfo as? WifiInfo
        out.add(s, Radio.WIFI_CONNECTED, (info != null).toString())
        if (info == null) return
        // Only link facts: SSID and BSSID are never read here.
        out.add(s, Radio.WIFI_STANDARD, Radio.wifiStandardName(info.wifiStandard))
        if (info.frequency > 0) {
            out.add(s, Radio.WIFI_FREQUENCY, info.frequency.toString())
            out.add(s, Radio.WIFI_BAND, Radio.band(info.frequency))
        }
        if (info.linkSpeed > 0) out.add(s, Radio.WIFI_LINK_SPEED, info.linkSpeed.toString())
        if (info.rxLinkSpeedMbps > 0) out.add(s, Radio.WIFI_RX_SPEED, info.rxLinkSpeedMbps.toString())
        if (info.txLinkSpeedMbps > 0) out.add(s, Radio.WIFI_TX_SPEED, info.txLinkSpeedMbps.toString())
    }

    private fun bluetooth(out: MutableList<Observation>) {
        val s = Radio.BLUETOOTH
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        out.add(s, Radio.PRESENT, (adapter != null).toString())
        out.add(s, Radio.BT_LE, context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE).toString())
        if (adapter == null) return
        out.add(s, Radio.BT_STATE, Radio.bluetoothStateName(runCatching { adapter.state }.getOrDefault(-1)))
        out.add(s, Radio.BT_LE_2M, runCatching { adapter.isLe2MPhySupported }.getOrNull()?.toString())
        out.add(s, Radio.BT_LE_CODED, runCatching { adapter.isLeCodedPhySupported }.getOrNull()?.toString())
        out.add(s, Radio.BT_LE_EXTENDED, runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrNull()?.toString())
        out.add(s, Radio.BT_LE_PERIODIC, runCatching { adapter.isLePeriodicAdvertisingSupported }.getOrNull()?.toString())
        out.add(s, Radio.BT_LE_MAX_ADV, runCatching { adapter.leMaximumAdvertisingDataLength }.getOrNull()?.takeIf { it > 0 }?.toString())
        out.add(s, Radio.BT_MULTI_ADV, runCatching { adapter.isMultipleAdvertisementSupported }.getOrNull()?.toString())
    }

    private fun nfc(out: MutableList<Observation>) {
        val s = Radio.NFC
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        out.add(s, Radio.PRESENT, (adapter != null).toString())
        out.add(s, Radio.NFC_HCE, context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION).toString())
        if (adapter == null) return
        out.add(s, Radio.ENABLED, runCatching { adapter.isEnabled }.getOrNull()?.toString())
        out.add(s, Radio.NFC_SECURE, runCatching { adapter.isSecureNfcSupported }.getOrNull()?.toString())
        if (runCatching { adapter.isSecureNfcSupported }.getOrDefault(false)) {
            out.add(s, Radio.NFC_SECURE_ON, runCatching { adapter.isSecureNfcEnabled }.getOrNull()?.toString())
        }
    }

    private fun uwb(out: MutableList<Observation>) {
        out.add(Radio.UWB, Radio.PRESENT, context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB).toString())
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        RadioContent(state)
    }
}

@Composable
private fun RadioContent(state: TunnelScreenState) {
    val subjects = remember(state.observations) { ExploreFormat.bySubject(state.observations) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExploreNote()
        if (subjects.isEmpty()) {
            ExploreEmpty(if (state.lastScan == null) "Scan to read what each radio reports about itself." else "No radio facts were read.")
            return@Column
        }
        Radio.subjects.forEach { subject ->
            val facts = subjects[subject] ?: return@forEach
            val headline = when {
                facts[Radio.PRESENT] == "false" -> "absent"
                subject == Radio.CELLULAR -> facts[Radio.DATA_NETWORK] ?: facts[Radio.PHONE_TYPE] ?: "present"
                subject == Radio.WIFI -> if (facts[Radio.WIFI_CONNECTED] == "true") facts[Radio.WIFI_BAND] ?: "connected" else if (facts[Radio.ENABLED] == "false") "off" else "not connected"
                subject == Radio.BLUETOOTH -> facts[Radio.BT_STATE] ?: "present"
                subject == Radio.NFC -> if (facts[Radio.ENABLED] == "true") "on" else "off"
                else -> if (facts[Radio.PRESENT] == "true") "present" else "absent"
            }
            GlassPanel(Modifier.fillMaxWidth(), tint = exploreTint) {
                CardColumn {
                    CardTitle(subject.replaceFirstChar { it.uppercase() }.replace("Wifi", "Wi-Fi").replace("Nfc", "NFC").replace("Uwb", "UWB"), headline)
                    facts.entries.sortedBy { it.key }.filter { it.key != Radio.PRESENT }.forEach { (k, v) -> FactRow(k, v) }
                }
            }
        }
    }
}
