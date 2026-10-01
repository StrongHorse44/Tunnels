package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.stronghorse44.tunnels.ble.PlaceAnchor
import io.github.stronghorse44.tunnels.ble.PlaceGrid
import io.github.stronghorse44.tunnels.ble.PlaceTracking
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.coroutines.resume

/** Whether a scan knew the phone's place, and the place's number when it did. */
data class PlaceResult(val available: String, val place: Long?)

/**
 * The place number for a scan ([PlaceTracking]): one position fix, snapped to the grid, compared with the current
 * place's anchor by a keyed hash. The fix and the cell exist only in memory for the length of this call; the
 * stored state is a single row ([STREAM]) holding the place number and the anchor's keyed hash, replaced on every
 * scan. The hash key is an HMAC key in the Android keystore that never leaves it.
 *
 * Shared by the manual scan and the background monitor; a mutex keeps two scans from numbering one move twice.
 */
object PlaceProbe {
    private const val TAG = "SurroundingsPlace"

    /** The events stream of the single anchor row, apart from sightings so a bounded read always finds it. */
    const val STREAM = "surroundings.place"
    private const val KIND = "place.anchor"
    private const val SUBJECT = "anchor"
    private const val KEY_ALIAS = "tunnels.surroundings.place"
    /** A fix this recent, from any app's request, is as good as a new one. */
    private const val FRESH_FIX_NS = 2 * 60 * 1_000_000_000L

    private val mutex = Mutex()
    @Volatile private var cached: PlaceAnchor? = null
    /** The last cell's block hashes: the monitor scans from one place for hours, so the keystore is asked once. */
    @Volatile private var memo: Pair<PlaceGrid.Cell, List<String>>? = null

    suspend fun locate(context: Context, timeoutMs: Long): PlaceResult {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return PlaceResult(SurroundingsKeys.AVAILABLE_NO_PERMISSION, null)
        val lm = context.getSystemService(LocationManager::class.java) ?: return PlaceResult(SurroundingsKeys.AVAILABLE_NO_ADAPTER, null)
        if (runCatching { lm.isLocationEnabled }.getOrDefault(true) == false) return PlaceResult(SurroundingsKeys.AVAILABLE_LOCATION_OFF, null)
        val fix = fix(context, lm, timeoutMs)
        if (fix == null || !fix.hasAccuracy() || fix.accuracy > PlaceGrid.MAX_ACCURACY_METERS) return PlaceResult(SurroundingsKeys.AVAILABLE_NO_FIX, null)
        val cell = PlaceGrid.cellOf(fix.latitude, fix.longitude)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val hashes = blockHashes(cell)
                    val store = TunnelsStore.get(context)
                    val stored = cached ?: store.dao.events(STREAM, 1).first().firstOrNull()?.let { PlaceAnchor.parse(it.summary) }
                    val next = PlaceTracking.next(stored, hashes) { PlaceTracking.firstPlace(System.currentTimeMillis()) }
                    // Rewritten every scan so it never expires while scans go on; the older row goes at once.
                    val now = System.currentTimeMillis()
                    store.recordEvent(STREAM, KIND, SUBJECT, next.encode(), java.time.Instant.ofEpochMilli(now))
                    store.dao.deleteEventsBefore(STREAM, now)
                    cached = next
                    PlaceResult(SurroundingsKeys.AVAILABLE_YES, next.place)
                } catch (e: Exception) {
                    Log.w(TAG, "place unavailable: ${e.javaClass.simpleName}")
                    PlaceResult(SurroundingsKeys.AVAILABLE_FAILED, null)
                }
            }
        }
    }

    /**
     * A fix accurate enough to place the phone: a recent one any app already got, else one fresh request from the
     * fused provider (or GPS, or network) that gives up after [timeoutMs].
     */
    private suspend fun fix(context: Context, lm: LocationManager, timeoutMs: Long): Location? {
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        val now = SystemClock.elapsedRealtimeNanos()
        val recent = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.elapsedRealtimeNanos < FRESH_FIX_NS && it.hasAccuracy() && it.accuracy <= PlaceGrid.MAX_ACCURACY_METERS }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation failed: ${e.javaClass.simpleName}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** Keyed hashes of [cell] and its neighbours ([PlaceGrid.block] order), 16 bytes each, hex. */
    private fun blockHashes(cell: PlaceGrid.Cell): List<String> {
        memo?.let { (c, hashes) -> if (c == cell) return hashes }
        val mac = Mac.getInstance("HmacSHA256").apply { init(key()) }
        val hashes = PlaceGrid.block(cell).map { c -> mac.doFinal(c.id.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) } }
        memo = cell to hashes
        return hashes
    }

    /** The HMAC key, created on first use. It is not exportable: hashes can only be made, and so matched, on this phone. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }
}
