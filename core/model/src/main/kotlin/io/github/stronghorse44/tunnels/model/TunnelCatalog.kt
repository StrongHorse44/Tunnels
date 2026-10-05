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
    /**
     * Build phase a planned tunnel lands in; null once it ships. Every tunnel on the map ships in this build:
     * the home screen also treats a tunnel as live when its module registered, so a stale number here can
     * never hide a built tunnel behind "phase N".
     */
    val phase: Int?,
) {
    val isLive: Boolean get() = phase == null
}

object TunnelCatalog {
    const val INSTALLER = "installer"
    const val UNZIP = "unzip"
    const val BACKUPS = "backups"

    /** The derived tunnel that joins the others ([DerivedTunnel]); on the home map it is Central, where the lines meet. */
    const val CROSSROADS = "crossroads"

    val all: List<TunnelInfo> = listOf(
        TunnelInfo(INSTALLER, "Installer", Stratum.TOPSOIL, MetroLine.FILES, "Inspect and install APKs and app bundles", null),
        TunnelInfo(UNZIP, "Unzip", Stratum.TOPSOIL, MetroLine.FILES, "Open zip, 7z, tar, gz, xz and bz2 archives", null),
        TunnelInfo(BACKUPS, "Backups", Stratum.TOPSOIL, MetroLine.FILES, "Is each app's newest export recent enough", null),

        TunnelInfo("permissions", "Permissions", Stratum.TOPSOIL, MetroLine.INSPECT, "Declared vs granted, per app", null),
        TunnelInfo("apk_excavation", "APK excavation", Stratum.TOPSOIL, MetroLine.INSPECT, "Trackers, certs and native libs inside apps", null),
        TunnelInfo("doors", "Doors", Stratum.TOPSOIL, MetroLine.INSPECT, "Exported components and link handlers", null),
        TunnelInfo("hardening", "Hardening audit", Stratum.BEDROCK, MetroLine.INSPECT, "Exploit mitigations in native code", null),
        TunnelInfo(CROSSROADS, "Crossroads", Stratum.TOPSOIL, MetroLine.INSPECT, "Findings that take two tunnels to see", null),

        TunnelInfo("system_packages", "System packages", Stratum.BEDROCK, MetroLine.SYSTEM, "Every system package and what it does", null),
        TunnelInfo("trust_store", "Trust store", Stratum.BEDROCK, MetroLine.SYSTEM, "System and user certificate authorities", null),
        TunnelInfo("silicon", "Silicon", Stratum.CORE, MetroLine.SYSTEM, "Verified boot and hardware attestation", null),
        TunnelInfo("deep_mode", "Deep mode", Stratum.CORE, MetroLine.SYSTEM, "App-ops history via Shizuku", null),

        TunnelInfo("notifications", "Notifications", Stratum.TOPSOIL, MetroLine.ACTIVITY, "Who notifies, how often, what leaks", null),
        TunnelInfo("timeline", "Timeline", Stratum.TOPSOIL, MetroLine.ACTIVITY, "App usage and data over time", null),

        TunnelInfo("traffic", "Traffic", Stratum.SURFACE, MetroLine.NETWORK, "Which apps talk to which domains", null),
        TunnelInfo("surroundings", "Surroundings", Stratum.SURFACE, MetroLine.NETWORK, "Nearby trackers, Wi-Fi and cell changes", null),
        TunnelInfo("home_network", "Home network", Stratum.SURFACE, MetroLine.NETWORK, "Devices and open doors on your LAN", null),

        TunnelInfo("sensors", "Sensors", Stratum.EXPLORE, MetroLine.EXPLORE, "Every sensor on the device", null),
        TunnelInfo("cameras", "Cameras", Stratum.EXPLORE, MetroLine.EXPLORE, "Camera hardware characteristics", null),
        TunnelInfo("satellites", "Satellites", Stratum.EXPLORE, MetroLine.EXPLORE, "GNSS constellations overhead", null),
        TunnelInfo("radio", "Radio", Stratum.EXPLORE, MetroLine.EXPLORE, "Cellular and radio details", null),
    )

    fun onLine(line: MetroLine) = all.filter { it.line == line }

    fun inStratum(stratum: Stratum) = all.filter { it.stratum == stratum }

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
