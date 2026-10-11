package io.github.stronghorse44.tunnels.lan

/**
 * One DNS-SD service type the discovery asks for, with a hint about what kind of device advertises it. [keepsName] false
 * for types whose instance name is not the device's name (a hardware address, a changing id): it is never kept.
 */
data class MdnsType(val type: String, val label: String, val kindHint: HostKind?, val keepsName: Boolean = true)

/** The curated DNS-SD types the home_network tunnel browses. */
object MdnsTypes {
    val all: List<MdnsType> = listOf(
        MdnsType("_http._tcp", "web page", null),
        MdnsType("_https._tcp", "secure web page", null),
        MdnsType("_ipp._tcp", "printing", HostKind.PRINTER),
        MdnsType("_printer._tcp", "printing", HostKind.PRINTER),
        MdnsType("_googlecast._tcp", "Google Cast", HostKind.TV),
        MdnsType("_airplay._tcp", "AirPlay", HostKind.TV),
        MdnsType("_raop._tcp", "AirPlay audio", HostKind.SPEAKER),
        MdnsType("_ssh._tcp", "SSH", HostKind.COMPUTER),
        MdnsType("_smb._tcp", "file sharing", HostKind.NAS),
        MdnsType("_afpovertcp._tcp", "Apple file sharing", HostKind.NAS),
        MdnsType("_hap._tcp", "HomeKit", HostKind.IOT),
        MdnsType("_homekit._tcp", "HomeKit", HostKind.IOT),
        MdnsType("_spotify-connect._tcp", "Spotify Connect", HostKind.SPEAKER),
        MdnsType("_sonos._tcp", "Sonos", HostKind.SPEAKER),
        MdnsType("_workstation._tcp", "workstation", HostKind.COMPUTER),
        MdnsType("_device-info._tcp", "device info", null),
        // Apple's own announcements (RJ, 2026-10-11): iPhones, iPads and Macs (and HomePods and Apple TVs) announce these
        // when awake, even when they share nothing. The device's name is in _companion-link's; _apple-mobdev2's instance
        // name holds the Wi-Fi hardware address and _rdlink's is not the device's name, so neither is ever kept.
        MdnsType("_companion-link._tcp", "Apple device", null),
        MdnsType("_apple-mobdev2._tcp", "iPhone or iPad", HostKind.PHONE, keepsName = false),
        MdnsType("_rdlink._tcp", "Apple device link", null, keepsName = false),
    )

    val types: List<String> = all.map { it.type }
    private val byType = all.associateBy { it.type }

    /** "_http._tcp.local." -> "_http._tcp"; a leading dot (some Android versions add one after resolving) goes too. */
    fun normalize(type: String): String {
        var t = type.trim().trimStart('.').removeSuffix(".")
        if (t.endsWith(".local")) t = t.removeSuffix(".local")
        return t.removeSuffix(".")
    }

    private val hardwareAddress = Regex("(?i)(?<![0-9a-f])[0-9a-f]{2}([:-])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}(?![0-9a-f])")

    /**
     * The instance name to keep for a service of [type], or null: never for a type that does not keep names, and never one
     * that holds a hardware address, whatever its type (rule 3: Tunnels does not keep other devices' hardware addresses).
     */
    fun nameToKeep(type: String?, name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (type != null && byType(type)?.keepsName == false) return null
        if (hardwareAddress.containsMatchIn(n)) return null
        return n
    }

    /** "_http._tcp" -> "http". */
    fun shortName(type: String): String = normalize(type).removePrefix("_").substringBefore("._")

    fun byType(type: String): MdnsType? = byType[normalize(type)]
}
