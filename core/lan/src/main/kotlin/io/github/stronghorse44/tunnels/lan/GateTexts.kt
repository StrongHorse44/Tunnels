package io.github.stronghorse44.tunnels.lan

/**
 * What Home network says about the gate's states (spec 2.5, 2.6). None of these is a finding (rule 7): they are the
 * network card's state and the last scan's gate line, each shown with its button.
 */
object GateTexts {
    const val RECONFIRM = "Tunnels now also checks your router's identity before it scans. Confirm this network once more."
    const val ROUTER_MISMATCH = "This network has your network's addresses, but its router identified itself differently, so Tunnels sends " +
        "nothing more. If you replaced your router, confirm it again."
    const val ROUTER_SILENT = "Your router didn't identify itself this time, so Tunnels sends nothing more. Try again in a moment."
    const val ROUTER_NOT_ASKED = "Tunnels couldn't ask your router who it is (this network has no IPv4 router address inside it), so it " +
        "sends nothing. Reconnect to the Wi-Fi and try again."
    const val VPN_BLOCKS_WIFI = "A VPN that doesn't let Tunnels bypass it is on, so Tunnels can't reach your Wi-Fi and sends nothing. " +
        "Exclude Tunnels in that VPN app's settings, or disconnect it, then try again."
    const val KEY_CHANGED = "Tunnels' key for confirmed networks changed, so it can't recognise them. Confirm this network again."
    const val ADDRESS_ONLY = "Recognised by address only."
    const val NOT_CONFIRMED = "This Wi-Fi is not confirmed as yours, so Tunnels sends nothing."
    const val STORE_UNAVAILABLE = "Tunnels' key or encrypted store couldn't be used, so it can't tell whether this network is yours. " +
        "Tunnels sends nothing."
    const val WIFI_CHANGED = "The Wi-Fi changed. Try again."

    const val ASK_TITLE = "Is this your own Wi-Fi?"
    const val ASK_TEXT = "Tunnels can't tell it from another with the same addresses."

    const val SILENT_ROUTER_TITLE = "Your router doesn't identify itself"
    const val SILENT_ROUTER_TEXT = "It answered neither UPnP nor HTTPS, so Tunnels can only recognise this network by its addresses, and " +
        "another Wi-Fi with the same addresses would look the same. If you confirm, Tunnels asks you before every scan, and never asks " +
        "this router to close an opening."
    const val NOT_ASKED_ROUTER_TEXT = "This network has no IPv4 router address inside it, so Tunnels couldn't ask the router who it is " +
        "and can only recognise this network by its addresses, and another Wi-Fi with the same addresses would look the same. If you " +
        "confirm, Tunnels asks you before every scan, and never asks this router to close an opening."
    /** Added to the dialog when the network already has a router check, which "Confirm by address only" replaces. */
    const val REPLACES_ROUTER_CHECK = "This replaces the router check Tunnels already has for this network."
    const val CONFIRM_ADDRESS_ONLY = "Confirm by address only"
    const val ASKING_ROUTER = "Asking your router who it is…"
    const val SAVE_FAILED = "Tunnels' key or encrypted store couldn't be used, so nothing was saved."
    const val FULL = "Tunnels keeps at most ${NetworkRecordCodec.MAX_NETWORKS} networks. Forget a network you no longer use first."

    const val CLOSE_MISMATCH = "This isn't the router you confirmed, so Tunnels sends nothing."
    const val CLOSE_SILENT = "Your router didn't identify itself just now, so Tunnels sends nothing. Try again, or remove it on the router's admin page."
    const val CLOSE_NO_UPNP = "Tunnels can't confirm this router's UPnP identity, so it doesn't ask it to change anything. Remove it on the router's admin page."
    const val CLOSE_NOT_ASKED = "Tunnels couldn't ask your router who it is, so it sends nothing. Remove it on the router's admin page."
    const val CLOSE_VPN_BLOCKS_WIFI = "A VPN that doesn't let Tunnels bypass it is on, so Tunnels sends nothing to your router: exclude Tunnels in that VPN app, or disconnect it."
    const val CLOSE_NO_WIFI = "The phone is not on Wi-Fi, so Tunnels sends nothing."
    const val CLOSE_WIFI_CHANGED = "The Wi-Fi changed while Tunnels checked your router, so it sends nothing. Try again."

