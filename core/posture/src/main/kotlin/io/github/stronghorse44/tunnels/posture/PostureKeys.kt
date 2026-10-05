package io.github.stronghorse44.tunnels.posture

/** Where a posture key is read from: two `settings list` tables and one `getprop` command. */
enum class PostureTable(val id: String, val label: String) {
    GLOBAL("global", "global settings"),
    SECURE("secure", "secure settings"),
    PROPS("props", "system properties"),
}

/**
 * One GrapheneOS setting Tunnels judges. [confirmed] is the per-item flag: true only once the key name has been seen on
 * RJ's phone. An unconfirmed item is read and shown, but its state is unknown and it never raises a finding.
 * To confirm an item flip its flag, add its line to `rjsOutputOf20261005ReadsTheConfirmedKeys`, and copy its Settings
 * path into [path] (the evidence ends with it).
 *
 * [kind] is null for items with no finding (display only, or panel only). Confirmed on RJ's phone 2026-10-05 (step 0): every
 * item except [USB_PORT] (no readable property on this build; its read stays here behind `confirmed = false` for a later
 * build) and the three auto-off timers (no key changed, the timers were not found in Settings). [extraKey] is a second key of the same table
 * that the item also reads (the VPN lockdown's exempt list).
 */
data class PostureItem(
    val id: String,
    val title: String,
    val table: PostureTable,
    val key: String,
    val confirmed: Boolean,
    val kind: String?,
    val sourceDefault: String,
    val action: String?,
    val actionLabel: String?,
    val path: String,
    val extraKey: String? = null,
    /**
     * Set when Tunnels cannot read this setting on the phone: while the item is unconfirmed the card shows this text and a
     * Settings button instead of a reading (Explore-style, never a finding).
     */
    val reminder: String? = null,
)

/**
 * The posture allowlist. Source names are from GrapheneOS `platform_frameworks_base` branch 17; an absent key means the
 * OS uses its built-in default, which Tunnels still reports as unknown. Plain Kotlin.
 */
object PostureKeys {
    /** Subject of every posture observation and finding. */
    const val SUBJECT = "posture"

    /** The tunnel whose snapshots hold the posture observations (Deep mode's id; a test in deepmode pins it). */
    const val TUNNEL_ID = "deep_mode"

    // android.provider.Settings actions, spelled out so this module stays plain Kotlin.
    const val ACTION_SECURITY = "android.settings.SECURITY_SETTINGS"
    const val ACTION_PRIVACY = "android.settings.PRIVACY_SETTINGS"
    const val ACTION_VPN = "android.settings.VPN_SETTINGS"
    const val ACTION_NETWORK = "android.settings.WIRELESS_SETTINGS"
    const val ACTION_WIFI = "android.settings.WIFI_SETTINGS"
    const val ACTION_BLUETOOTH = "android.settings.BLUETOOTH_SETTINGS"
    const val ACTION_NFC = "android.settings.NFC_SETTINGS"

    const val AUTO_REBOOT = "auto_reboot"
    const val USB_PORT = "usb_port"
    const val VPN_ALWAYS_ON = "vpn_always_on"
    const val VPN_LOCKDOWN = "vpn_lockdown"
    const val PRIVATE_DNS = "private_dns"
    const val PIN_SCRAMBLE = "pin_scramble"
    const val PIN_SCRAMBLE_2 = "pin_scramble_2"
    const val CLIPBOARD_DEFAULT = "clipboard_default"
    const val CLIPBOARD_NOTICES = "clipboard_notices"
    const val WIFI_AUTO_OFF = "wifi_auto_off"
    const val BT_AUTO_OFF = "bt_auto_off"
    const val NFC_AUTO_OFF = "nfc_auto_off"
    const val SENSORS_DEFAULT = "sensors_default"
    const val LOCK_DELAY = "lock_delay"

    const val USB_MODE_PROP = "persist.security.usb_mode"
    const val LOCKDOWN_EXEMPT_KEY = "always_on_vpn_lockdown_whitelist"

    /** Read by name through `getprop`; every name must stay shell-safe (a test checks it). */
    val PROPS: List<String> = listOf(USB_MODE_PROP)

    /** One `echo "<name>=$(getprop <name>)"` per name, so an unset property still prints a line. */
    val PROPS_COMMAND: String = PROPS.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" }

