package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import android.util.Log
import io.github.stronghorse44.tunnels.ble.ApSeen
import io.github.stronghorse44.tunnels.ble.ManyNames
import io.github.stronghorse44.tunnels.ble.NameCluster
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.ble.WifiNetwork
import io.github.stronghorse44.tunnels.ble.WifiScanRecord
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Wi-Fi access points over time, the Android side: keyed ids for this scan's access points (an HMAC of the address
 * under the Surroundings keystore key, [SurroundingsKey]; the address itself is dropped), the Wi-Fi stream
 * ([SurroundingsKeys.WIFI_LEDGER], one row per scan, 30 days) and the user's threshold. Shared by the manual scan and
 * the background monitor. Names are kept with each scan: RJ's exception to rule 3 (2026-10-10, CLAUDE.md).
 */
object WifiSightings {
    private const val TAG = "SurroundingsWifi"
    private const val AP_INPUT = "wifi-ap:v1:"
    private const val DEVICE_INPUT = "wifi-dev:v1:"
    private const val STORE_TIMEOUT_MS = 20_000L
    private const val RETENTION_MS = 30L * SurroundingsKeys.DAY_MS

    /** This scan as a record of keyed ids and names, one entry per access point; null when nothing fresh was heard. */
    fun recordOf(session: String, at: Long, place: Long?, fresh: List<WifiNetwork>): WifiScanRecord? {
        if (fresh.isEmpty()) return null
        val unique = fresh.distinctBy { it.bssid.lowercase() }
        val keys = keyed(unique.map { AP_INPUT + it.bssid.lowercase() }) ?: return null
        return WifiScanRecord(session, at, place, unique.zip(keys) { n, k -> ApSeen(k, n.subject) })
    }

    /** The devices in [fresh] that broadcast several names, each under the keyed id of its address prefix. */
    fun clustersOf(fresh: List<WifiNetwork>): List<NameCluster> {
        val prefixes = fresh.mapNotNull { ManyNames.prefixOf(it.bssid) }.distinct()
        if (prefixes.isEmpty()) return emptyList()
        val keys = keyed(prefixes.map { DEVICE_INPUT + it }) ?: return emptyList()
        val byPrefix = prefixes.zip(keys).toMap()
        return ManyNames.clusters(fresh) { byPrefix.getValue(it) }
    }

    /** Keyed ids of [inputs], in order; null when the keystore refused (the scan then keeps no Wi-Fi sightings). */
    private fun keyed(inputs: List<String>): List<String>? = try {
        SurroundingsKey.hmacAll(inputs).map { it.take(WifiFollowing.KEY_LENGTH) }
    } catch (e: Exception) {
        Log.w(TAG, "keyed ids unavailable: ${e.javaClass.simpleName}")
        null
    }

    /** The Wi-Fi rows of the last 30 days; empty when the store cannot be read. */
    suspend fun history(context: Context, now: Long): List<WifiScanRecord> = try {
        val store = TunnelsStore.get(context)
        val rows = withTimeoutOrNull(STORE_TIMEOUT_MS) { store.dao.events(SurroundingsKeys.WIFI_LEDGER, SurroundingsKeys.MAX_WIFI_ROWS).first() }.orEmpty()
        rows.filter { it.kind == SurroundingsKeys.EVENT_WIFI_SCAN && it.at >= now - RETENTION_MS }
            .mapNotNull { WifiScanRecord.parse(it.subject, it.summary) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Wi-Fi history unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    suspend fun record(context: Context, record: WifiScanRecord) {
        try {
            TunnelsStore.get(context).recordEvent(
                SurroundingsKeys.WIFI_LEDGER, SurroundingsKeys.EVENT_WIFI_SCAN, record.session, record.encode(), Instant.ofEpochMilli(record.at),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi record failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun threshold(context: Context): Int = try {
        WifiFollowing.threshold(TunnelsStore.get(context).setting(SurroundingsKeys.WIFI_THRESHOLD_SETTING))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WifiFollowing.DEFAULT_THRESHOLD
    }

    suspend fun setThreshold(context: Context, scans: Int) {
        val value = scans.coerceIn(WifiFollowing.MIN_THRESHOLD, WifiFollowing.MAX_THRESHOLD)
        TunnelsStore.get(context).putSetting(SurroundingsKeys.WIFI_THRESHOLD_SETTING, value.toString())
    }
}
