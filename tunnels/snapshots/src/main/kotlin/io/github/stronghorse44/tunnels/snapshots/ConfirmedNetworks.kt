package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import io.github.stronghorse44.tunnels.export.BundleSettings
import java.io.IOException

/**
 * The Wi-Fi networks the user confirmed as their own for Home network's scan, as SHA-256 hashes of each network's
 * fingerprint. Home network keeps them in its own preferences file (`homenet_gate`, key `confirmed_network_hashes`);
 * this reads and extends that same set so a restored phone does not have to confirm its networks again. The values
 * are hashes, never an SSID or an address. The names below must stay equal to `NetworkGate.PREFS` and
 * `NetworkGate.KEY_CONFIRMED` in tunnels/homenet, which this module cannot depend on.
 */
class ConfirmedNetworks(context: Context, prefsName: String = PREFS) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** What the preferences hold, as stored (a copy), for putting back after a failed import. */
    fun raw(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    /** The well-formed hashes among them: what an export carries. */
    fun all(): Set<String> = BundleSettings.validNetworks(raw())

    /** Adds [hashes] (already validated by the bundle reader); returns how many were new. Throws if the write fails. */
    fun addAll(hashes: Set<String>): Int {
        val have = raw()
        val fresh = hashes - have
        if (fresh.isEmpty()) return 0
        restore(have + fresh)
        return fresh.size
    }

    /** Writes [set] back, synchronously: this is the rollback of an import too. */
    fun restore(set: Set<String>) {
        if (!prefs.edit().putStringSet(KEY, set).commit()) throw IOException("The confirmed networks couldn't be saved.")
    }

    companion object {
        const val PREFS = "homenet_gate"
        const val KEY = "confirmed_network_hashes"
    }
}
