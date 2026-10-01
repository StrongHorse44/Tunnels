package io.github.stronghorse44.tunnels.ble

/**
 * The accessory non-owner service of "Detecting Unwanted Location Trackers" (IETF
 * draft-detecting-unwanted-location-trackers, continued as draft-ietf-dult-accessory-protocol).
 * Any phone may connect to a tag in separated mode without authentication, read what it is and make it
 * ring. Values below were checked against the draft text and two independent implementations (nRF
 * Connect SDK `subsys/dult`, AirGuard); where the draft leaves something open it is said so.
 *
 * Frames are little-endian: a two-byte opcode followed by operands. Responses arrive as GATT
 * indications on the same characteristic.
 */
object DultProtocol {
    /** Accessory non-owner service, a primary service over GATT/LE. */
    const val SERVICE_UUID = "15190001-12f4-c226-88ed-2ac5579f2a85"
    /** The single characteristic: write commands to it, receive indications from it. */
    const val CHARACTERISTIC_UUID = "8e0c0001-1d68-fb92-bf61-48377421680e"
    /** Client Characteristic Configuration descriptor (Bluetooth SIG), enables indications. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    // Accessory information opcodes and their responses (response = opcode | 0x0800).
    const val GET_PRODUCT_DATA = 0x0003
    const val GET_PRODUCT_DATA_RESPONSE = 0x0803
    const val GET_MANUFACTURER_NAME = 0x0004
    const val GET_MANUFACTURER_NAME_RESPONSE = 0x0804
    const val GET_MODEL_NAME = 0x0005
    const val GET_MODEL_NAME_RESPONSE = 0x0805
    const val GET_ACCESSORY_CATEGORY = 0x0006
    const val GET_ACCESSORY_CATEGORY_RESPONSE = 0x0806
    const val GET_PROTOCOL_IMPLEMENTATION_VERSION = 0x0007
    const val GET_PROTOCOL_IMPLEMENTATION_VERSION_RESPONSE = 0x0807
    const val GET_ACCESSORY_CAPABILITIES = 0x0008
    const val GET_ACCESSORY_CAPABILITIES_RESPONSE = 0x0808
    const val GET_NETWORK_ID = 0x0009
    const val GET_NETWORK_ID_RESPONSE = 0x0809
    const val GET_FIRMWARE_VERSION = 0x000A
    const val GET_FIRMWARE_VERSION_RESPONSE = 0x080A
    const val GET_BATTERY_TYPE = 0x000B
    const val GET_BATTERY_TYPE_RESPONSE = 0x080B
    const val GET_BATTERY_LEVEL = 0x000C
    const val GET_BATTERY_LEVEL_RESPONSE = 0x080C

    // Non-owner controls.
    const val SOUND_START = 0x0300
    const val SOUND_STOP = 0x0301
    const val COMMAND_RESPONSE = 0x0302
    const val SOUND_COMPLETED = 0x0303
    const val GET_IDENTIFIER = 0x0404
    const val GET_IDENTIFIER_RESPONSE = 0x0405

    // Command_Response status codes.
    const val STATUS_SUCCESS = 0x0000
    const val STATUS_INVALID_STATE = 0x0001
    const val STATUS_INVALID_CONFIGURATION = 0x0002
    const val STATUS_INVALID_LENGTH = 0x0003
    const val STATUS_INVALID_PARAM = 0x0004
    const val STATUS_INVALID_COMMAND = 0xFFFF

    // Accessory capability bits (Uint32).
    const val CAP_PLAY_SOUND = 1 shl 0
    const val CAP_MOTION_DETECTOR_UT = 1 shl 1
    const val CAP_IDENTIFIER_LOOKUP_NFC = 1 shl 2
    const val CAP_IDENTIFIER_LOOKUP_BLE = 1 shl 3

    const val PRODUCT_DATA_LENGTH = 8
    const val NAME_MAX_LENGTH = 64
    /** Once the user presses the tag's button (or whatever the maker requires) Get_Identifier is answered for this long. */
    const val IDENTIFIER_READ_WINDOW_MINUTES = 5

