package io.github.stronghorse44.tunnels.emissions

/**
 * The Faraday bag test: the radios stay on, the phone listens for a while outside the bag, then for a while inside
 * it, and compares. It tests the bag, not the phone. Nothing is stored: the result lives on the screen. Plain Kotlin;
 * the Android side takes the samples.
 */
object BagTest {
    /** Listening outside the bag, to know what there is to block. */
    const val OUTSIDE_MILLIS = 10_000L

    /** Time to put the phone in the bag and close it. */
    const val COUNTDOWN_SECONDS = 10

    /** Listening inside the bag. */
    const val INSIDE_MILLIS = 45_000L

    /** The start of the inside window that is ignored: the modem and the scans take a moment to notice. */
    const val SETTLE_MILLIS = 15_000L

    /**
     * What one window saw. A null field means that channel could not be read in the window (no hardware, switched off,
     * no permission, or Android gave no fresh scan).
     */
    data class Sample(
        val cellInService: Boolean? = null,
        /** `SignalStrength.getLevel()`, 0 (none) to 4. */
        val cellLevel: Int? = null,
        /** Wi-Fi networks in a scan made during the window. */
        val wifiNetworks: Int? = null,
        /** Connected to a Wi-Fi network at the end of the window. */
        val wifiLinked: Boolean? = null,
        /** Distinct Bluetooth devices heard during the window. */
        val bleDevices: Int? = null,
    )

    enum class Channel(val title: String) { CELL("Cellular"), WIFI("Wi-Fi"), BLUETOOTH("Bluetooth") }

    enum class Verdict { BLOCKED, LEAKS, UNTESTED }

    data class ChannelResult(val channel: Channel, val verdict: Verdict, val outside: String, val inside: String, val note: String? = null)

    data class Result(val channels: List<ChannelResult>) {
        val overall: Verdict
            get() = when {
                channels.any { it.verdict == Verdict.LEAKS } -> Verdict.LEAKS
                channels.any { it.verdict == Verdict.BLOCKED } -> Verdict.BLOCKED
                else -> Verdict.UNTESTED
            }

        val headline: String
            get() {
                val leaks = channels.filter { it.verdict == Verdict.LEAKS }.map { it.channel.title }
                val untested = channels.filter { it.verdict == Verdict.UNTESTED }.map { it.channel.title }
                return when (overall) {
                    Verdict.LEAKS -> "The bag leaks: ${leaks.joinToString(", ")} still got through."
                    Verdict.BLOCKED -> "The bag blocked everything Tunnels could test" +
                        (if (untested.isEmpty()) "." else " (not tested: ${untested.joinToString(", ")}).")
                    Verdict.UNTESTED -> "Nothing to test: no signal was seen outside the bag."
                }
            }
    }

    /** Caveats shown with every result. */
    val caveats: List<String> = listOf(
        "A bag that blocks the signals here can still pass a stronger one elsewhere: test next to a router and with full cell bars.",
        "Inside the bag the phone keeps trying to reach a network. Use airplane mode or power off as well when you store it.",
    )

    fun judge(outside: Sample, inside: Sample): Result = Result(listOf(cell(outside, inside), wifi(outside, inside), bluetooth(outside, inside)))

    private fun cellText(s: Sample) = when {
        s.cellInService == null -> "not read"
        s.cellInService && (s.cellLevel ?: 0) >= 1 -> "in service, level ${s.cellLevel}"
        s.cellInService -> "in service, no signal"
        else -> "no service"
    }

    private fun cell(o: Sample, i: Sample): ChannelResult {
        val testable = o.cellInService == true && (o.cellLevel ?: 0) >= 1
        if (!testable) return ChannelResult(Channel.CELL, Verdict.UNTESTED, cellText(o), cellText(i), "No cell signal outside the bag to block.")
        val leaks = i.cellInService == true && (i.cellLevel ?: 0) >= 1
        return ChannelResult(Channel.CELL, if (leaks) Verdict.LEAKS else Verdict.BLOCKED, cellText(o), cellText(i))
    }

    private fun wifiText(s: Sample): String {
        val scan = s.wifiNetworks?.let { "$it ${if (it == 1) "network" else "networks"}" } ?: "no fresh scan"
        val link = when (s.wifiLinked) {
            true -> ", connected"
            false -> ", not connected"
            null -> ""
        }
        return scan + link
    }

    private fun wifi(o: Sample, i: Sample): ChannelResult {
        val seenOutside = (o.wifiNetworks ?: 0) > 0 || o.wifiLinked == true
        if (!seenOutside) return ChannelResult(Channel.WIFI, Verdict.UNTESTED, wifiText(o), wifiText(i), "No Wi-Fi seen outside the bag.")
        val leaks = (i.wifiNetworks ?: 0) > 0 || i.wifiLinked == true
        if (leaks) return ChannelResult(Channel.WIFI, Verdict.LEAKS, wifiText(o), wifiText(i))
        val scanEvidence = (o.wifiNetworks ?: 0) > 0 && i.wifiNetworks != null
        val linkEvidence = o.wifiLinked == true && i.wifiLinked != null
        return if (scanEvidence || linkEvidence) ChannelResult(Channel.WIFI, Verdict.BLOCKED, wifiText(o), wifiText(i))
        else ChannelResult(Channel.WIFI, Verdict.UNTESTED, wifiText(o), wifiText(i), "Android gave no fresh Wi-Fi scan inside the bag.")
    }

    private fun bleText(n: Int?) = n?.let { "$it ${if (it == 1) "device" else "devices"}" } ?: "not read"

    private fun bluetooth(o: Sample, i: Sample): ChannelResult {
        if ((o.bleDevices ?: 0) == 0) {
            return ChannelResult(Channel.BLUETOOTH, Verdict.UNTESTED, bleText(o.bleDevices), bleText(i.bleDevices), "No Bluetooth device heard outside the bag.")
        }
        val n = i.bleDevices ?: return ChannelResult(Channel.BLUETOOTH, Verdict.UNTESTED, bleText(o.bleDevices), bleText(null), "Bluetooth could not listen inside the bag.")
        return ChannelResult(Channel.BLUETOOTH, if (n > 0) Verdict.LEAKS else Verdict.BLOCKED, bleText(o.bleDevices), bleText(n))
    }

    /** What keeps a channel from being tested, said before the test starts. */
    fun preflight(r: EmissionsReading): List<String> = buildList {
        if (r.airplane == true) add("Airplane mode is on, so cellular cannot be tested. Turn it off for the test.")
        if (r.state(Emitter.WIFI) == EmitterState.OFF) add("Wi-Fi is off, so Wi-Fi cannot be tested. Turn it on for the test.")
        if (r.state(Emitter.BLUETOOTH) == EmitterState.OFF) add("Bluetooth is off, so Bluetooth cannot be tested. Turn it on for the test.")
        if (r.location == false && r.state(Emitter.WIFI) != EmitterState.OFF) {
            add("Location is off, so Android gives no Wi-Fi scan results; only a connected Wi-Fi network can be tested.")
        }
    }
}

/** The Quick Settings tile's look for a reading: lit when the phone is quiet. */
object TileText {
    data class Look(val active: Boolean, val subtitle: String)

    fun of(r: EmissionsReading): Look = when (r.level) {
        QuietLevel.SILENT -> Look(true, "Silent")
        QuietLevel.QUIET_UNREAD -> Look(true, "Quiet")
        else -> Look(false, "${r.on.size} on")
    }
}