    /** "Confirmed as your network. Your router identified itself (UPnP id · admin page key)." */
    fun confirmed(upnp: Boolean, tls: Boolean): String {
        val how = listOfNotNull("UPnP id".takeIf { upnp }, "admin page key".takeIf { tls }).joinToString(" · ")
        return "Confirmed as your network. Your router identified itself ($how)."
    }

    /** The card's (and Look closer's) sentence for a refusal [reason]. */
    fun refusal(reason: String?): String = when (reason) {
        LanKeys.REASON_RECONFIRM -> RECONFIRM
        LanKeys.REASON_ROUTER_MISMATCH -> ROUTER_MISMATCH
        LanKeys.REASON_ROUTER_SILENT -> ROUTER_SILENT
        LanKeys.REASON_ROUTER_NOT_ASKED -> ROUTER_NOT_ASKED
        LanKeys.REASON_VPN_BLOCKS_WIFI -> VPN_BLOCKS_WIFI
        LanKeys.REASON_KEY_CHANGED -> KEY_CHANGED
        LanKeys.REASON_ADDRESS_ONLY -> "$ADDRESS_ONLY $ASK_TITLE $ASK_TEXT"
        LanKeys.REASON_STORE_UNAVAILABLE -> STORE_UNAVAILABLE
        LanKeys.REASON_NO_WIFI -> "The phone is not on Wi-Fi."
        LanKeys.REASON_NETWORK_UNKNOWN -> "The network's router and address were not readable, or the Wi-Fi changed. Tunnels sends nothing."
        else -> NOT_CONFIRMED
    }

    /** Close it's one-line answer for a refusal [reason] (spec 2.6). */
    fun closeRefusal(reason: String?): String = when (reason) {
        LanKeys.REASON_ROUTER_MISMATCH -> CLOSE_MISMATCH
        LanKeys.REASON_ROUTER_SILENT -> CLOSE_SILENT
        LanKeys.REASON_ROUTER_NOT_ASKED -> CLOSE_NOT_ASKED
        LanKeys.REASON_VPN_BLOCKS_WIFI -> CLOSE_VPN_BLOCKS_WIFI
        LanKeys.REASON_NO_WIFI -> CLOSE_NO_WIFI
        LanKeys.REASON_NO_UPNP_MATCH, LanKeys.REASON_ADDRESS_ONLY -> CLOSE_NO_UPNP
        LanKeys.REASON_NETWORK_UNKNOWN -> CLOSE_WIFI_CHANGED
        LanKeys.REASON_RECONFIRM, LanKeys.REASON_KEY_CHANGED, LanKeys.REASON_STORE_UNAVAILABLE -> refusal(reason)
        else -> NOT_CONFIRMED
    }

    /** The last scan's gate line: "The last scan stopped at the gate: " + this. */
    fun gateReason(reason: String?): String = when (reason) {
        LanKeys.REASON_NO_WIFI -> "the phone was not on Wi-Fi."
        LanKeys.REASON_NETWORK_UNKNOWN -> "the network's router and address were not readable, or the Wi-Fi changed while Tunnels checked the router."
        LanKeys.REASON_NOT_CONFIRMED -> "this network had not been confirmed as yours."
        LanKeys.REASON_STORE_UNAVAILABLE -> "Tunnels' key or encrypted store could not be used, so it could not tell whether this network is yours."
        LanKeys.REASON_NO_PERMISSION -> "the Nearby devices permission was missing."
        LanKeys.REASON_RECONFIRM -> RECONFIRM
        LanKeys.REASON_ROUTER_MISMATCH -> ROUTER_MISMATCH
        LanKeys.REASON_ROUTER_SILENT -> ROUTER_SILENT
        LanKeys.REASON_ROUTER_NOT_ASKED -> ROUTER_NOT_ASKED
        LanKeys.REASON_VPN_BLOCKS_WIFI -> VPN_BLOCKS_WIFI
        LanKeys.REASON_KEY_CHANGED -> KEY_CHANGED
        LanKeys.REASON_ADDRESS_ONLY -> "this network is recognised by address only, and the scan was not started from Home network's " +
            "\"$ASK_TITLE\" question. Scan from Home network."
        else -> "the network could not be verified as yours."
    }
}
