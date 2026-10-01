package io.github.stronghorse44.tunnels.model

/** Static description of a tunnel for the strata home screen. */
data class TunnelInfo(
    val id: String,
    val title: String,
    val stratum: Stratum,
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
        TunnelInfo("surroundings", "Surroundings", Stratum.SURFACE, "Nearby trackers, Wi-Fi and cell changes", 4),
        TunnelInfo("home_network", "Home network", Stratum.SURFACE, "Devices and open doors on your LAN", 5),
        TunnelInfo("traffic", "Traffic", Stratum.SURFACE, "Which apps talk to which domains", 3),

        TunnelInfo(INSTALLER, "Installer", Stratum.TOPSOIL, "Inspect and install APKs and app bundles", null),
        TunnelInfo(UNZIP, "Unzip", Stratum.TOPSOIL, "Open zip, 7z, tar, gz, xz and bz2 archives", null),
        TunnelInfo("permissions", "Permissions", Stratum.TOPSOIL, "Declared vs granted, per app", 1),
        TunnelInfo("apk_excavation", "APK excavation", Stratum.TOPSOIL, "Trackers, certs and native libs inside apps", 1),
        TunnelInfo("doors", "Doors", Stratum.TOPSOIL, "Exported components and link handlers", 1),
        TunnelInfo("notifications", "Notifications", Stratum.TOPSOIL, "Who notifies, how often, what leaks", 2),
        TunnelInfo("timeline", "Timeline", Stratum.TOPSOIL, "App usage and data over time", 2),

        TunnelInfo("system_packages", "System packages", Stratum.BEDROCK, "Every system package and what it does", 1),
        TunnelInfo("trust_store", "Trust store", Stratum.BEDROCK, "System and user certificate authorities", 1),
        TunnelInfo("hardening", "Hardening audit", Stratum.BEDROCK, "Exploit mitigations in native code", 1),

        TunnelInfo("silicon", "Silicon", Stratum.CORE, "Verified boot and hardware attestation", 1),
        TunnelInfo("deep_mode", "Deep mode", Stratum.CORE, "App-ops history via Shizuku", 6),

        TunnelInfo("satellites", "Satellites", Stratum.EXPLORE, "GNSS constellations overhead", 4),
        TunnelInfo("sensors", "Sensors", Stratum.EXPLORE, "Every sensor on the device", 1),
        TunnelInfo("cameras", "Cameras", Stratum.EXPLORE, "Camera hardware characteristics", 1),
        TunnelInfo("radio", "Radio", Stratum.EXPLORE, "Cellular and radio details", 4),
    )

    fun inStratum(stratum: Stratum) = all.filter { it.stratum == stratum }

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
