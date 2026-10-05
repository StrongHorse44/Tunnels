package io.github.stronghorse44.tunnels.lan

/** The short "How to fix" text behind each finding kind. Plain words, specific steps, no jargon. */
object LanGuides {
    fun fix(kind: String, riskyPorts: Collection<Int> = emptyList()): String = when (kind) {
        LanRules.RISKY_SERVICE -> {
            val names = riskyPorts.mapNotNull { PortCatalog.byPort(it)?.service }.distinct()
            val what = if (names.isEmpty()) "the login and file-sharing services" else names.joinToString(", ")
            "On that device, turn off $what if you do not use them, or give them a strong, unique password. " +
                "Update its firmware. If it is a gadget you cannot configure, move it to a guest Wi-Fi so it cannot reach your other devices."
        }
        LanRules.CAMERA_OPEN_WEB ->
            "In the camera's app or web page: change the default password, update the firmware, and switch off web access, RTSP and UPnP if you do not need them. " +
                "Put cameras on a separate guest or IoT Wi-Fi so a weak camera cannot reach your laptop."
        LanRules.UPNP_IGD_ENABLED ->
            "Open your router's admin page and find UPnP (usually under Advanced, NAT, Firewall or Port forwarding). Turn it off. " +
                "Set up the one or two port forwards you really need by hand, then update the router firmware and check its admin password is not the default."
        LanRules.DNS_HIJACK ->
            "In your router's admin page set the DNS servers to a resolver you trust (your ISP's or a public one) and change the admin password; if the setting keeps changing, reset the router and update its firmware. " +
                "On this phone, turn on Private DNS (Settings > Network & internet > Private DNS) so lookups bypass the router."
        // NEW_HOST is retired as a rule; its guide stays so a finding stored by an older build still gets its action.
        LanRules.UNKNOWN_DEVICE, LanRules.NEW_HOST ->
            "If you do not recognise this device, open your router's admin page and check its client list. " +
                "Change the Wi-Fi password and reconnect only the devices you know; turn off WPS."
        LanRules.CENSUS_NOT_SET_UP ->
            "Check each device in the list. If you know them all, tap These are all mine; " +
                "if one is a stranger, check your router's client list first."
        else -> "Open your router's admin page, update its firmware, and check which devices and services are allowed."
    }

    /** Label for the finding's primary action. */
    const val FIX_LABEL = "How to fix"
    const val ROUTER_ADMIN_LABEL = "Open router admin"
    const val WIFI_SETTINGS_LABEL = "Wi-Fi settings"
    const val MINE_LABEL = "Mine: add to this network's list"
    const val ALL_MINE_LABEL = "These are all mine"

    /** Router-level kinds get an "Open router admin" action. */
    fun isRouterKind(kind: String) = kind == LanRules.UPNP_IGD_ENABLED || kind == LanRules.DNS_HIJACK
}
