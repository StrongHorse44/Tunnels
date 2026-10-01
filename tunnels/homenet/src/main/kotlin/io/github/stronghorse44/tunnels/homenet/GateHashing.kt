package io.github.stronghorse44.tunnels.homenet

/**
 * Android-free SSID normalisation for the network card. The SSID is shown when Android shares it and is
 * never part of the gate's decision or hash (see NetworkFingerprint in core/lan).
 */
object GateHashing {
    /** What WifiInfo reports when the SSID is hidden from the app. */
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** Strips WifiInfo's quotes; null for unknown or blank values. */
    fun normalizeSsid(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s == UNKNOWN_SSID) return null
        val unquoted = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
        return unquoted.ifBlank { null }
    }
}
