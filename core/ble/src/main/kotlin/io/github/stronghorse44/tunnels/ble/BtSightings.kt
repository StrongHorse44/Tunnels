package io.github.stronghorse44.tunnels.ble

/** A Bluetooth address's type as Android reports it (`BluetoothDevice.getAddressType`, Android 15 and later). */
enum class BtAddressType { PUBLIC, RANDOM, UNKNOWN }

/**
 * Which Bluetooth addresses stay the same over time (Bluetooth Core, Vol 6, Part B, 1.3). A public address is the
 * device's own; a random one is static when its two top bits are 11, and changes every few minutes otherwise (a
 * resolvable private address, 01, as phones, earbuds and watches use; a non-resolvable one, 00). A changing address
 * can never be followed across scans, so only fixed ones are kept over time (RJ, 2026-10-11).
 */
object BtAddress {
    private val SHAPE = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

    /**
     * True for an address that stays the same. Without a type (Android 14) the top bits decide: 01 is taken as a
     * changing private address and anything else as fixed, so a public address that happens to start with 01 is
     * missed and a non-resolvable one is logged (it then never repeats).
     */
    fun isFixed(address: String, type: BtAddressType): Boolean {
        if (!SHAPE.matches(address)) return false
        val top = address.substring(0, 2).toInt(16) ushr 6
        return when (type) {
            BtAddressType.PUBLIC -> true
            BtAddressType.RANDOM -> top == 0b11
            BtAddressType.UNKNOWN -> top != 0b01
        }
    }
}

/** One fixed-address Bluetooth device in one scan: its keyed id, the name it advertised ("" for none) and its hint id. */
data class BtSeen(val key: String, val name: String, val hint: String? = null)

/**
 * One Bluetooth scan that listened to every advertiser: the unit the Bluetooth stream ([SurroundingsKeys.BT_LEDGER])
 * keeps for 30 days. Devices appear by [BtSeen.key], the first 16 hex of an HMAC of the address under the Surroundings
 * keystore key; the address itself is never stored. The advertised name and hint id are kept with it (RJ's third
 * exception to rule 3, 2026-10-11: see CLAUDE.md). Trackers are not in it (they have their own sightings) and neither
 * are changing addresses.
 */
data class BtScanRecord(val session: String, val at: Long, val place: Long?, val devices: List<BtSeen>) {
    /** `at=…;place=…;devs=<key>~<name>[~<hint>],…`, escaped as Wi-Fi rows are ([WifiScanRecord.escape]). */
    fun encode(): String = buildString {
        append("at=").append(at)
        append(";place=").append(place?.toString().orEmpty())
        append(";devs=").append(devices.joinToString(",") { WifiScanRecord.entry(it.key, it.name, it.hint) })
    }

    companion object {
        /** Reads a row back; null for one this version does not understand. Malformed entries are dropped. */
        fun parse(session: String, summary: String): BtScanRecord? {
            if (session.isEmpty()) return null
            val fields = summary.split(';').mapNotNull { f ->
                val i = f.indexOf('=')
                if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
            }.toMap()
            val at = fields["at"]?.toLongOrNull() ?: return null
            val place = fields["place"]?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            val devices = WifiScanRecord.entries(fields["devs"]).map { (key, name, hint) -> BtSeen(key, name, hint) }
            return BtScanRecord(session, at, place, devices)
        }
    }
}

/** Everything known about one fixed-address Bluetooth device across the scans of the last 30 days. */
data class BtDeviceSighting(
    override val key: String,
    override val name: String,
    override val names: Int,
    override val firstSeen: Long,
    override val lastSeen: Long,
    override val sessions: Int,
    override val places: Int,
    override val moves: Int,
    override val lastSession: String,
    override val hint: String? = null,
) : OverTimeItem

/**
 * Bluetooth devices over time, the way Wi-Fi access points are ([WifiFollowing]): same threshold (the user's one
 * setting), same levels. Only scans that listened to every advertiser log devices: the manual scan and the monitor's
 * passes while the screen is on, because Android pauses an unfiltered scan while the screen is off (RJ, 2026-10-11).
 */
object BtFollowing {
    /** At most this many devices per scan row, strongest first; a crowd of fixed addresses past it is not followed. */
    const val MAX_DEVICES_PER_SCAN = 100

    /** The monitor logs a screen-on pass at most this often, like its Wi-Fi scans, so the threshold means the same. */
    const val MONITOR_INTERVAL_MS = 10 * 60_000L

    internal fun scanOf(r: BtScanRecord): OverTime.Scan = OverTime.Scan(r.session, r.at, r.place, r.devices.map { OverTime.Heard(it.key, it.name, it.hint) })

    /**
     * Folds the scans into one entry per device, most recently seen first. A move counts when the Bluetooth devices
     * and the Wi-Fi access points heard at the two places, taken together, mostly changed ([OverTime.realMoves]).
     */
    fun aggregate(records: Collection<BtScanRecord>, wifi: Collection<WifiScanRecord> = emptyList()): List<BtDeviceSighting> {
        val scans = records.map(::scanOf)
        val byPlace = HashMap<Long, MutableSet<String>>()
        for (source in listOf(OverTime.placeKeys(scans), OverTime.placeKeys(wifi.map(WifiFollowing::scanOf)))) {
            for ((p, keys) in source) byPlace.getOrPut(p) { HashSet() } += keys
        }
        return OverTime.fold(scans, OverTime.realMoves(byPlace)).map { f ->
            BtDeviceSighting(f.key, f.name, f.names, f.firstSeen, f.lastSeen, f.sessions, f.places, f.moves, f.lastSession, f.hint)
        }
    }

    /** "unnamed a1b2c3" for a device that advertised no name. */
    fun displayName(name: String, subjectOrKey: String): String =
        name.ifBlank { "unnamed ${subjectOrKey.substringAfterLast(':').take(6)}" }
}
