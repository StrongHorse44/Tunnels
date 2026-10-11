package io.github.stronghorse44.tunnels.lan

/** One TCP port the connect scan probes, with the words the UI uses for it. */
data class PortInfo(
    val port: Int,
    /** Short lowercase tag, e.g. "smb". */
    val tag: String,
    /** Plain-words service name, e.g. "Windows file sharing (SMB)". */
    val service: String,
    /** True for services that let anyone on the Wi-Fi try to log in or read files, often unencrypted. */
    val risky: Boolean,
    val note: String,
)

/** The curated port list of the home_network tunnel: about thirty ports a home device might expose. */
object PortCatalog {
    val all: List<PortInfo> = listOf(
        PortInfo(21, "ftp", "FTP file transfer", true, "Sends passwords and files unencrypted."),
        PortInfo(22, "ssh", "SSH remote login", false, "Encrypted remote shell; needs a strong password or keys."),
        PortInfo(23, "telnet", "Telnet remote login", true, "Unencrypted remote login, often with a default password."),
        PortInfo(25, "smtp", "Mail server (SMTP)", false, "Accepts mail from other machines."),
        PortInfo(53, "dns", "DNS resolver", false, "Answers name lookups for the network."),
        PortInfo(80, "http", "Web page (HTTP)", false, "Unencrypted web interface."),
        PortInfo(110, "pop3", "Mail (POP3)", false, "Unencrypted mailbox access."),
        PortInfo(139, "netbios", "Windows file sharing (NetBIOS)", true, "Legacy file sharing, easy to abuse."),
        PortInfo(143, "imap", "Mail (IMAP)", false, "Unencrypted mailbox access."),
        PortInfo(443, "https", "Web page (HTTPS)", false, "Encrypted web interface."),
        PortInfo(445, "smb", "Windows file sharing (SMB)", true, "Shares files to anyone who can reach it; a frequent target of worms."),
        PortInfo(548, "afp", "Apple file sharing (AFP)", false, "Older Mac file sharing."),
        PortInfo(554, "rtsp", "Video stream (RTSP)", false, "Live video, often from a camera."),
        PortInfo(631, "ipp", "Printing (IPP)", false, "Network printing."),
        PortInfo(853, "dot", "DNS over TLS", false, "Encrypted DNS resolver."),
        PortInfo(993, "imaps", "Mail (IMAP over TLS)", false, "Encrypted mailbox access."),
        PortInfo(995, "pop3s", "Mail (POP3 over TLS)", false, "Encrypted mailbox access."),
        PortInfo(1883, "mqtt", "MQTT messaging", true, "Smart-home message bus, usually without encryption or login."),
        PortInfo(1900, "ssdp", "UPnP discovery (SSDP)", false, "Announces the device to the network."),
        PortInfo(3389, "rdp", "Remote desktop (RDP)", true, "Full remote control of a Windows machine."),
        PortInfo(5000, "web5000", "Web admin (port 5000)", false, "Admin page of a NAS or UPnP device."),
        PortInfo(5353, "mdns", "mDNS (Bonjour)", false, "Local name announcements."),
        PortInfo(5900, "vnc", "Screen sharing (VNC)", true, "Remote screen control, often with a weak password."),
        PortInfo(8008, "cast", "Cast / web (port 8008)", false, "Chromecast-style control."),
        PortInfo(8080, "http-alt", "Web page (port 8080)", false, "Alternate web interface."),
        PortInfo(8443, "https-alt", "Web page (port 8443)", false, "Alternate encrypted web interface."),
        PortInfo(8883, "mqtts", "MQTT over TLS", false, "Encrypted smart-home message bus."),
        PortInfo(9100, "jetdirect", "Raw printing (JetDirect)", false, "Prints anything sent to it."),
        PortInfo(32400, "plex", "Plex media server", false, "Streams your media library."),
        PortInfo(49152, "upnp-ctl", "UPnP control (port 49152)", false, "Accepts UPnP commands."),
    )

    /**
     * Ports only "Look closer" tries, on the one device the user tapped (RJ, 2026-10-11): each says a lot about what the
     * device is, or is a risk of its own. Never part of the regular scan, which stays at [all].
     */
    val closerOnly: List<PortInfo> = listOf(
        PortInfo(62078, "apple-sync", "iPhone or iPad sync (port 62078)", false, "Apple's sync service: an iPhone or iPad listens here."),
        PortInfo(7000, "airplay", "AirPlay (port 7000)", false, "Receives AirPlay: an Apple TV, HomePod, Mac or AirPlay TV or speaker."),
        PortInfo(8009, "cast-ctl", "Google Cast (port 8009)", false, "Google Cast control: a Chromecast, Google TV, Nest speaker or Cast TV."),
        PortInfo(1400, "sonos", "Sonos control (port 1400)", false, "Sonos speakers answer here."),
        PortInfo(5555, "adb", "Android debug bridge (ADB)", true, "Full control of an Android device or TV box for anyone on the Wi-Fi."),
        PortInfo(8554, "rtsp-alt", "Video stream (RTSP, port 8554)", false, "Live video, often from a camera."),
        PortInfo(5001, "web5001", "Web admin (port 5001)", false, "Encrypted admin page, often of a NAS."),
        PortInfo(515, "lpd", "Printing (LPD)", false, "Older network printing."),
        PortInfo(34567, "dvr-sofia", "DVR protocol (port 34567)", true, "The protocol of many cheap cameras and video recorders (XMEye); often weakly protected."),
        PortInfo(37777, "dahua", "Dahua recorder protocol (port 37777)", false, "Dahua cameras and video recorders."),
        PortInfo(8000, "web8000", "Camera SDK or web (port 8000)", false, "Hikvision cameras use it; so do some web services."),
        PortInfo(6668, "tuya", "Tuya smart home (port 6668)", false, "Tuya and Smart Life plugs, bulbs and sensors."),
        PortInfo(2869, "upnp-host", "Windows UPnP host (port 2869)", false, "Windows network discovery."),
        PortInfo(5357, "wsd", "Windows device discovery (WSD)", false, "Windows and some printers announce themselves here."),
        PortInfo(2323, "telnet-alt", "Telnet (port 2323)", true, "Unencrypted remote login, common on cheap gadgets."),
    )

    /** Every port "Look closer" tries: the regular catalog first, then [closerOnly]. */
    val closer: List<PortInfo> = all + closerOnly

    val ports: List<Int> = all.map { it.port }
    val risky: Set<Int> = closer.filter { it.risky }.map { it.port }.toSet()
    private val byPort: Map<Int, PortInfo> = closer.associateBy { it.port }

    fun byPort(port: Int): PortInfo? = byPort[port]

    fun riskyOf(open: Collection<Int>): List<Int> = open.filter { it in risky }.distinct().sorted()

    /** "Telnet remote login (23), Windows file sharing (SMB) (445)" for the UI and evidence strings. */
    fun describe(ports: Collection<Int>, max: Int = 5): String {
        val sorted = ports.distinct().sorted()
        val shown = sorted.take(max).joinToString(", ") { p -> byPort[p]?.let { "${it.service} ($p)" } ?: "port $p" }
        val rest = sorted.size - max
        return if (rest > 0) "$shown and $rest more" else shown
    }
}
