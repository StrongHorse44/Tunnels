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
 * an open copy next to a secured one, or a spread of access points from several vendors. Curated carrier
 * and public hotspot names ([carrierHotspots]) are exempt from the vendor rule and judged on security alone.
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
                twinSuspect = if (subject == HIDDEN) null else twinReason(subject, aps, securities),
            )
        }.sortedWith(compareByDescending<WifiSummary> { it.current }.thenBy { it.subject })

    /** How a carrier or public hotspot name is normally secured. */
    enum class HotspotSecurity { OPEN, ENTERPRISE }

    /**
     * Network names that carriers and hotspot operators broadcast from many access points of many vendors,
     * so "several vendors share this name" is normal for them. Matched case-insensitively on the exact name.
     * Each entry records how the network is normally secured: the impostor sign left for these names is an
     * access point whose security does not fit (an open copy of an 802.1X/Passpoint network, a password-protected
     * one among open ones). Add a name only when it is certain to be such a network.
     */
    val carrierHotspots: Map<String, HotspotSecurity> = mapOf(
        // Charter Spectrum: the Passpoint network for Spectrum Mobile lines, and the open/secured cable hotspots.
        "spectrum mobile" to HotspotSecurity.ENTERPRISE,
        "spectrumwifi" to HotspotSecurity.OPEN,
        "spectrumwifi plus" to HotspotSecurity.ENTERPRISE,
        // Legacy Time Warner Cable hotspots, now run by Spectrum.
        "twcwifi" to HotspotSecurity.OPEN,
        "twcwifi-passpoint" to HotspotSecurity.ENTERPRISE,
        // Comcast Xfinity: open captive-portal hotspots and the secured 802.1X twin.
        "xfinitywifi" to HotspotSecurity.OPEN,
        "xfinity" to HotspotSecurity.ENTERPRISE,
        // CableWiFi: the roaming alliance of US cable operators.
        "cablewifi" to HotspotSecurity.OPEN,
        // Optimum (Altice) and Cox hotspots.
        "optimumwifi" to HotspotSecurity.OPEN,
        "coxwifi" to HotspotSecurity.OPEN,
        // AT&T hotspots in shops and restaurants.
        "attwifi" to HotspotSecurity.OPEN,
        // Boingo airport and venue hotspots.
        "boingo hotspot" to HotspotSecurity.OPEN,
        // Google-run Starbucks Wi-Fi.
        "google starbucks" to HotspotSecurity.OPEN,
        // eduroam: the worldwide academic roaming network, always 802.1X.
        "eduroam" to HotspotSecurity.ENTERPRISE,
    )

    /** The expected security of a curated carrier or public hotspot name, or null for any other name. */
    fun carrierHotspot(subject: String): HotspotSecurity? = carrierHotspots[subject.trim().lowercase()]

    private const val CARRIER = "normally a carrier network with many access points"

    private fun words(s: WifiSecurity): String = when (s) {
        WifiSecurity.OPEN -> "open security"
        WifiSecurity.WEP -> "WEP"
        WifiSecurity.WPA, WifiSecurity.WPA2, WifiSecurity.WPA3 -> "a Wi-Fi password (${s.slug.uppercase()}-Personal)"
        WifiSecurity.ENTERPRISE -> "enterprise sign-in"
        WifiSecurity.OWE -> "enhanced open (OWE)"
    }

    /**
     * For a curated carrier name the vendor spread is normal; only security that does not fit the network is
     * an impostor sign. An 802.1X/Passpoint network should only ever ask for enterprise sign-in; an open
     * hotspot network may come with enhanced open (OWE) but should not mix in password-protected copies.
     */
    private fun carrierReason(expected: HotspotSecurity, securities: Set<WifiSecurity>): String? {
        val sorted = securities.sortedBy { it.ordinal }
        return when (expected) {
            HotspotSecurity.ENTERPRISE -> {
                val odd = sorted.firstOrNull { it != WifiSecurity.ENTERPRISE } ?: return null
                if (WifiSecurity.ENTERPRISE in securities) "$CARRIER; this one advertises ${words(odd)} while others use enterprise sign-in"
                else "$CARRIER that uses enterprise sign-in; this one advertises ${words(odd)}"
            }
            HotspotSecurity.OPEN -> {
                val openLike = setOf(WifiSecurity.OPEN, WifiSecurity.OWE)
                val odd = sorted.firstOrNull { it !in openLike } ?: return null
                if (WifiSecurity.OPEN in securities) "$CARRIER; this one advertises ${words(odd)} while others are open" else null
            }
        }
    }

    private fun twinReason(subject: String, aps: List<WifiNetwork>, securities: Set<WifiSecurity>): String? {
        carrierHotspot(subject)?.let { return carrierReason(it, securities) }
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
