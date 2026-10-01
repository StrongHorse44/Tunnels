package io.github.stronghorse44.tunnels.ble

/** Security of one access point, read from Android's capabilities string. */
enum class WifiSecurity(val slug: String, val family: String) {
    OPEN("open", "open"),
    WEP("wep", "wep"),
    WPA("wpa", "wpa"),
    WPA2("wpa2", "wpa"),
    WPA3("wpa3", "wpa"),
    ENTERPRISE("enterprise", "enterprise"),
    OWE("owe", "owe"),
    ;

    companion object {
        fun bySlug(slug: String?): WifiSecurity? = entries.firstOrNull { it.slug == slug }

        /**
         * Parses a `ScanResult.capabilities` string such as `[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]`. The strongest
         * personal mode wins; any EAP suite means enterprise; the open side of an OWE transition pair is open.
         */
        fun parse(capabilities: String?): WifiSecurity {
            val c = capabilities.orEmpty().uppercase()
            return when {
                c.contains("EAP") -> ENTERPRISE
                c.contains("OWE_TRANSITION") -> OPEN
                c.contains("OWE") -> OWE
                c.contains("SAE") -> WPA3
                c.contains("WPA2") || c.contains("RSN") -> WPA2
                c.contains("WPA") -> WPA
                c.contains("WEP") -> WEP
                else -> OPEN
            }
        }
    }
}

/** One access point from a Wi-Fi scan, copied into plain Kotlin. The BSSID is used in memory only and never stored. */
data class WifiNetwork(
    val ssid: String?,
    val bssid: String,
    val capabilities: String?,
    val frequencyMhz: Int,
    val current: Boolean = false,
) {
    val subject: String get() = WifiHeuristics.subjectOf(ssid)
    val security: WifiSecurity get() = WifiSecurity.parse(capabilities)
}

/** What the snapshot keeps about one network name: counts and kinds, no addresses. */
data class WifiSummary(
    val subject: String,
    val securities: Set<WifiSecurity>,
    val bssids: Int,
    val bands: Set<String>,
    val current: Boolean,
    /** Why this name looks like an evil twin, or null when it does not. */
    val twinSuspect: String?,
) {
    /** The security the subject is reported as: the weakest, so an open impostor is never hidden behind a secured twin. */
    val security: WifiSecurity get() = securities.minByOrNull { it.ordinal } ?: WifiSecurity.OPEN
}

/**
 * Wi-Fi summaries and the evil-twin heuristic: one network name should not come with mixed security,
 * an open copy next to a secured one, or a spread of access points from several vendors.
 */
object WifiHeuristics {
    const val HIDDEN = "<hidden>"
    /** More distinct vendors than this behind one name is odd for a home or small office. */
    const val MAX_VENDORS_PER_SSID = 4

    fun subjectOf(ssid: String?): String {
        val s = ssid?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        return if (s.isEmpty() || s == "<unknown ssid>") HIDDEN else s
    }

    fun band(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2400..2500 -> "2.4GHz"
        in 4900..5900 -> "5GHz"
        in 5925..7125 -> "6GHz"
        in 57000..71000 -> "60GHz"
        else -> "?"
    }

    /** The vendor part of a BSSID: its first three bytes. */
    fun oui(bssid: String): String = bssid.lowercase().split(':').take(3).joinToString(":")

    fun summarise(networks: Collection<WifiNetwork>): List<WifiSummary> =
        networks.groupBy { it.subject }.map { (subject, aps) ->
            val securities = aps.map { it.security }.toSet()
            WifiSummary(
                subject = subject,
                securities = securities,
                bssids = aps.map { it.bssid.lowercase() }.toSet().size,
                bands = aps.map { band(it.frequencyMhz) }.toSet(),
                current = aps.any { it.current },
                twinSuspect = if (subject == HIDDEN) null else twinReason(aps, securities),
            )
        }.sortedWith(compareByDescending<WifiSummary> { it.current }.thenBy { it.subject })

    private fun twinReason(aps: List<WifiNetwork>, securities: Set<WifiSecurity>): String? {
        val families = securities.map { it.family }.toSet()
        if (WifiSecurity.OPEN in securities && securities.size > 1) {
            return "an open network uses the same name as a secured one"
        }
        if (families.size > 1) {
            return "access points with this name use different security (${securities.sortedBy { it.ordinal }.joinToString("/") { it.slug }})"
        }
        val vendors = aps.map { oui(it.bssid) }.toSet().size
        if (vendors > MAX_VENDORS_PER_SSID) {
            return "$vendors access points from different vendors share this name"
        }
        return null
    }
}
