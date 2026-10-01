package io.github.stronghorse44.tunnels.lan

/** Vendor and device-class hints read out of mDNS names, TXT models and SSDP SERVER / device-type strings. */
object VendorHints {
    private class Hint(val marker: String, val vendor: String, val kind: HostKind? = null)

    /** Order matters: the first marker found wins, so specific products come before their maker. */
    private val hints: List<Hint> = listOf(
        Hint("sonos", "Sonos", HostKind.SPEAKER),
        Hint("fritz!box", "AVM FRITZ!Box", HostKind.ROUTER),
        Hint("fritzbox", "AVM FRITZ!Box", HostKind.ROUTER),
        Hint("miniupnpd", "MiniUPnPd"),
        Hint("synology", "Synology", HostKind.NAS),
        Hint("qnap", "QNAP", HostKind.NAS),
        Hint("hikvision", "Hikvision", HostKind.CAMERA),
        Hint("dahua", "Dahua", HostKind.CAMERA),
        Hint("reolink", "Reolink", HostKind.CAMERA),
        Hint("wyze", "Wyze", HostKind.CAMERA),
        Hint("arlo", "Arlo", HostKind.CAMERA),
        Hint("roku", "Roku", HostKind.TV),
        Hint("bravia", "Sony", HostKind.TV),
        Hint("webos", "LG", HostKind.TV),
        Hint("lg electronics", "LG"),
        Hint("samsung", "Samsung"),
        Hint("chromecast", "Google", HostKind.TV),
        Hint("google", "Google"),
        Hint("philips hue", "Philips Hue", HostKind.IOT),
        Hint("hue bridge", "Philips Hue", HostKind.IOT),
        Hint("shelly", "Shelly", HostKind.IOT),
        Hint("tasmota", "Tasmota", HostKind.IOT),
        Hint("espressif", "Espressif", HostKind.IOT),
        Hint("esp32", "Espressif", HostKind.IOT),
        Hint("esp8266", "Espressif", HostKind.IOT),
        Hint("tp-link", "TP-Link"),
        Hint("tplink", "TP-Link"),
        Hint("netgear", "Netgear"),
        Hint("asus", "ASUS"),
        Hint("ubiquiti", "Ubiquiti"),
        Hint("unifi", "Ubiquiti"),
        Hint("mikrotik", "MikroTik", HostKind.ROUTER),
        Hint("openwrt", "OpenWrt", HostKind.ROUTER),
        Hint("eero", "eero", HostKind.ROUTER),
        Hint("apple tv", "Apple", HostKind.TV),
        Hint("homepod", "Apple", HostKind.SPEAKER),
        Hint("apple", "Apple"),
        Hint("xbox", "Microsoft Xbox", HostKind.TV),
        Hint("microsoft", "Microsoft"),
        Hint("playstation", "Sony PlayStation", HostKind.TV),
        Hint("hewlett", "HP", HostKind.PRINTER),
        Hint("hp ", "HP"),
        Hint("brother", "Brother", HostKind.PRINTER),
        Hint("canon", "Canon", HostKind.PRINTER),
        Hint("epson", "Epson", HostKind.PRINTER),
        Hint("fire tv", "Amazon", HostKind.TV),
        Hint("amazon", "Amazon"),
        Hint("raspberry", "Raspberry Pi", HostKind.COMPUTER),
        Hint("plex", "Plex", HostKind.NAS),
        Hint("western digital", "Western Digital", HostKind.NAS),
        Hint("my cloud", "Western Digital", HostKind.NAS),
        Hint("nvidia shield", "NVIDIA", HostKind.TV),
        Hint("tuya", "Tuya", HostKind.IOT),
    )

    /** Vendor name for any of the strings, or null. The input is matched lowercase. */
    fun vendorOf(vararg strings: String?): String? {
        val text = strings.filterNotNull().joinToString(" ").lowercase()
        if (text.isBlank()) return null
        return hints.firstOrNull { text.contains(it.marker) }?.vendor
    }

    /** Device class implied by a vendor marker, when that vendor makes only one kind of thing. */
    fun kindOf(lowercaseText: String): HostKind? = hints.firstOrNull { it.kind != null && lowercaseText.contains(it.marker) }?.kind
}
