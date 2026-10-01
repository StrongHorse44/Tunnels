package io.github.stronghorse44.tunnels.lan

/** What a LAN host most likely is, from the services it advertises and the ports it answers on. */
enum class HostKind(val label: String, val title: String) {
    ROUTER("router", "Router"),
    CAMERA("camera", "Cameras"),
    NAS("nas", "Storage"),
    COMPUTER("computer", "Computers"),
    PHONE("phone", "Phones"),
    TV("tv", "TVs and streamers"),
    SPEAKER("speaker", "Speakers"),
    PRINTER("printer", "Printers"),
    IOT("iot", "Smart home"),
    UNKNOWN("unknown", "Unidentified"),
    ;

    companion object {
        fun byLabel(label: String?): HostKind = entries.firstOrNull { it.label == label } ?: UNKNOWN
    }
}

/** Everything the scan learned about one host that can hint at its kind. */
data class HostEvidence(
    val mdnsTypes: Set<String> = emptySet(),
    val names: List<String> = emptyList(),
    val ssdpServer: String? = null,
    val ssdpTypes: Set<String> = emptySet(),
    val openPorts: Set<Int> = emptySet(),
    val isGateway: Boolean = false,
    /** Values of mDNS TXT keys such as "model" or "md", when present. */
    val models: List<String> = emptyList(),
)

object HostKinds {
    private val cameraWords = listOf("camera", "cam", "doorbell", "ipc", "nvr", "dvr")
    private val phoneWords = listOf("iphone", "pixel", "galaxy", "android", "oneplus", "xiaomi", "redmi", "huawei", "ipad")
    private val computerWords = listOf("macbook", "imac", "mac mini", "mac-mini", "laptop", "desktop", "thinkpad", "surface", "-pc", "pc")
    private val tvWords = listOf("tv", "chromecast", "roku", "fire tv", "firetv", "apple tv", "appletv", "shield", "bravia", "mediarenderer", "dial")
    private val speakerWords = listOf("sonos", "homepod", "speaker", "echo", "soundbar", "nest audio", "nest mini", "home mini")
    private val nasWords = listOf("nas", "synology", "diskstation", "qnap", "my cloud", "mycloud", "freenas", "truenas", "mediaserver")
    private val printerWords = listOf("printer", "officejet", "laserjet", "deskjet", "pixma", "brother", "epson", "ecotank", "xerox")
    private val iotWords = listOf("hue", "shelly", "tasmota", "esp", "tuya", "sonoff", "thermostat", "plug", "bulb", "bridge", "homekit", "nest", "ring")

    /** Picks the most specific kind the evidence supports: gateway first, then vendor and name words, then services, then ports. */
    fun infer(e: HostEvidence): HostKind {
        if (e.isGateway) return HostKind.ROUTER
        val types = e.mdnsTypes.map(MdnsTypes::normalize).toSet()
        val text = (e.names + e.models + listOfNotNull(e.ssdpServer) + e.ssdpTypes).joinToString(" ").lowercase()

        VendorHints.kindOf(text)?.let { return it }
        if (cameraWords.any { text.containsWord(it) } || (554 in e.openPorts && (80 in e.openPorts || 8080 in e.openPorts))) return HostKind.CAMERA
        if ("_ipp._tcp" in types || "_printer._tcp" in types || 9100 in e.openPorts || 631 in e.openPorts || printerWords.any { text.contains(it) }) return HostKind.PRINTER
        val audioOnly = "_raop._tcp" in types && "_airplay._tcp" !in types
        if ("_sonos._tcp" in types || "_spotify-connect._tcp" in types || audioOnly || speakerWords.any { text.contains(it) }) return HostKind.SPEAKER
        if ("_googlecast._tcp" in types || "_airplay._tcp" in types || tvWords.any { text.containsWord(it) }) return HostKind.TV
        if (phoneWords.any { text.contains(it) }) return HostKind.PHONE
        if ("_smb._tcp" in types || "_afpovertcp._tcp" in types || nasWords.any { text.containsWord(it) }) {
            return if (computerWords.any { text.contains(it) } || "_workstation._tcp" in types) HostKind.COMPUTER else HostKind.NAS
        }
        if ("_hap._tcp" in types || "_homekit._tcp" in types || iotWords.any { text.containsWord(it) }) return HostKind.IOT
        if ("_workstation._tcp" in types || "_ssh._tcp" in types || computerWords.any { text.containsWord(it) }) return HostKind.COMPUTER
        if (3389 in e.openPorts || 5900 in e.openPorts || 445 in e.openPorts || 139 in e.openPorts) return HostKind.COMPUTER
        if (1883 in e.openPorts || 8883 in e.openPorts) return HostKind.IOT
        return HostKind.UNKNOWN
    }

    private fun String.containsWord(word: String): Boolean {
        var from = 0
        while (true) {
            val i = indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else this[i - 1]
            val after = if (i + word.length >= length) ' ' else this[i + word.length]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }
}
