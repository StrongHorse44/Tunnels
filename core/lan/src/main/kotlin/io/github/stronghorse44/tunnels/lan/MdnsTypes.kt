package io.github.stronghorse44.tunnels.lan

/** One DNS-SD service type the discovery asks for, with a hint about what kind of device advertises it. */
data class MdnsType(val type: String, val label: String, val kindHint: HostKind?)

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
    )

    val types: List<String> = all.map { it.type }
    private val byType = all.associateBy { it.type }

    /** "_http._tcp.local." -> "_http._tcp". */
    fun normalize(type: String): String {
        var t = type.trim().removeSuffix(".")
        if (t.endsWith(".local")) t = t.removeSuffix(".local")
        return t.removeSuffix(".")
    }

    /** "_http._tcp" -> "http". */
    fun shortName(type: String): String = normalize(type).removePrefix("_").substringBefore("._")

    fun byType(type: String): MdnsType? = byType[normalize(type)]
}
