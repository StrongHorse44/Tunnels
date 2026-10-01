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
)

/** One entry of the tracker catalog: who it is, how it is spotted, how sure we are. */
data class TrackerSignature(
    val type: TrackerType,
    val confidence: Confidence,
    /** Plain-language description of what the signature keys on, shown in the UI and kept honest. */
    val basis: String,
    /** False for an entry kept in the catalog but not matched, pending on-device verification. */
    val active: Boolean = true,
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
        "Apple company id 0x004C with an Offline Finding payload (type 0x12). Length 0x19 means separated from its owner; length 0x02 means it is near its owner.",
    ) { ad ->
        val payload = ad.manufacturerData[APPLE_COMPANY_ID] ?: return@TrackerSignature null
        if (payload.size < 2 || payload[0].toInt() and 0xFF != APPLE_FINDMY_TYPE) return@TrackerSignature null
        when (payload[1].toInt() and 0xFF) {
            APPLE_FINDMY_SEPARATED_LENGTH -> {
                if (payload.size < 3) return@TrackerSignature null
                val status = payload[2].toInt() and 0xFF
                TrackerMatch(TrackerType.APPLE_FINDMY, TrackerState.SEPARATED, "mfr:004c", Confidence.HIGH, battery = appleBattery(status))
            }
            APPLE_FINDMY_NEARBY_LENGTH -> TrackerMatch(TrackerType.APPLE_FINDMY, TrackerState.WITH_OWNER, "mfr:004c", Confidence.HIGH)
            else -> null
        }
    }

    val samsung = TrackerSignature(
        TrackerType.SAMSUNG_SMARTTAG,
        Confidence.MEDIUM,
        "Samsung SmartThings Find service 0xFD5A. Other Samsung devices on that network can advertise it too.",
    ) { ad -> if (ad.hasService(SAMSUNG_FIND_SERVICE)) TrackerMatch(TrackerType.SAMSUNG_SMARTTAG, TrackerState.UNKNOWN, "svc:fd5a", Confidence.MEDIUM) else null }

    val tile = TrackerSignature(TrackerType.TILE, Confidence.HIGH, "Tile service 0xFEED.") { ad ->
        if (ad.hasService(TILE_SERVICE)) TrackerMatch(TrackerType.TILE, TrackerState.UNKNOWN, "svc:feed", Confidence.HIGH) else null
    }

    val chipolo = TrackerSignature(TrackerType.CHIPOLO, Confidence.HIGH, "Chipolo service 0xFE33.") { ad ->
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
    ) { ad ->
        val data = ad.serviceDataOf(GOOGLE_FAST_PAIR_SERVICE) ?: return@TrackerSignature null
        val frame = data.firstOrNull()?.toInt()?.and(0xFF) ?: return@TrackerSignature null
        val ok = (frame == FMDN_FRAME_20_BYTE_EID && data.size >= 21) || (frame == FMDN_FRAME_32_BYTE_EID && data.size >= 33)
        if (ok) TrackerMatch(TrackerType.GOOGLE_FMDN, TrackerState.UNKNOWN, "svc:fe2c", Confidence.MEDIUM) else null
    }

    val all: List<TrackerSignature> = listOf(apple, samsung, tile, chipolo, pebblebee, google)

    fun of(type: TrackerType): TrackerSignature = all.first { it.type == type }

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
