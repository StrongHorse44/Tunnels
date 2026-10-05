package io.github.stronghorse44.tunnels.posture

/** The words of the Posture card, kept here so they can be tested off-device. Unknown is never styled as a finding. */
object PostureText {
    const val DURESS_NOTE = "No app can read whether a duress PIN is set. Check it yourself: Security & privacy > Device unlock > Duress password."
    const val NEEDS_SHIZUKU_END = "Nothing has been read."
    const val NOT_SCANNED = "Scan Deep mode to read posture."

    /** Items whose timer was not found in Settings on RJ's build (step 0): no instruction to change them. */
    private val NOT_IN_SETTINGS = setOf(PostureKeys.WIFI_AUTO_OFF, PostureKeys.BT_AUTO_OFF, PostureKeys.NFC_AUTO_OFF)

    fun needsShizuku(reason: String) = "Posture needs Shizuku: $reason. $NEEDS_SHIZUKU_END"

    /** The state word of a row: `ok`, `weak`, `unknown` or `n/a`. */
    fun stateWord(state: PostureState): String = when (state) {
        PostureState.GOOD -> "ok"
        PostureState.WEAK -> "weak"
        PostureState.UNKNOWN -> "unknown"
        PostureState.NA -> "n/a"
    }

    /** The one detail line under a row. */
    fun detail(r: Reading): String = when (r.state) {
        PostureState.GOOD, PostureState.WEAK -> r.value ?: ""
        PostureState.NA -> when (r.item.id) {
            PostureKeys.VPN_ALWAYS_ON -> "No always-on VPN is set."
            PostureKeys.VPN_LOCKDOWN -> "No always-on VPN, so there is no lockdown to judge."
            else -> "${r.value ?: "Read"}. Shown only: Tunnels can't tell whether a second PIN is in use."
        }
        PostureState.UNKNOWN -> when (r.why) {
            PostureWhy.ABSENT -> if (r.item.id in NOT_IN_SETTINGS) {
                "Not set on this phone and not found in Settings on this build; GrapheneOS's built-in default is ${r.item.sourceDefault}."
            } else
                "Not set on this phone, so Tunnels can't tell (GrapheneOS's built-in default is ${r.item.sourceDefault}). " +
                    "Change it once in Settings, then rescan."
            PostureWhy.UNREADABLE -> "Could not be read this scan."
            PostureWhy.MALFORMED -> "Unexpected value; not judged."
            PostureWhy.UNCONFIRMED -> "Reads ${r.value ?: "a value"}; the key is not confirmed on this phone yet."
            null -> "Not judged."
        }
    }
}
