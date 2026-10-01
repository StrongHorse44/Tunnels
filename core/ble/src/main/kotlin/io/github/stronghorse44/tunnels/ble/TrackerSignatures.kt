package io.github.stronghorse44.tunnels.ble

/** How sure the catalog is that an advertisement matching the signature really is that tracker. */
enum class Confidence { HIGH, MEDIUM, LOW }

/** The tracker families Tunnels recognises. [slug] is what observation subjects carry. */
enum class TrackerType(val slug: String, val label: String, val network: String) {
    APPLE_FINDMY("findmy", "Apple Find My", "Apple Find My network (AirTag and compatible tags)"),
    SAMSUNG_SMARTTAG("smarttag", "Samsung SmartTag", "Samsung SmartThings Find"),
    TILE("tile", "Tile", "Tile network"),
    CHIPOLO("chipolo", "Chipolo", "Chipolo"),
    PEBBLEBEE("pebblebee", "Pebblebee", "Pebblebee"),
    GOOGLE_FMDN("fmdn", "Google Find My Device", "Google Find My Device network"),
    ;

    companion object {
        fun bySlug(slug: String): TrackerType? = entries.firstOrNull { it.slug == slug }
    }
}

/** Whether the tracker says it is away from its owner. Only Apple's advertisement states this clearly. */
enum class TrackerState(val slug: String) {
    SEPARATED("separated"),
    WITH_OWNER("with-owner"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun bySlug(slug: String?): TrackerState = entries.firstOrNull { it.slug == slug } ?: UNKNOWN
    }
}

/**
 * The parts of a BLE advertisement the signatures look at, copied out of the platform's ScanRecord.
 * [serviceUuids] and [serviceData] keys are full 128-bit UUID strings in lower case, as Android prints
 * them (`0000fd5a-0000-1000-8000-00805f9b34fb`); [manufacturerData] is keyed by company id.
 */
data class Advertisement(
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
) {
    /** True when the record carries the 16-bit service UUID [short16] anywhere (UUID list or service data). */
    fun hasService(short16: Int): Boolean =
        serviceUuids.any { BleUuid.short16(it) == short16 } || serviceData.keys.any { BleUuid.short16(it) == short16 }

    fun serviceDataOf(short16: Int): ByteArray? = serviceData.entries.firstOrNull { BleUuid.short16(it.key) == short16 }?.value
}

/** Bluetooth SIG 16-bit UUID aliases live inside the base UUID `0000xxxx-0000-1000-8000-00805f9b34fb`. */
object BleUuid {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** The 16-bit alias of a full UUID string, or null when the UUID is not a SIG alias. */
    fun short16(uuid: String): Int? {
        val s = uuid.lowercase()
        if (s.length != 36 || !s.endsWith(BASE_SUFFIX) || !s.startsWith("0000")) return null
        return s.substring(4, 8).toIntOrNull(16)
    }

    /** The full UUID string for a 16-bit alias, e.g. `0xFD5A` → `0000fd5a-0000-1000-8000-00805f9b34fb`. */
    fun full(short16: Int): String = "0000%04x%s".format(short16 and 0xFFFF, BASE_SUFFIX)
}

/** One recognised tracker advertisement. [idSource] feeds the pseudonymous device key with the manufacturer or service id. */
data class TrackerMatch(
    val type: TrackerType,
    val state: TrackerState,
    val idSource: String,
    val confidence: Confidence,
    /** Coarse battery level as the tag reports it (Apple only): full, medium, low, critical. */
    val battery: String? = null,
    /** What kind of device the frame says it is (Apple only): airtag, accessory, airpods, apple-device. */
    val kind: String? = null,
)

/**
 * One decoded Apple Offline Finding advertisement (company id 0x004C, type 0x12). Apple has not published
 * the format; this follows the reverse engineering in OpenHaystack and AirGuard (seemoo-lab), which read
 * the status byte the same way in both frame lengths.
 */
