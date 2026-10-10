package io.github.stronghorse44.tunnels.emissions

/** How quiet the phone is, as far as Android reports. Never proof: see [FullSilence]. */
enum class QuietLevel(val id: String, val label: String) {
    /** Nothing off. */
    LOUD("loud", "Transmitting"),

    /** Some transmitters on, some off. */
    PARTLY("partly", "Partly quiet"),

    /** Nothing Tunnels can read is on, but some states are unknown (they need Shizuku). */
    QUIET_UNREAD("quiet_unread", "Quiet, not all read"),

    /** Every transmitter Android reports is off. */
    SILENT("silent", "Silent, as far as Android says"),
    ;

    companion object {
        fun byId(id: String?): QuietLevel? = entries.firstOrNull { it.id == id }

        fun of(r: EmissionsReading): QuietLevel = when {
            r.on.isEmpty() && r.unknown.isEmpty() -> SILENT
            r.on.isEmpty() -> QUIET_UNREAD
            r.off.isEmpty() -> LOUD
            else -> PARTLY
        }
    }
}

/** A Settings screen for a transmitter: the user turns it off there when Tunnels has no shell. */
data class SettingsRoute(val action: String, val label: String, val path: String)

/** The Settings fallbacks, spelled out so this module stays plain Kotlin (`android.provider.Settings` values). */
object EmitterSettings {
    const val ACTION_AIRPLANE = "android.settings.AIRPLANE_MODE_SETTINGS"
    const val ACTION_WIFI_PANEL = "android.settings.panel.action.WIFI"
    const val ACTION_LOCATION = "android.settings.LOCATION_SOURCE_SETTINGS"
    const val ACTION_BLUETOOTH = "android.settings.BLUETOOTH_SETTINGS"
    const val ACTION_NFC_PANEL = "android.settings.panel.action.NFC"
    const val ACTION_SETTINGS = "android.settings.SETTINGS"
    const val ACTION_NETWORK = "android.settings.WIRELESS_SETTINGS"

    fun routeFor(e: Emitter): SettingsRoute = when (e) {
        Emitter.CELLULAR -> SettingsRoute(ACTION_AIRPLANE, "Airplane mode", "Network & internet > Airplane mode")
        Emitter.WIFI -> SettingsRoute(ACTION_WIFI_PANEL, "Wi-Fi panel", "Network & internet > Internet > Wi-Fi")
        Emitter.WIFI_SCANNING -> SettingsRoute(ACTION_LOCATION, "Location settings", "Location > Location services > Wi-Fi scanning")
        Emitter.BLUETOOTH -> SettingsRoute(ACTION_BLUETOOTH, "Bluetooth settings", "Connected devices > Connection preferences > Bluetooth")
        Emitter.BLE_SCANNING -> SettingsRoute(ACTION_LOCATION, "Location settings", "Location > Location services > Bluetooth scanning")
        Emitter.NFC -> SettingsRoute(ACTION_NFC_PANEL, "NFC panel", "Connected devices > Connection preferences > NFC")
        Emitter.UWB -> SettingsRoute(ACTION_SETTINGS, "Settings", "Connected devices > Connection preferences > Ultra-Wideband (UWB)")
        Emitter.HOTSPOT -> SettingsRoute(ACTION_NETWORK, "Network settings", "Network & internet > Hotspot & tethering")
    }
}

/**
 * The answer to "how much can be silenced": what is on, which of it Tunnels can turn off itself (through the shell),
 * which needs the user in Settings, and what stays unknown.
 */
data class SilenceReport(
    val level: QuietLevel,
    val on: List<Emitter>,
    val byTunnels: List<Emitter>,
    val byYou: List<Emitter>,
    val unknown: List<Emitter>,
    val present: Int,
) {
    val headline: String
        get() = when (level) {
            QuietLevel.SILENT -> "All ${present} transmitters Android reports are off."
            QuietLevel.QUIET_UNREAD -> "Nothing Tunnels can read is on; ${unknown.size} ${noun(unknown.size)} unread without Shizuku."
            else -> "${on.size} of $present ${noun(present)} on."
        }

    val howFar: String
        get() = when {
            on.isEmpty() -> "Software has done what it can. For full silence see the steps below."
            byYou.isEmpty() -> "Tunnels can turn off all ${on.size} with one tap."
            byTunnels.isEmpty() -> "You can turn off all ${on.size} from Android's own panels; Tunnels opens each one."
            else -> "Tunnels can turn off ${byTunnels.size}; ${byYou.size} ${if (byYou.size == 1) "needs" else "need"} you in Settings: " +
                byYou.joinToString(", ") { it.title } + "."
        }

    companion object {
        fun of(r: EmissionsReading): SilenceReport {
            val shell = r.shell == ShellAccess.READY
            val on = r.on
            val byTunnels = if (shell) on.filter { ShellPlan.silenceStep(it, r) != null } else emptyList()
            return SilenceReport(r.level, on, byTunnels, on - byTunnels.toSet(), r.unknown, r.present.size)
        }

        private fun noun(n: Int) = if (n == 1) "transmitter" else "transmitters"
    }
}

/**
 * What it takes to silence the phone completely, and what software cannot promise. Shown under the transmitters.
 * The Pixel powered-off beacon line is unverified on GrapheneOS (BUILD_PLAN device checks).
 */
object FullSilence {
    data class Step(val title: String, val body: String)

    val steps: List<Step> = listOf(
        Step(
            "Airplane mode, then check Wi-Fi and Bluetooth",
            "Airplane mode turns off the cellular radio. Android remembers if you turned Wi-Fi or Bluetooth back on " +
                "during airplane mode and turns them on again next time, so check both are still off.",
        ),
        Step(
            "Turn off scanning",
            "Location > Location services: Wi-Fi scanning and Bluetooth scanning keep both radios working for location " +
                "even while they look off.",
        ),
        Step(
            "NFC, UWB and the hotspot",
            "Turn NFC off, Ultra-Wideband off where the phone has it, and any hotspot or tethering.",
        ),
        Step(
            "Power off",
            "Off is the only state where Android is not running. Pixel 8 and later can keep a Find Hub beacon going while " +
                "off, but only once Google's Find Hub has set it up; GrapheneOS does not ship it. That has not been checked " +
                "on this phone yet.",
        ),
        Step(
            "A Faraday bag you have tested",
            "The only method that does not rely on trusting software. Test the bag below, or call the phone while it is " +
                "inside: the call must not ring. A phone left on inside a bag keeps searching for a network and drains faster.",
        ),
    )

    /** What software cannot promise, in one place for the screen. */
    val limits: List<String> = listOf(
        "Airplane mode is a request to the modem. Tunnels reads what Android reports; it cannot see inside the modem.",
        "Emergency calls work in airplane mode and without a SIM: the modem wakes for them when you dial.",
        "Removing a SIM stops registration with your carrier, not the modem: it still listens so emergency calls work.",
        "GNSS (GPS) only receives. Turning location off is about privacy, not emissions.",
    )
}
