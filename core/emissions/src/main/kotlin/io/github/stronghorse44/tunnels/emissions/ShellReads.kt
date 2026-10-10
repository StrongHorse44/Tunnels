package io.github.stronghorse44.tunnels.emissions

/**
 * The states only the shell can read, and parsers for what it prints. The UWB and tethering parsers are unconfirmed:
 * the formats come from AOSP and have not been seen on RJ's phone, so their readings are shown but raise no finding
 * until [UWB_CONFIRMED] or [TETHERING_CONFIRMED] is flipped after a device check.
 */
object ShellReads {
    const val BLE_SCANNING = "settings get global ble_scan_always_enabled"
    const val UWB_STATUS = "cmd uwb status"
    const val TETHERING = "dumpsys tethering"

    const val UWB_CONFIRMED = false
    const val TETHERING_CONFIRMED = false

    val ALL: List<String> = listOf(BLE_SCANNING, UWB_STATUS, TETHERING) + RadioUse.Kind.entries.flatMap { k -> k.ops.map(RadioUse::command) }

    /** `settings get` of a 0/1 flag. `null` (never set) means the platform default, which Tunnels cannot see. */
    fun flag(output: String): EmitterState = when (output.trim()) {
        "1" -> EmitterState.ON
        "0" -> EmitterState.OFF
        else -> EmitterState.UNKNOWN
    }

    private val uwbState = Regex("""(?i)\b(?:uwb\s+)?(?:adapter\s+)?state\s*[:=]\s*([A-Za-z_0-9]+)""")
    private val uwbIs = Regex("""(?i)\buwb\s+(?:is\s+)?(enabled|disabled)\b""")

    /**
     * `cmd uwb status`, unconfirmed. Accepts "Uwb state: enabled", "state = STATE_ENABLED_ACTIVE", "state: 2" and
     * "UWB is disabled" (UwbManager's adapter states: 0 disabled, 1 enabled and idle, 2 enabled and ranging).
     */
    fun uwb(output: String): EmitterState {
        if (ShellOutput.failed(output)) return EmitterState.UNKNOWN
        uwbIs.find(output)?.let { return if (it.groupValues[1].equals("enabled", ignoreCase = true)) EmitterState.ON else EmitterState.OFF }
        val value = uwbState.find(output)?.groupValues?.get(1)?.uppercase() ?: return EmitterState.UNKNOWN
        return when {
            value == "0" || value.contains("DISABLED") -> EmitterState.OFF
            value == "1" || value == "2" || value.contains("ENABLED") || value == "ON" -> EmitterState.ON
            value == "OFF" -> EmitterState.OFF
            else -> EmitterState.UNKNOWN
        }
    }

    private val tetherHeader = Regex("""^\s*Tether state:""")
    private val tetherLine = Regex("""^\s*([A-Za-z0-9_.:-]+) - ([A-Za-z]+)State\b""")

    data class Tethering(val state: EmitterState, val kinds: List<String>)

    /**
     * `dumpsys tethering`, unconfirmed. Its "Tether state:" section has one `<iface> - <Name>State` line per
     * interface; Tethered and LocalHotspot mean the phone is serving a network on it. No section: unknown.
     */
    fun tethering(output: String): Tethering {
        if (ShellOutput.failed(output)) return Tethering(EmitterState.UNKNOWN, emptyList())
        var inSection = false
        var sawSection = false
        val kinds = LinkedHashSet<String>()
        for (line in output.lineSequence()) {
            if (tetherHeader.containsMatchIn(line)) {
                inSection = true
                sawSection = true
                continue
            }
            if (!inSection) continue
            val m = tetherLine.find(line)
            if (m == null) {
                // The section ends at the first line that is not an interface line (blank lines aside).
                if (line.isNotBlank()) inSection = false
                continue
            }
            if (m.groupValues[2] == "Tethered" || m.groupValues[2] == "LocalHotspot") kinds += kindOf(m.groupValues[1])
        }
        if (!sawSection) return Tethering(EmitterState.UNKNOWN, emptyList())
        return Tethering(if (kinds.isEmpty()) EmitterState.OFF else EmitterState.ON, kinds.sorted())
    }

    /** Interface name to the kind of tethering it carries. */
    fun kindOf(iface: String): String {
        val name = iface.lowercase()
        return when {
            name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> "wifi"
            name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") -> "usb"
            name.startsWith("bt") -> "bluetooth"
            else -> "other"
        }
    }
}