data class AppleFindMyFrame(
    /** The payload length byte: 0x19 while separated from the owner, 0x02 while near it. */
    val length: Int,
    /** The status byte that follows the length. */
    val status: Int,
) {
    val state: TrackerState get() = when (length) {
        TrackerSignatures.APPLE_FINDMY_SEPARATED_LENGTH -> TrackerState.SEPARATED
        TrackerSignatures.APPLE_FINDMY_NEARBY_LENGTH -> TrackerState.WITH_OWNER
        else -> TrackerState.UNKNOWN
    }

    /** Bits 7..6: 0 full, 1 medium, 2 low, 3 critical. */
    val battery: String get() = TrackerSignatures.appleBattery(status)

    /** Bits 5..4 (AirGuard's `statusByte and 0x30 shr 4`): 0 an Apple device (iPhone, Mac), 1 AirTag, 2 third-party Find My accessory, 3 AirPods. */
    val kind: String get() = when ((status shr 4) and 0x03) {
        0 -> KIND_APPLE_DEVICE
        1 -> KIND_AIRTAG
        2 -> KIND_ACCESSORY
        else -> KIND_AIRPODS
    }

    companion object {
        const val KIND_APPLE_DEVICE = "apple-device"
        const val KIND_AIRTAG = "airtag"
        const val KIND_ACCESSORY = "accessory"
        const val KIND_AIRPODS = "airpods"

        /** Plain-language label for a stored kind slug; null for an unknown or absent slug. */
        fun kindLabel(kind: String?): String? = when (kind) {
            KIND_APPLE_DEVICE -> "Apple device (iPhone, iPad or Mac)"
            KIND_AIRTAG -> "AirTag"
            KIND_ACCESSORY -> "Find My accessory (third-party tag)"
            KIND_AIRPODS -> "AirPods"
            else -> null
        }

        /**
         * Decodes the manufacturer payload for company 0x004C; null when it is not an Offline Finding frame.
         * The separated frame must carry the bytes its length byte announces (status, 22 key bytes, key bits,
         * hint); the nearby frame is the status byte and one more.
         */
        fun parse(payload: ByteArray): AppleFindMyFrame? {
            if (payload.size < 3 || payload[0].toInt() and 0xFF != TrackerSignatures.APPLE_FINDMY_TYPE) return null
            val length = payload[1].toInt() and 0xFF
            val status = payload[2].toInt() and 0xFF
            return when (length) {
                TrackerSignatures.APPLE_FINDMY_SEPARATED_LENGTH -> if (payload.size >= 2 + length) AppleFindMyFrame(length, status) else null
                TrackerSignatures.APPLE_FINDMY_NEARBY_LENGTH -> AppleFindMyFrame(length, status)
                else -> null
            }
        }
    }
}

/**
 * A platform-neutral description of one hardware scan filter, mirroring what Android's ScanFilter can
 * express: manufacturer data under a mask, a service UUID in the advertised list, service data under a
 * mask. Filters matter beyond efficiency: since Android 8.1 the Bluetooth stack suspends an UNFILTERED
 * scan while the screen is off and only resumes it on screen-on, without reporting a failure, so a
 * background window taken from a pocket sees nothing unless it carries filters. [accepts] reproduces
 * the platform's matching so tests can prove the catalog's filters let every active signature through.
 */
sealed interface ScanFilterSpec {
    fun accepts(ad: Advertisement): Boolean

    /** Manufacturer data for [companyId] whose first bytes, under [mask] (null: exact), equal [data]. */
    class ManufacturerData(val companyId: Int, val data: ByteArray, val mask: ByteArray? = null) : ScanFilterSpec {
        init {
            require(mask == null || mask.size == data.size) { "mask and data lengths differ" }
        }

        override fun accepts(ad: Advertisement): Boolean = matchesPrefix(data, mask, ad.manufacturerData[companyId])
    }

    /** The 16-bit alias [short16] appears in the advertised service UUID list. */
    class ServiceUuid(val short16: Int) : ScanFilterSpec {
        override fun accepts(ad: Advertisement): Boolean = ad.serviceUuids.any { BleUuid.short16(it) == short16 }
    }

