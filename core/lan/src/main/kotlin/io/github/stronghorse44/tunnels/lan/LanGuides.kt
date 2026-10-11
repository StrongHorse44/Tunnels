package io.github.stronghorse44.tunnels.lan

/** The short "How to fix" text behind each finding kind. Plain words, specific steps, no jargon. */
object LanGuides {
    fun fix(kind: String, riskyPorts: Collection<Int> = emptyList()): String = when (kind) {
        LanRules.RISKY_SERVICE -> {
            val names = riskyPorts.mapNotNull { PortCatalog.byPort(it)?.service }.distinct()
            val what = if (names.isEmpty()) "the login and file-sharing services" else names.joinToString(", ")
            val adb = if (5555 in riskyPorts) " For Android debug: on the TV box or phone, open Settings > Developer options and turn off USB, network and wireless debugging." else ""
            "On that device, turn off $what if you do not use them, or give them a strong, unique password. " +
                "Update its firmware. If it is a gadget you cannot configure, move it to a guest Wi-Fi so it cannot reach your other devices.$adb"
        }
        LanRules.PORT_OPENED ->
            "Close it removes the opening now, but the device can ask for it again while UPnP is on. To stop that, open your " +
                "router's admin page, find UPnP (under Advanced, NAT, Firewall or Port forwarding), delete the opening and turn UPnP off. " +
                "If you need it (a game console, a media server), keep it, update that device and give it a strong password."
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
        LanRules.WATCHED_BACK ->
            "If you expected it, there is nothing to do. If not, open your router's admin page and check its client list " +
                "for when it connected, or tap Stop watching if you no longer need to know."
        LanRules.WATCHED_CHANGED ->
            "A newly opened port is a new way in: open the device's detail in Home network and Look closer to see what it " +
                "says, then update its firmware or switch the service off in its app. A new name can mean a reset or a " +
                "different device on the same address."
        LanRules.CENSUS_NOT_SET_UP ->
            "Check each device in the list. If you know them all, tap These are all mine; " +
                "if one is a stranger, check your router's client list first."
        else -> "Open your router's admin page, update its firmware, and check which devices and services are allowed."
    }

    /**
     * How to track down a device Tunnels cannot name (RJ, 2026-10-11): what the router knows that a phone cannot, and two
     * ways to find out by elimination.
     */
    val TRACK_DOWN: List<String> = listOf(
        "Open your router's admin page (the button below) and find its list of connected devices. It shows each device's " +
            "hardware address, often its maker, and how long it has been connected: things Android hides from apps.",
        "In that list, a hardware address whose second character is 2, 6, A or E is a private, random one. Phones, tablets " +
            "and laptops use those; cameras, TVs and gadgets usually show their real maker.",
        "Switch off or unplug your devices one at a time and scan again. The one whose address disappears is it.",
        "Or block it in the router (or change the Wi-Fi password and reconnect only what you know): whatever stops working " +
            "tells you what it was.",
    )

    /** Why Look closer guessed a phone or tablet from silence ([LookCloser.Findings.quietLikeAndroid]), said under its guess. */
    const val QUIET_ANDROID =
        "It refused every door, runs no service, gave no name and announces nothing, and its ping looks like Android's: " +
            "that is how an Android phone or tablet behaves. A cloud-only gadget, such as a video doorbell, can look the same; " +
            "the router's device list can tell (a hardware address whose second character is 2, 6, A or E is a phone's or tablet's)."

    /** What "Look closer" keeps, said on the screen next to its button. */
    const val CLOSER_KEEPS =
        "Asks only this device, on this network. Tunnels keeps its kind, maker, open ports and names for 30 days; " +
            "the page text and greetings it shows here are not kept."

    /** Label for the finding's primary action. */
    const val FIX_LABEL = "How to fix"
    const val ROUTER_ADMIN_LABEL = "Open router admin"
    const val WIFI_SETTINGS_LABEL = "Wi-Fi settings"
    const val MINE_LABEL = "Mine: add to this network's list"
    const val ALL_MINE_LABEL = "These are all mine"
    const val STOP_WATCHING_LABEL = "Stop watching"
    const val CLOSE_OPENING_LABEL = "Close it"

    /** What a device's notes (its name, how often it is here, Watch) are and where they stay, said in its detail. */
    const val NOTES_KEEP =
        "Your names, these counts and the watch list stay on this phone, in Tunnels' encrypted store, for this network only. " +
            "Counts of a device not seen for 30 days are forgotten."

    /** Why a device with no identity is noted by its address, said in its detail. */
    const val BY_ADDRESS =
        "This device gave nothing to recognise it by, so its name and watch follow its address: if the router gives the " +
            "address to another such device, they go with it. Look closer: if it answers with its own name, Tunnels asks " +
            "it again at each scan, and it can then go on your list and keep its notes wherever it moves."

    /** Router-level kinds get an "Open router admin" action. */
    fun isRouterKind(kind: String) = kind == LanRules.UPNP_IGD_ENABLED || kind == LanRules.DNS_HIJACK

    /** A stranger on the network is checked against the router's client list, so that finding opens the router admin page too. */
    fun wantsRouterAdmin(kind: String) = isRouterKind(kind) || kind == LanRules.UNKNOWN_DEVICE || kind == LanRules.WATCHED_BACK || kind == LanRules.PORT_OPENED
}
