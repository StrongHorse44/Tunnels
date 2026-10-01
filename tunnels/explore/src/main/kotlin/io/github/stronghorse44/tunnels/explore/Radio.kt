package io.github.stronghorse44.tunnels.explore

/** Radio tunnel: subjects, keys and the pure name tables for the platform's integer codes. Explore line: no rules. */
object Radio {
    const val TUNNEL_ID = "radio"

    const val CELLULAR = "cellular"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val NFC = "nfc"
    const val UWB = "uwb"

    val subjects = listOf(CELLULAR, WIFI, BLUETOOTH, NFC, UWB)

    const val PRESENT = "present"
    const val ENABLED = "enabled"
    const val ERROR = "error"

    // cellular
    const val SIM_COUNTRY = "simCountry"
    const val OPERATOR_NAME = "operatorName"
    const val PHONE_TYPE = "phoneType"
    const val MODEMS = "activeModems"
    const val DATA_ENABLED = "dataEnabled"
    const val DATA_NETWORK = "dataNetworkType"
    const val SIGNAL_LEVEL = "signal:level"
    const val SIGNAL_DBM_PREFIX = "signal:dbm:"
    const val CARRIER_PRIVILEGES = "carrierPrivileges"

    // wifi
    const val WIFI_CONNECTED = "connected"
    const val WIFI_STANDARD = "standard"
    const val WIFI_BAND = "band"
    const val WIFI_FREQUENCY = "frequency:mhz"
    const val WIFI_LINK_SPEED = "linkSpeed:mbps"
    const val WIFI_RX_SPEED = "rxSpeed:mbps"
    const val WIFI_TX_SPEED = "txSpeed:mbps"
    const val WIFI_5GHZ = "supports:5GHz"
    const val WIFI_6GHZ = "supports:6GHz"
    const val WIFI_WPA3 = "supports:wpa3"
    const val WIFI_OWE = "supports:owe"

    // bluetooth
    const val BT_STATE = "state"
    const val BT_LE = "le"
    const val BT_LE_2M = "le:2MPhy"
    const val BT_LE_CODED = "le:codedPhy"
    const val BT_LE_EXTENDED = "le:extendedAdvertising"
    const val BT_LE_PERIODIC = "le:periodicAdvertising"
    const val BT_LE_MAX_ADV = "le:maxAdvertisingBytes"
    const val BT_MULTI_ADV = "le:multipleAdvertisement"

    // nfc
    const val NFC_HCE = "hostCardEmulation"
    const val NFC_SECURE = "secureNfcSupported"
    const val NFC_SECURE_ON = "secureNfcEnabled"

    /** `TelephonyManager.NETWORK_TYPE_*` as a short name. */
    fun networkTypeName(type: Int): String = when (type) {
        0 -> "unknown"
        1 -> "GPRS"
        2 -> "EDGE"
        3 -> "UMTS"
        4 -> "CDMA"
        5 -> "EVDO 0"
        6 -> "EVDO A"
        7 -> "1xRTT"
        8 -> "HSDPA"
        9 -> "HSUPA"
        10 -> "HSPA"
        11 -> "iDEN"
        12 -> "EVDO B"
        13 -> "LTE"
        14 -> "eHRPD"
        15 -> "HSPA+"
        16 -> "GSM"
        17 -> "TD-SCDMA"
        18 -> "IWLAN"
        20 -> "NR"
        else -> "type $type"
    }

    /** `TelephonyManager.PHONE_TYPE_*`. */
    fun phoneTypeName(type: Int): String = when (type) {
        0 -> "none"
        1 -> "GSM"
        2 -> "CDMA"
        3 -> "SIP"
        else -> "type $type"
    }

    /** `ScanResult.WIFI_STANDARD_*`. */
    fun wifiStandardName(standard: Int): String = when (standard) {
        0 -> "unknown"
        1 -> "legacy (802.11a/b/g)"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "Wi-Fi 6 (802.11ax)"
        7 -> "WiGig (802.11ad)"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> "standard $standard"
    }

    /** `BluetoothAdapter.STATE_*`. */
    fun bluetoothStateName(state: Int): String = when (state) {
        10 -> "off"
        11 -> "turning on"
        12 -> "on"
        13 -> "turning off"
        else -> "state $state"
    }

    /** `SignalStrength.getLevel()` 0..4 as words. */
    fun signalLevelLabel(level: Int): String = when (level) {
        0 -> "0 (none)"
        1 -> "1 (poor)"
        2 -> "2 (moderate)"
        3 -> "3 (good)"
        4 -> "4 (great)"
        else -> level.toString()
    }

    /** Frequency in MHz to a band name. */
    fun band(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..71000 -> "60 GHz"
        else -> "?"
    }

    /** `CellSignalStrengthLte` → `lte`; `CellSignalStrengthNr` → `nr`. */
    fun signalKind(className: String): String = className.removePrefix("CellSignalStrength").lowercase().ifEmpty { "unknown" }

    /** A dBm reading that is not `Integer.MAX_VALUE` (the platform's "unavailable"). */
    fun validDbm(dbm: Int): Boolean = dbm != Int.MAX_VALUE && dbm in -200..0
}