    /** Service data under [short16] whose first bytes, under [mask], equal [data]; empty [data] means any payload. */
    class ServiceData(val short16: Int, val data: ByteArray = ByteArray(0), val mask: ByteArray? = null) : ScanFilterSpec {
        init {
            require(mask == null || mask.size == data.size) { "mask and data lengths differ" }
        }

        override fun accepts(ad: Advertisement): Boolean = matchesPrefix(data, mask, ad.serviceDataOf(short16))
    }

    companion object {
        /** Android's ScanFilter.matchesPartialData: the advertised bytes must be at least as long and agree under the mask. */
        fun matchesPrefix(data: ByteArray, mask: ByteArray?, advertised: ByteArray?): Boolean {
            if (advertised == null || advertised.size < data.size) return false
            for (i in data.indices) {
                val m = mask?.get(i)?.toInt() ?: 0xFF
                if ((advertised[i].toInt() and m) != (data[i].toInt() and m)) return false
            }
            return true
        }
    }
}

/** One entry of the tracker catalog: who it is, how it is spotted, how sure we are. */
data class TrackerSignature(
    val type: TrackerType,
    val confidence: Confidence,
    /** Plain-language description of what the signature keys on, shown in the UI and kept honest. */
    val basis: String,
    /** False for an entry kept in the catalog but not matched, pending on-device verification. */
    val active: Boolean = true,
    /** Hardware filters that let every advertisement [match] could accept through; empty only for an inactive entry. */
    val filters: List<ScanFilterSpec> = emptyList(),
    val match: (Advertisement) -> TrackerMatch?,
)

/**
 * The curated tracker catalog. Signatures key on published company ids and Bluetooth SIG member
 * service UUIDs; each carries the confidence of the identification.
 */
object TrackerSignatures {
    const val APPLE_COMPANY_ID = 0x004C
    /** Apple "Offline Finding" advertisement type byte. */
    const val APPLE_FINDMY_TYPE = 0x12
    /** Payload length when the tag is separated from its owner (status + 22 key bytes + 2). */
    const val APPLE_FINDMY_SEPARATED_LENGTH = 0x19
    /** Payload length of the short advertisement a tag sends while near its paired device. */
    const val APPLE_FINDMY_NEARBY_LENGTH = 0x02

    const val SAMSUNG_FIND_SERVICE = 0xFD5A
    const val TILE_SERVICE = 0xFEED
    const val CHIPOLO_SERVICE = 0xFE33
    /** Google Fast Pair service; Find My Device network frames ride on its service data. */
    const val GOOGLE_FAST_PAIR_SERVICE = 0xFE2C
    const val FMDN_FRAME_20_BYTE_EID = 0x40
    const val FMDN_FRAME_32_BYTE_EID = 0x41
    /** Eddystone (0xFEAA) and Google's 0xFEF3 are deliberately not used: neither identifies a tracker. */
    const val EDDYSTONE_SERVICE = 0xFEAA

    val apple = TrackerSignature(
        TrackerType.APPLE_FINDMY,
        Confidence.HIGH,
        "Apple company id 0x004C with an Offline Finding payload (type 0x12). Length 0x19 means separated from its owner; length 0x02 means it is near its owner. The status byte gives battery and device kind.",
        filters = listOf(ScanFilterSpec.ManufacturerData(APPLE_COMPANY_ID, byteArrayOf(APPLE_FINDMY_TYPE.toByte()), byteArrayOf(0xFF.toByte()))),
    ) { ad ->
        val payload = ad.manufacturerData[APPLE_COMPANY_ID] ?: return@TrackerSignature null
        val frame = AppleFindMyFrame.parse(payload) ?: return@TrackerSignature null
        TrackerMatch(TrackerType.APPLE_FINDMY, frame.state, "mfr:004c", Confidence.HIGH, battery = frame.battery, kind = frame.kind)
    }

