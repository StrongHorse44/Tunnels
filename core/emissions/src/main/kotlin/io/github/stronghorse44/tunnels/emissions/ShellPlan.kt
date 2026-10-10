package io.github.stronghorse44.tunnels.emissions

/**
 * One transmitter's change through the Shizuku shell. [commands] are alternatives: the first one whose output does not
 * read as an error ([ShellOutput.failed]) wins, the rest are not run. [confirmed] is false until the command has been
 * seen to work on RJ's phone; it is run anyway (a wrong command only prints an error) and the result is always checked
 * by reading the state again.
 */
data class ShellStep(val emitter: Emitter, val commands: List<String>, val confirmed: Boolean = false)

/** Silence and Restore through the shell (uid 2000, as `adb shell`). Plain Kotlin; none of these take arguments. */
object ShellPlan {
    /** Silence order: the hotspot before airplane mode, then each radio and its scanning. */
    private val SILENCE_ORDER = listOf(
        Emitter.HOTSPOT, Emitter.CELLULAR, Emitter.WIFI, Emitter.WIFI_SCANNING, Emitter.BLUETOOTH,
        Emitter.BLE_SCANNING, Emitter.NFC, Emitter.UWB,
    )

    /** Restore order: airplane mode off first, so the radios that follow are not switched off again by it. */
    private val RESTORE_ORDER = listOf(
        Emitter.CELLULAR, Emitter.WIFI, Emitter.WIFI_SCANNING, Emitter.BLUETOOTH, Emitter.BLE_SCANNING, Emitter.NFC, Emitter.UWB,
    )

    fun off(e: Emitter): ShellStep = when (e) {
        Emitter.CELLULAR -> ShellStep(e, listOf("cmd connectivity airplane-mode enable"))
        Emitter.WIFI -> ShellStep(e, listOf("cmd wifi set-wifi-enabled disabled", "svc wifi disable"))
        // Since Android 11 the Wi-Fi stack keeps this setting itself; the Settings key is the older way, kept as a fallback.
        Emitter.WIFI_SCANNING -> ShellStep(e, listOf("cmd wifi set-scan-always-available disabled", "settings put global wifi_scan_always_enabled 0"))
        Emitter.BLUETOOTH -> ShellStep(e, listOf("svc bluetooth disable", "cmd bluetooth_manager disable"))
        Emitter.BLE_SCANNING -> ShellStep(e, listOf("settings put global ble_scan_always_enabled 0"))
        Emitter.NFC -> ShellStep(e, listOf("svc nfc disable"))
        Emitter.UWB -> ShellStep(e, listOf("cmd uwb disable-uwb"))
        // Wi-Fi hotspot only; USB and Bluetooth tethering are switched off in Settings.
        Emitter.HOTSPOT -> ShellStep(e, listOf("cmd wifi stop-softap"))
    }

    /** The command that turns [e] back on, or null for the hotspot (it needs its name and password). */
    fun on(e: Emitter): ShellStep? = when (e) {
        Emitter.CELLULAR -> ShellStep(e, listOf("cmd connectivity airplane-mode disable"))
        Emitter.WIFI -> ShellStep(e, listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable"))
        Emitter.WIFI_SCANNING -> ShellStep(e, listOf("cmd wifi set-scan-always-available enabled", "settings put global wifi_scan_always_enabled 1"))
        Emitter.BLUETOOTH -> ShellStep(e, listOf("svc bluetooth enable", "cmd bluetooth_manager enable"))
        Emitter.BLE_SCANNING -> ShellStep(e, listOf("settings put global ble_scan_always_enabled 1"))
        Emitter.NFC -> ShellStep(e, listOf("svc nfc enable"))
        Emitter.UWB -> ShellStep(e, listOf("cmd uwb enable-uwb"))
        Emitter.HOTSPOT -> null
    }

    /**
     * The step that silences [e] given [r], or null when there is nothing to do: off or absent. An unknown state on
     * a present transmitter is silenced too (turning off what is already off does nothing), except the hotspot and
     * USB or Bluetooth tethering, which the shell cannot stop.
     */
    fun silenceStep(e: Emitter, r: EmissionsReading): ShellStep? {
        val state = r.state(e)
        if (state == EmitterState.OFF || state == EmitterState.ABSENT) return null
        if (e == Emitter.HOTSPOT && state == EmitterState.ON && r.hotspotKinds.isNotEmpty() && "wifi" !in r.hotspotKinds) return null
        return off(e)
    }

    fun silence(r: EmissionsReading): List<ShellStep> = SILENCE_ORDER.mapNotNull { silenceStep(it, r) }

    /** Steps that turn [remembered] back on; those already on are skipped. */
    fun restore(remembered: Set<Emitter>, r: EmissionsReading): List<ShellStep> =
        RESTORE_ORDER.filter { it in remembered && r.state(it) != EmitterState.ON && r.state(it) != EmitterState.ABSENT }.mapNotNull(::on)

    /** Every command these plans can run, for the shell-safety test. */
    val allCommands: List<String>
        get() = Emitter.entries.flatMap { off(it).commands + on(it)?.commands.orEmpty() } + ShellReads.ALL
}

/** Tells whether a command's output reads as a failure. Tolerant: the formats are not a stable API. */
object ShellOutput {
    private val failure = Regex(
        """^(Error|Exception|java\.|Security|Unknown|Bad |Failure|Failed|usage:|Usage:|Can't find service|No shell command|""" +
            """Permission [Dd]enial|\[exit |\[timed out]|\[error]|/system/bin/sh: )""",
    )

    fun failed(output: String): Boolean = output.lineSequence().map { it.trim() }.any { failure.containsMatchIn(it) }

    /** The first failure line, shortened for one line of UI. */
    fun firstFailure(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { failure.containsMatchIn(it) }?.let { if (it.length > 120) it.take(119) + "…" else it }
}

/**
 * Outcome of a Silence or Restore, judged by reading the states again afterwards: [done] changed as asked, [missed]
 * did not ([missedLabel] says how), [errors] holds the first failure line per transmitter.
 */
data class SilenceOutcome(
    val done: List<Emitter>,
    val missed: List<Emitter>,
    val missedLabel: String,
    val errors: Map<Emitter, String>,
) {
    val summary: String
        get() = buildString {
            append(if (missed.isEmpty()) "Done: " else "Partly done: ")
            append(if (done.isEmpty()) "nothing changed" else done.joinToString(", ") { it.title })
            if (missed.isNotEmpty()) append(". ").append(missedLabel).append(": ").append(missed.joinToString(", ") { it.title })
            val firstError = missed.firstNotNullOfOrNull { errors[it] }
            if (firstError != null) append(" (").append(firstError).append(")")
            append('.')
        }

    companion object {
        /** Compares the states before and after a Silence: which went off, which are still on. */
        fun silence(before: EmissionsReading, after: EmissionsReading, errors: Map<Emitter, String>) = SilenceOutcome(
            done = before.on.filter { after.state(it) == EmitterState.OFF },
            missed = after.on,
            missedLabel = "Still on",
            errors = errors,
        )

        /** After a Restore: which remembered transmitters are on again, which stayed off. */
        fun restore(remembered: Set<Emitter>, after: EmissionsReading, errors: Map<Emitter, String>) = SilenceOutcome(
            done = remembered.filter { after.state(it) == EmitterState.ON },
            missed = remembered.filter { after.state(it) == EmitterState.OFF },
            missedLabel = "Still off",
            errors = errors,
        )
    }
}
