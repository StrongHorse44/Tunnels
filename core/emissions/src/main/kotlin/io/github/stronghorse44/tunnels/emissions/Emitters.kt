package io.github.stronghorse44.tunnels.emissions

/**
 * Every transmitter the Emissions tunnel judges, in the order of its card. GNSS is not here: it only receives, so it
 * is shown as location ([EmissionsKeys.LOCATION]) and marked as not an emission. Plain Kotlin.
 */
enum class Emitter(val id: String, val title: String, val sends: String, val restorable: Boolean) {
    CELLULAR(
        "cellular", "Cellular",
        "Registers with nearby towers and checks in with them while idle; carries calls, texts and mobile data.",
        restorable = true,
    ),
    WIFI(
        "wifi", "Wi-Fi",
        "Probe requests while it looks for networks, then the traffic of the network it joins.",
        restorable = true,
    ),
    WIFI_SCANNING(
        "wifi_scanning", "Wi-Fi scanning",
        "Probe requests for location, even while Wi-Fi itself is off.",
        restorable = true,
    ),
    BLUETOOTH(
        "bluetooth", "Bluetooth",
        "Advertisements and connections to paired devices.",
        restorable = true,
    ),
    BLE_SCANNING(
        "ble_scanning", "Bluetooth scanning",
        "Bluetooth Low Energy scans for location, even while Bluetooth itself is off; active scans send requests.",
        restorable = true,
    ),
    NFC(
        "nfc", "NFC",
        "A short-range field that polls for tags and cards while the screen is on.",
        restorable = true,
    ),
    UWB(
        "uwb", "Ultra-wideband",
        "Ranging pulses to nearby UWB devices and tags.",
        restorable = true,
    ),
    HOTSPOT(
        "hotspot", "Hotspot and tethering",
        "Beacons for a network of its own and the traffic of devices that join it.",
        // Turning a hotspot back on needs its name and password: the user does it in Settings.
        restorable = false,
    ),
    ;

    companion object {
        fun byId(id: String): Emitter? = entries.firstOrNull { it.id == id }
    }
}

enum class EmitterState(val id: String) {
    ON("on"),
    OFF("off"),

    /** The phone has no such hardware. */
    ABSENT("absent"),

    /** Tunnels cannot read it this scan (most often: it needs Shizuku). */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun byId(id: String?): EmitterState = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Where a reading came from: Android's app APIs, or the Shizuku shell. */
enum class ReadSource(val id: String) {
    APP("app"),
    SHELL("shell"),
    ;

    companion object {
        fun byId(id: String?): ReadSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One transmitter's state. [confirmed] is false while the way it was read has not been seen to work on the owner's phone
 * (a parser of shell output Tunnels has not checked there yet): such a reading is shown but never raises a finding.
 */
data class EmitterReading(
    val emitter: Emitter,
    val state: EmitterState,
    val source: ReadSource? = null,
    val confirmed: Boolean = true,
)

/** Shizuku's shell: whether Tunnels can use it right now. */
enum class ShellAccess(val id: String) {
    READY("yes"),
    NOT_INSTALLED("not installed"),
    NOT_RUNNING("not running"),
    NOT_GRANTED("not granted"),
    ;

    companion object {
        fun byId(id: String?): ShellAccess = entries.firstOrNull { it.id == id } ?: NOT_RUNNING
    }
}

/** What one scan read, before it becomes observations. */
data class EmissionsReading(
    val emitters: Map<Emitter, EmitterReading>,
    /** Airplane mode; null when unread. */
    val airplane: Boolean? = null,
    /** `present`, `none`, `locked` or `unknown`. */
    val sim: String? = null,
    val mobileData: Boolean? = null,
    /** The location switch; GNSS only listens, so this is not an emission. */
    val location: Boolean? = null,
    /** Which tethering kinds are up (`wifi`, `usb`, `bluetooth`, `other`); empty when none or unread. */
    val hotspotKinds: List<String> = emptyList(),
    val shell: ShellAccess = ShellAccess.NOT_RUNNING,
    /** Counts of apps that used each radio recently; null when it was not read (no shell). */
    val appUse: AppUseCounts? = null,
) {
    fun reading(e: Emitter): EmitterReading = emitters[e] ?: EmitterReading(e, EmitterState.UNKNOWN)

    fun state(e: Emitter): EmitterState = reading(e).state

    /** Transmitters the phone has, in card order. */
    val present: List<Emitter> get() = Emitter.entries.filter { state(it) != EmitterState.ABSENT }

    val on: List<Emitter> get() = Emitter.entries.filter { state(it) == EmitterState.ON }

    val off: List<Emitter> get() = Emitter.entries.filter { state(it) == EmitterState.OFF }

    val unknown: List<Emitter> get() = Emitter.entries.filter { state(it) == EmitterState.UNKNOWN }

    val level: QuietLevel get() = QuietLevel.of(this)
}

/** How many apps used each kind of radio in the last [RadioUse.RECENT_MINUTES] minutes, read through the shell. */
data class AppUseCounts(val counts: Map<RadioUse.Kind, Int>, val failed: Boolean = false)