    val samsung = TrackerSignature(
        TrackerType.SAMSUNG_SMARTTAG,
        Confidence.MEDIUM,
        "Samsung SmartThings Find service 0xFD5A. Other Samsung devices on that network can advertise it too.",
        filters = serviceFilters(SAMSUNG_FIND_SERVICE),
    ) { ad -> if (ad.hasService(SAMSUNG_FIND_SERVICE)) TrackerMatch(TrackerType.SAMSUNG_SMARTTAG, TrackerState.UNKNOWN, "svc:fd5a", Confidence.MEDIUM) else null }

    val tile = TrackerSignature(TrackerType.TILE, Confidence.HIGH, "Tile service 0xFEED.", filters = serviceFilters(TILE_SERVICE)) { ad ->
        if (ad.hasService(TILE_SERVICE)) TrackerMatch(TrackerType.TILE, TrackerState.UNKNOWN, "svc:feed", Confidence.HIGH) else null
    }

    val chipolo = TrackerSignature(TrackerType.CHIPOLO, Confidence.HIGH, "Chipolo service 0xFE33.", filters = serviceFilters(CHIPOLO_SERVICE)) { ad ->
        if (ad.hasService(CHIPOLO_SERVICE)) TrackerMatch(TrackerType.CHIPOLO, TrackerState.UNKNOWN, "svc:fe33", Confidence.HIGH) else null
    }

    /** Kept in the catalog without a matcher: no Pebblebee advertisement format is known well enough to key on. */
    val pebblebee = TrackerSignature(
        TrackerType.PEBBLEBEE,
        Confidence.LOW,
        "No verified signature yet. Current Pebblebee tags join Apple's or Google's network and are spotted as those.",
        active = false,
    ) { null }

    val google = TrackerSignature(
        TrackerType.GOOGLE_FMDN,
        Confidence.MEDIUM,
        "Fast Pair service 0xFE2C with a Find My Device network frame (type 0x40 or 0x41 followed by an ephemeral id).",
        // 0x40 and 0x41 differ only in bit 0, so one masked filter covers both frame types.
        filters = listOf(ScanFilterSpec.ServiceData(GOOGLE_FAST_PAIR_SERVICE, byteArrayOf(FMDN_FRAME_20_BYTE_EID.toByte()), byteArrayOf(0xFE.toByte()))),
    ) { ad ->
        val data = ad.serviceDataOf(GOOGLE_FAST_PAIR_SERVICE) ?: return@TrackerSignature null
        val frame = data.firstOrNull()?.toInt()?.and(0xFF) ?: return@TrackerSignature null
        val ok = (frame == FMDN_FRAME_20_BYTE_EID && data.size >= 21) || (frame == FMDN_FRAME_32_BYTE_EID && data.size >= 33)
        if (ok) TrackerMatch(TrackerType.GOOGLE_FMDN, TrackerState.UNKNOWN, "svc:fe2c", Confidence.MEDIUM) else null
    }

    val all: List<TrackerSignature> = listOf(apple, samsung, tile, chipolo, pebblebee, google)

    fun of(type: TrackerType): TrackerSignature = all.first { it.type == type }

    /**
     * The hardware filters a scan needs so that every active signature can still be matched, including
     * while the screen is off. The platform ORs them; ordinary devices do not pass, so a filtered window
     * counts trackers only, not every device around.
     */
    val scanFilters: List<ScanFilterSpec> = all.filter { it.active }.flatMap { it.filters }

    /** A service may sit in the UUID list, in service data, or both; the platform filters each separately. */
    private fun serviceFilters(short16: Int): List<ScanFilterSpec> =
        listOf(ScanFilterSpec.ServiceUuid(short16), ScanFilterSpec.ServiceData(short16))

    /** The first active signature that recognises [ad], or null for an ordinary device. */
    fun match(ad: Advertisement): TrackerMatch? {
        for (sig in all) {
            if (!sig.active) continue
            val m = try { sig.match(ad) } catch (_: Exception) { null }
            if (m != null) return m
        }
        return null
    }

    /** Apple's status byte carries the battery level in its top two bits. */
    fun appleBattery(status: Int): String = when ((status shr 6) and 0x03) {
        0 -> "full"
        1 -> "medium"
        2 -> "low"
        else -> "critical"
    }
}
