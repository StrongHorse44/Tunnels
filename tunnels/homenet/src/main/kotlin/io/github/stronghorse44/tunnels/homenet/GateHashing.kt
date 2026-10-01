package io.github.stronghorse44.tunnels.homenet

import java.security.MessageDigest

/** Android-free helpers of the own-network gate: SSID normalisation and hashing. */
object GateHashing {
    /** What WifiInfo reports when the SSID is hidden from the app. */
    const val UNKNOWN_SSID = "<unknown ssid>"
    /** How many hex chars of the SSID hash the summary observation keeps. */
    const val PREFIX_LENGTH = 8

    /** Strips WifiInfo's quotes; null for unknown, blank or hex-only placeholder values. */
    fun normalizeSsid(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s == UNKNOWN_SSID) return null
        val unquoted = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
        return unquoted.ifBlank { null }
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The SSID hash as it is stored and compared. */
    fun ssidHash(ssid: String): String = sha256Hex("ssid:$ssid")

    /** The short, non-reversible network tag kept in observations. */
    fun prefix(hash: String): String = hash.take(PREFIX_LENGTH)
}
