package io.github.stronghorse44.tunnels.model

/** Metro lines on the home map. Each groups related tunnels; EXPLORE is curiosity-only. */
enum class MetroLine(val label: String, val code: String) {
    FILES("Files", "F"),
    INSPECT("Inspect", "I"),
    SYSTEM("System", "S"),
    ACTIVITY("Activity", "A"),
    NETWORK("Network", "N"),
    EXPLORE("Explore", "X"),
}

/** Static description of a tunnel for the home map. */
data class TunnelInfo(
    val id: String,
    val title: String,
    val stratum: Stratum,
    val line: MetroLine,
    val blurb: String,
    /** Build phase the tunnel lands in; null when it is live in this build. */
    val phase: Int?,
) {
    val isLive: Boolean get() = phase == null
}

object TunnelCatalog {
    const val INSTALLER = "installer"
    const val UNZIP = "unzip"

    val all: List<TunnelInfo> = listOf(
        TunnelInfo(INSTALLER, "Installer", Stratum.TOPSOIL, MetroLine.FILES, "Inspect and install APKs and app bundles", null),
        TunnelInfo(UNZIP, "Unzip", Stratum.TOPSOIL, MetroLine.FILES, "Open zip, 7z, tar, gz, xz and bz2 archives", null),

        TunnelInfo("permissions", "Permissions", Stratum.TOPSOIL, MetroLine.INSPECT, "Declared vs granted, per app", 1),
        TunnelInfo("apk_excavation", "APK excavation", Stratum.TOPSOIL, MetroLine.INSPECT, "Trackers, certs and native libs inside apps", 1),
        TunnelInfo("doors", "Doors", Stratum.TOPSOIL, MetroLine.INSPECT, "Exported components and link handlers", 1),
        TunnelInfo("hardening", "Hardening audit", Stratum.BEDROCK, MetroLine.INSPECT, "Exploit mitigations in native code", 1),

        TunnelInfo("system_packages", "System packages", Stratum.BEDROCK, MetroLine.SYSTEM, "Every system package and what it does", 1),
        TunnelInfo("trust_store", "Trust store", Stratum.BEDROCK, MetroLine.SYSTEM, "System and user certificate authorities", 1),
        TunnelInfo("silicon", "Silicon", Stratum.CORE, MetroLine.SYSTEM, "Verified boot and hardware attestation", 1),
        TunnelInfo("deep_mode", "Deep mode", Stratum.CORE, MetroLine.SYSTEM, "App-ops history via Shizuku", 6),

        TunnelInfo("notifications", "Notifications", Stratum.TOPSOIL, MetroLine.ACTIVITY, "Who notifies, how often, what leaks", 2),
        TunnelInfo("timeline", "Timeline", Stratum.TOPSOIL, MetroLine.ACTIVITY, "App usage and data over time", 2),

        TunnelInfo("traffic", "Traffic", Stratum.SURFACE, MetroLine.NETWORK, "Which apps talk to which domains", 3),
        TunnelInfo("surroundings", "Surroundings", Stratum.SURFACE, MetroLine.NETWORK, "Nearby trackers, Wi-Fi and cell changes", 4),
        TunnelInfo("home_network", "Home network", Stratum.SURFACE, MetroLine.NETWORK, "Devices and open doors on your LAN", 5),

        TunnelInfo("sensors", "Sensors", Stratum.EXPLORE, MetroLine.EXPLORE, "Every sensor on the device", 1),
        TunnelInfo("cameras", "Cameras", Stratum.EXPLORE, MetroLine.EXPLORE, "Camera hardware characteristics", 1),
        TunnelInfo("satellites", "Satellites", Stratum.EXPLORE, MetroLine.EXPLORE, "GNSS constellations overhead", 4),
        TunnelInfo("radio", "Radio", Stratum.EXPLORE, MetroLine.EXPLORE, "Cellular and radio details", 4),
    )

    fun onLine(line: MetroLine) = all.filter { it.line == line }

    fun inStratum(stratum: Stratum) = all.filter { it.stratum == stratum }

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