    // The order is the order of the card. One line per item: flip `confirmed` and fill `path` after step 0.
    val ITEMS: List<PostureItem> = listOf(
        PostureItem(
            AUTO_REBOOT, "Auto reboot", PostureTable.GLOBAL, "settings_reboot_after_timeout",
            confirmed = true, kind = "POSTURE_AUTO_REBOOT", sourceDefault = "18 h",
            action = ACTION_SECURITY, actionLabel = "Security settings",
            path = "Security & privacy > Exploit protection > Auto reboot",
        ),
        PostureItem(
            USB_PORT, "USB-C port", PostureTable.PROPS, USB_MODE_PROP,
            confirmed = false, kind = "POSTURE_USB_PORT", sourceDefault = "charging only when locked",
            action = ACTION_SECURITY, actionLabel = "Security settings",
            path = "Security & privacy > Exploit protection > USB-C port",
            reminder = "No app can read the USB-C port mode on this build. Check it yourself: Security & privacy > Exploit protection > " +
                "USB-C port; Charging-only when locked or stricter is the safe choice.",
        ),
        PostureItem(
            // No finding (DECISIONS 2026-10-05, Q1): "no always-on VPN" is shown on the card only.
            VPN_ALWAYS_ON, "Always-on VPN", PostureTable.SECURE, "always_on_vpn_app",
            confirmed = true, kind = null, sourceDefault = "none",
            action = null, actionLabel = null, path = "Network & internet > VPN",
        ),
        PostureItem(
            VPN_LOCKDOWN, "VPN lockdown", PostureTable.SECURE, "always_on_vpn_lockdown",
            confirmed = true, kind = "POSTURE_VPN_LOCKDOWN", sourceDefault = "off",
            action = ACTION_VPN, actionLabel = "VPN settings",
            path = "Network & internet > VPN > the VPN's gear",
            extraKey = LOCKDOWN_EXEMPT_KEY,
        ),
        PostureItem(
            PRIVATE_DNS, "Private DNS", PostureTable.GLOBAL, "private_dns_mode",
            confirmed = true, kind = "POSTURE_PRIVATE_DNS", sourceDefault = "automatic",
            action = ACTION_NETWORK, actionLabel = "Network settings",
            path = "Network & internet > Private DNS",
        ),
        PostureItem(
            PIN_SCRAMBLE, "Scramble PIN layout", PostureTable.SECURE, "lockscreen_scramble_pin_layout",
            confirmed = true, kind = "POSTURE_PIN_SCRAMBLE", sourceDefault = "off",
            action = ACTION_SECURITY, actionLabel = "Security settings",
            path = "Security & privacy > Device unlock > Screen lock > Screen lock settings",
        ),
        PostureItem(
            // Display only: Tunnels cannot tell whether a secondary PIN is in use.
            PIN_SCRAMBLE_2, "Scramble PIN layout (second PIN)", PostureTable.SECURE, "lockscreen_scramble_pin_layout_secondary",
            confirmed = true, kind = null, sourceDefault = "off",
            action = null, actionLabel = null, path = "",
        ),
        PostureItem(
            CLIPBOARD_DEFAULT, "Clipboard access by default", PostureTable.GLOBAL, "allow_clipboard_read",
            confirmed = true, kind = "POSTURE_CLIPBOARD_DEFAULT", sourceDefault = "Allow",
            action = ACTION_PRIVACY, actionLabel = "Privacy settings",
            path = "Security & privacy > Privacy controls > Clipboard access",
        ),
        PostureItem(
            CLIPBOARD_NOTICES, "Clipboard access notices", PostureTable.SECURE, "clipboard_show_access_notifications",
            confirmed = true, kind = "POSTURE_CLIPBOARD_NOTICES", sourceDefault = "on",
            action = ACTION_PRIVACY, actionLabel = "Privacy settings",
            path = "Security & privacy > Privacy controls > Clipboard access",
        ),
        PostureItem(
            WIFI_AUTO_OFF, "Wi-Fi auto-off", PostureTable.GLOBAL, "wifi_off_timeout",
            confirmed = false, kind = "POSTURE_WIFI_AUTO_OFF", sourceDefault = "never",
            action = ACTION_WIFI, actionLabel = "Wi-Fi settings",
            path = "Network & internet > Internet > Network preferences",
        ),
        PostureItem(
            BT_AUTO_OFF, "Bluetooth auto-off", PostureTable.GLOBAL, "bluetooth_off_timeout",
            confirmed = false, kind = "POSTURE_BT_AUTO_OFF", sourceDefault = "never",
            action = ACTION_BLUETOOTH, actionLabel = "Bluetooth settings",
            path = "Connected devices > Connection preferences > Bluetooth",
        ),
        PostureItem(
            NFC_AUTO_OFF, "NFC auto-off", PostureTable.GLOBAL, "nfc_off_timeout",
            confirmed = false, kind = "POSTURE_NFC_AUTO_OFF", sourceDefault = "never",
            action = ACTION_NFC, actionLabel = "NFC settings",
            path = "", // from step 0
        ),
        PostureItem(
            SENSORS_DEFAULT, "Sensors permission by default", PostureTable.SECURE, "auto_grant_OTHER_SENSORS_perm",
            confirmed = true, kind = "POSTURE_SENSORS_DEFAULT", sourceDefault = "on",
            action = ACTION_SECURITY, actionLabel = "Security settings",
            path = "Security & privacy > More security & privacy > Allow Sensors permission to apps by default",
        ),
        PostureItem(
            LOCK_DELAY, "Lock after screen timeout", PostureTable.SECURE, "lock_screen_lock_after_timeout",
            confirmed = true, kind = "POSTURE_LOCK_DELAY", sourceDefault = "5 s",
            action = ACTION_SECURITY, actionLabel = "Security settings",
            path = "Security & privacy > Device unlock > Screen lock > Screen lock settings",
        ),
    )

    /** The keys read from [table]: the item keys plus any extra key. */
    fun keys(table: PostureTable, items: List<PostureItem> = ITEMS): Set<String> =
        items.filter { it.table == table }.flatMapTo(LinkedHashSet()) { listOfNotNull(it.key, it.extraKey) }

    /** Every key of the two `settings list` tables. */
    val SETTINGS_KEYS: Set<String> = keys(PostureTable.GLOBAL) + keys(PostureTable.SECURE)

    fun item(id: String, items: List<PostureItem> = ITEMS): PostureItem? = items.firstOrNull { it.id == id }

    // Observation keys (all under [SUBJECT]).
    fun stateKey(id: String) = "$SUBJECT:$id"
    fun valueKey(id: String) = "$SUBJECT:$id:value"
    fun whyKey(id: String) = "$SUBJECT:$id:why"
    fun readKey(table: PostureTable) = "$SUBJECT:read:${table.id}"
}