    /** The two-byte little-endian command frame for an opcode without operands. */
    fun command(opcode: Int): ByteArray = byteArrayOf((opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte())

    /** The opcode at the front of a frame, or null for a frame shorter than two bytes. */
    fun opcodeOf(frame: ByteArray): Int? = if (frame.size < 2) null else (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)

    /** Everything the accessory may send back. [Unknown] keeps an unfamiliar opcode without failing. */
    sealed interface Response {
        data class ProductData(val bytes: ByteArray) : Response {
            val hex: String get() = bytes.joinToString("") { "%02x".format(it) }
            override fun equals(other: Any?) = other is ProductData && other.bytes.contentEquals(bytes)
            override fun hashCode() = bytes.contentHashCode()
        }
        data class ManufacturerName(val name: String) : Response
        data class ModelName(val name: String) : Response
        data class AccessoryCategory(val value: Int) : Response {
            val name: String get() = categoryName(value)
        }
        data class ProtocolVersion(val major: Int, val minor: Int, val revision: Int) : Response {
            override fun toString() = "$major.$minor.$revision"
        }
        data class Capabilities(val bits: Int) : Response {
            val playSound: Boolean get() = bits and CAP_PLAY_SOUND != 0
            val motionDetector: Boolean get() = bits and CAP_MOTION_DETECTOR_UT != 0
            val identifierByNfc: Boolean get() = bits and CAP_IDENTIFIER_LOOKUP_NFC != 0
            val identifierByBle: Boolean get() = bits and CAP_IDENTIFIER_LOOKUP_BLE != 0
            val labels: List<String> get() = listOfNotNull(
                "play sound".takeIf { playSound },
                "motion-triggered sound".takeIf { motionDetector },
                "serial over NFC".takeIf { identifierByNfc },
                "serial over Bluetooth".takeIf { identifierByBle },
            )
        }
        /** The draft defers network ids to a registry it does not (yet) contain, so the value is shown as a number. */
        data class NetworkId(val value: Int) : Response
        data class FirmwareVersion(val major: Int, val minor: Int, val revision: Int) : Response {
            override fun toString() = "$major.$minor.$revision"
        }
        data class BatteryType(val value: Int) : Response {
            val name: String get() = batteryTypeName(value)
        }
        data class BatteryLevel(val value: Int) : Response {
            val name: String get() = batteryLevelName(value)
        }
        /** The reply to a control command: which command, and how it went. */
        data class CommandResponse(val commandOpcode: Int, val status: Int) : Response {
            val ok: Boolean get() = status == STATUS_SUCCESS
            val statusName: String get() = DultProtocol.statusName(status)
        }
        data object SoundCompleted : Response
        /** The encrypted, non-identifying identifier blob; only its length is kept. */
        data class Identifier(val length: Int) : Response
        data class Unknown(val opcode: Int, val length: Int) : Response
    }

    /** Decodes one indication; null for a frame too short to carry an opcode. */
    fun parse(frame: ByteArray): Response? {
        val opcode = opcodeOf(frame) ?: return null
        val body = frame.copyOfRange(2, frame.size)
        return when (opcode) {
            GET_PRODUCT_DATA_RESPONSE -> Response.ProductData(body.copyOf(minOf(body.size, PRODUCT_DATA_LENGTH)))
            GET_MANUFACTURER_NAME_RESPONSE -> Response.ManufacturerName(utf8(body))
            GET_MODEL_NAME_RESPONSE -> Response.ModelName(utf8(body))
            GET_ACCESSORY_CATEGORY_RESPONSE -> Response.AccessoryCategory(body.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
            GET_PROTOCOL_IMPLEMENTATION_VERSION_RESPONSE -> version(body)?.let { (ma, mi, re) -> Response.ProtocolVersion(ma, mi, re) } ?: Response.Unknown(opcode, body.size)
            GET_ACCESSORY_CAPABILITIES_RESPONSE -> Response.Capabilities(uint32(body) ?: 0)
            GET_NETWORK_ID_RESPONSE -> Response.NetworkId(body.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
            GET_FIRMWARE_VERSION_RESPONSE -> version(body)?.let { (ma, mi, re) -> Response.FirmwareVersion(ma, mi, re) } ?: Response.Unknown(opcode, body.size)
            GET_BATTERY_TYPE_RESPONSE -> Response.BatteryType(body.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
            GET_BATTERY_LEVEL_RESPONSE -> Response.BatteryLevel(body.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
            COMMAND_RESPONSE -> {
                val cmd = uint16(body, 0) ?: return Response.Unknown(opcode, body.size)
                val status = uint16(body, 2) ?: return Response.Unknown(opcode, body.size)
                Response.CommandResponse(cmd, status)
            }
            SOUND_COMPLETED -> Response.SoundCompleted
            GET_IDENTIFIER_RESPONSE -> Response.Identifier(body.size)
            else -> Response.Unknown(opcode, body.size)
        }
    }

    /** Names are UTF-8 up to 64 bytes, either exact-length or zero-terminated and zero-padded. */
    private fun utf8(body: ByteArray): String {
        val end = body.indexOf(0).let { if (it < 0) body.size else it }.coerceAtMost(NAME_MAX_LENGTH)
        return String(body, 0, end, Charsets.UTF_8).trim()
    }

    /** Uint32: byte 0 revision, byte 1 minor, bytes 2..3 major (1.0.0 = 0x00010000). */
    private fun version(body: ByteArray): Triple<Int, Int, Int>? {
        val v = uint32(body) ?: return null
        return Triple((v shr 16) and 0xFFFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    private fun uint16(body: ByteArray, at: Int): Int? =
        if (body.size < at + 2) null else (body[at].toInt() and 0xFF) or ((body[at + 1].toInt() and 0xFF) shl 8)

    private fun uint32(body: ByteArray): Int? {
        if (body.size < 4) return null
        return (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8) or ((body[2].toInt() and 0xFF) shl 16) or ((body[3].toInt() and 0xFF) shl 24)
    }

    fun statusName(status: Int): String = when (status) {
        STATUS_SUCCESS -> "success"
        STATUS_INVALID_STATE -> "invalid state"
        STATUS_INVALID_CONFIGURATION -> "invalid configuration"
        STATUS_INVALID_LENGTH -> "invalid length"
        STATUS_INVALID_PARAM -> "invalid parameter"
        STATUS_INVALID_COMMAND -> "invalid command"
        else -> "status 0x%04x".format(status)
    }

    /**
     * Why a control command was refused, in the user's terms. The draft makes Sound_Start/Stop and
     * Get_Identifier answer Invalid_command outside the separated state, respectively the identifier read state.
     */
    fun explainRefusal(commandOpcode: Int, status: Int): String = when (commandOpcode) {
        GET_IDENTIFIER -> when (status) {
            STATUS_INVALID_COMMAND, STATUS_INVALID_STATE ->
                "The tag is not in identifier-read mode. It only hands out its identifier for $IDENTIFIER_READ_WINDOW_MINUTES minutes after a " +
                    "user action on the tag itself (for example holding its button for about 10 seconds), and only while it has been away from its " +
                    "owner for a while. Press the tag's button, then try again. An AirTag has no button: tap it with NFC instead."
            else -> "The tag refused the identifier request (${statusName(status)})."
        }
        SOUND_START, SOUND_STOP -> when (status) {
            STATUS_INVALID_COMMAND -> "The tag will not ring for a non-owner right now: a tag only accepts that while it has been away from its owner for a while (separated mode). Try again later, or move away from where its owner may be."
            STATUS_INVALID_STATE -> if (commandOpcode == SOUND_START) "The tag is already ringing." else "No sound of ours is playing on the tag."
            else -> "The tag refused (${statusName(status)})."
        }
        else -> "The tag refused (${statusName(status)})."
    }

    fun batteryTypeName(value: Int): String = when (value) {
        0x00 -> "powered"
        0x01 -> "non-rechargeable battery"
        0x02 -> "rechargeable battery"
        else -> "battery type $value"
    }

    fun batteryLevelName(value: Int): String = when (value) {
        0x00 -> "full"
        0x01 -> "medium"
        0x02 -> "low"
        0x03 -> "critically low"
        else -> "level $value"
    }

    /** Accessory category table of the draft. */
    fun categoryName(value: Int): String = when (value) {
        1 -> "location tracker"
        128 -> "other"
        129 -> "luggage"
        130 -> "backpack"
        131 -> "jacket"
        132 -> "coat"
        133 -> "shoes"
        134 -> "bike"
        135 -> "scooter"
        136 -> "stroller"
        137 -> "wheelchair"
        138 -> "boat"
        139 -> "helmet"
        140 -> "skateboard"
        141 -> "skis"
        142 -> "snowboard"
        143 -> "surfboard"
        144 -> "camera"
        145 -> "laptop"
        146 -> "watch"
        147 -> "flash drive"
        148 -> "drone"
        149 -> "headphones"
        150 -> "earphones"
        151 -> "inhaler"
        152 -> "sunglasses"
        153 -> "handbag"
        154 -> "wallet"
        155 -> "umbrella"
        156 -> "water bottle"
        157 -> "tools or tool box"
        158 -> "keys"
        159 -> "smart case"
        160 -> "remote"
        161 -> "hat"
        162 -> "motorbike"
        163 -> "consumer electronic device"
        164 -> "apparel"
        165 -> "transportation device"
        166 -> "sports equipment"
        167 -> "personal item"
        else -> "category $value"
    }

    /** What one query learnt, folded into the single line the events table keeps. */
    data class Summary(
        val manufacturer: String? = null,
        val model: String? = null,
        val category: String? = null,
        val batteryLevel: String? = null,
        val batteryType: String? = null,
        val capabilities: List<String> = emptyList(),
        val firmware: String? = null,
        val soundPlayed: Boolean = false,
        val identifierRead: Boolean = false,
    ) {
        fun with(r: Response): Summary = when (r) {
            is Response.ManufacturerName -> copy(manufacturer = r.name.ifBlank { null })
            is Response.ModelName -> copy(model = r.name.ifBlank { null })
            is Response.AccessoryCategory -> copy(category = r.name)
            is Response.BatteryLevel -> copy(batteryLevel = r.name)
            is Response.BatteryType -> copy(batteryType = r.name)
            is Response.Capabilities -> copy(capabilities = r.labels)
            is Response.FirmwareVersion -> copy(firmware = r.toString())
            is Response.Identifier -> copy(identifierRead = true)
            else -> this
        }

        /** "DULT: Apple AirTag, battery full, sound played". */
        fun line(): String {
            val name = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { category ?: "unknown tag" }
            val parts = mutableListOf("DULT: $name")
            if (category != null && (manufacturer != null || model != null)) parts += category
            batteryLevel?.let { parts += "battery $it" }
            if (soundPlayed) parts += "sound played"
            if (identifierRead) parts += "identifier read"
            return parts.joinToString(", ")
        }
    }
}
