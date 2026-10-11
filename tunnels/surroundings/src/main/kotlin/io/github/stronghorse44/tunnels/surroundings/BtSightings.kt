package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import android.util.Log
import io.github.stronghorse44.tunnels.ble.BtFollowing
import io.github.stronghorse44.tunnels.ble.BtScanRecord
import io.github.stronghorse44.tunnels.ble.BtSeen
import io.github.stronghorse44.tunnels.ble.DeviceHints
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.WifiFollowing
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Bluetooth devices over time, the Android side: keyed ids for an unfiltered window's fixed-address devices (an HMAC of
 * the address under the Surroundings keystore key, [SurroundingsKey]; the address itself is dropped) and the Bluetooth
 * stream ([SurroundingsKeys.BT_LEDGER], one row per logged scan, 30 days). Shared by the manual scan, the background
 * monitor's screen-on passes and find-it. Names and hint ids are kept with each scan: RJ's exception to rule 3
 * (2026-10-11, CLAUDE.md). The threshold is the Wi-Fi one ([WifiSightings.threshold]).
 */
object BtSightings {
    private const val TAG = "SurroundingsBt"
    private const val DEVICE_INPUT = "bt-dev:v1:"
    private const val STORE_TIMEOUT_MS = 20_000L
    private const val RETENTION_MS = 30L * SurroundingsKeys.DAY_MS

    /**
     * This window's fixed-address devices as a record of keyed ids, names and hint ids, strongest first and at most
     * [BtFollowing.MAX_DEVICES_PER_SCAN]; null when there were none or the keystore refused.
     */
    fun recordOf(session: String, at: Long, place: Long?, heard: List<HeardDevice>): BtScanRecord? {
        if (heard.isEmpty()) return null
        val kept = heard.distinctBy { it.address.lowercase() }.sortedByDescending { it.rssi }.take(BtFollowing.MAX_DEVICES_PER_SCAN)
        val keys = keyed(kept.map { it.address }) ?: return null
        return BtScanRecord(session, at, place, kept.zip(keys) { d, k -> BtSeen(k, d.name, DeviceHints.of(d.name, d.companyIds)?.id) })
    }

    /** The keyed id of one address, as [recordOf] makes it; null when the keystore refused. Find-it matches with it. */
    fun keyOf(address: String): String? = keyed(listOf(address))?.single()

    private fun keyed(addresses: List<String>): List<String>? = try {
        SurroundingsKey.hmacAll(addresses.map { DEVICE_INPUT + it.lowercase() }).map { it.take(WifiFollowing.KEY_LENGTH) }
    } catch (e: Exception) {
        Log.w(TAG, "keyed ids unavailable: ${e.javaClass.simpleName}")
        null
    }

    /** The Bluetooth rows of the last 30 days; empty when the store cannot be read. */
    suspend fun history(context: Context, now: Long): List<BtScanRecord> = try {
        val store = TunnelsStore.get(context)
        val rows = withTimeoutOrNull(STORE_TIMEOUT_MS) { store.dao.events(SurroundingsKeys.BT_LEDGER, SurroundingsKeys.MAX_BT_ROWS).first() }.orEmpty()
        rows.filter { it.kind == SurroundingsKeys.EVENT_BT_SCAN && it.at >= now - RETENTION_MS }
            .mapNotNull { BtScanRecord.parse(it.subject, it.summary) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Bluetooth history unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    suspend fun record(context: Context, record: BtScanRecord) {
        try {
            TunnelsStore.get(context).recordEvent(
                SurroundingsKeys.BT_LEDGER, SurroundingsKeys.EVENT_BT_SCAN, record.session, record.encode(), Instant.ofEpochMilli(record.at),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth record failed: ${e.javaClass.simpleName}")
        }
    }
}
