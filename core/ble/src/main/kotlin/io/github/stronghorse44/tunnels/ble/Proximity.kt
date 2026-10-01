package io.github.stronghorse44.tunnels.ble

import kotlin.math.abs

/**
 * A BLE signal in words. Thresholds are coarse on purpose: RSSI depends on the tag's antenna, the phone,
 * pockets and bodies. Trackers transmit at low power, so -60 dBm already means an arm's length away.
 */
enum class Proximity(val label: String, val hint: String) {
    NEAR("near", "within about a metre"),
    MEDIUM("medium", "in the same room or bag"),
    FAR("far", "several metres away or behind walls"),
    ;

    companion object {
        /** At or above this the tag is within reach. */
        const val NEAR_DBM = -60
        /** Below this the tag is across the room or further. */
        const val FAR_DBM = -80

        fun of(rssi: Int): Proximity = when {
            rssi >= NEAR_DBM -> NEAR
            rssi >= FAR_DBM -> MEDIUM
            else -> FAR
        }
    }
}

/** Maps RSSI onto a 0..1 meter and the haptic cadence the find-it screen uses. */
object RssiMeter {
    /** The meter's full-scale ends: anything at or above [BEST_DBM] is "right here", at or below [WORST_DBM] "lost". */
    const val BEST_DBM = -40
    const val WORST_DBM = -100
    const val FASTEST_PULSE_MS = 120L
    const val SLOWEST_PULSE_MS = 1_800L

    /** 0.0 at [WORST_DBM] or below, 1.0 at [BEST_DBM] or above, linear in dBm between. */
    fun fraction(rssi: Double): Float = ((rssi - WORST_DBM) / (BEST_DBM - WORST_DBM)).coerceIn(0.0, 1.0).toFloat()

    /** Pause between two haptic pulses: short when the signal is strong, long when it is weak. */
    fun pulseIntervalMs(rssi: Double): Long {
        val f = fraction(rssi)
        return Math.round(SLOWEST_PULSE_MS - (SLOWEST_PULSE_MS - FASTEST_PULSE_MS) * f.toDouble())
    }
}

/**
 * Exponential moving average over RSSI readings. BLE readings jump by 10 dB between consecutive
 * advertisements of a tag that has not moved; the smoothed value is what a person can follow.
 */
class RssiSmoother(private val alpha: Double = DEFAULT_ALPHA) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
    }

    /** Read from the UI thread while scan callbacks write: every access is synchronised. */
    private val lock = Any()
    private var current: Double? = null

    val value: Double? get() = synchronized(lock) { current }

    fun add(rssi: Int): Double = synchronized(lock) {
        val v = current
        val next = if (v == null) rssi.toDouble() else v + alpha * (rssi - v)
        current = next
        next
    }

    fun reset() = synchronized(lock) { current = null }

    companion object {
        const val DEFAULT_ALPHA = 0.3
    }
}

/** Android's ScanCallback.onScanFailed codes in words. The constants are mirrored here so the mapping is testable off-device. */
object ScanFailure {
    const val ALREADY_STARTED = 1
    const val APPLICATION_REGISTRATION_FAILED = 2
    const val INTERNAL_ERROR = 3
    const val FEATURE_UNSUPPORTED = 4
    const val OUT_OF_HARDWARE_RESOURCES = 5
    const val SCANNING_TOO_FREQUENTLY = 6

    fun describe(code: Int): String = when (code) {
        ALREADY_STARTED -> "A scan was already running."
        APPLICATION_REGISTRATION_FAILED -> "Bluetooth refused to register the scan; turning Bluetooth off and on usually clears it."
        INTERNAL_ERROR -> "Bluetooth reported an internal error."
        FEATURE_UNSUPPORTED -> "This phone's Bluetooth does not support the scan settings asked for."
        OUT_OF_HARDWARE_RESOURCES -> "Bluetooth is out of scan slots; close other apps that scan and try again."
        SCANNING_TOO_FREQUENTLY -> "Too many scans in a short time: wait 30 seconds and start again."
        else -> "Bluetooth scan failed (code $code)."
    }
}

/** Which way the smoothed signal has gone since the last comparison. */
enum class Trend(val label: String) {
    WARMER("warmer"),
    COLDER("colder"),
    STEADY("steady"),
    ;

    companion object {
        /** Changes smaller than this many dB are noise, not movement. */
        const val DEADBAND_DB = 2.0

        fun of(previous: Double?, current: Double): Trend {
            if (previous == null) return STEADY
            val d = current - previous
            return when {
                abs(d) < DEADBAND_DB -> STEADY
                d > 0 -> WARMER
                else -> COLDER
            }
        }
    }
}
