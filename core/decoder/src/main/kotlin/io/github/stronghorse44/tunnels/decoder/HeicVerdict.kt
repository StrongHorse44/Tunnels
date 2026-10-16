package io.github.stronghorse44.tunnels.decoder

/**
 * Remembers, in memory for the app process only, that this phone's sealed process cannot read HEIC (audit M6 review).
 * It only ever refuses: a probe that answered anything but OK is kept, so a phone where HEIC crashes the sealed process
 * gets one crash per app start, not one per Device checks visit or HEIC file. Restarting Tunnels probes again. A probe
 * that was cancelled, or never ran because the reader was busy or didn't start, says nothing and is not kept.
 * Thread-safe.
 */
class HeicVerdict {
    @Volatile
    private var refused: DecodeStatus? = null

    /** How the probe that refused HEIC ended, or null when HEIC has not been refused in this process. */
    val refusal: DecodeStatus? get() = refused

    fun record(probe: DecodeStatus) {
        if (probe == DecodeStatus.OK || probe == DecodeStatus.CANCELLED || probe == DecodeStatus.BUSY || probe == DecodeStatus.UNAVAILABLE) return
        if (refused == null) refused = probe
    }
}
