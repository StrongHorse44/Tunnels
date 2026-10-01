package io.github.stronghorse44.tunnels.permrules

/**
 * What a permission lets an app reach, in the words a Settings screen would use. [weight] ranks how
 * much a grant matters: 0 is routine (not listed as sensitive), 3 is the most intrusive.
 */
enum class PermissionGroup(val label: String, val description: String, val weight: Int) {
    CAMERA("camera", "Take pictures and record video", 2),
    MICROPHONE("microphone", "Record audio", 2),
    LOCATION_FINE("precise location", "Know where the phone is to within a few metres", 2),
    LOCATION_COARSE("approximate location", "Know roughly where the phone is", 1),
    LOCATION_BACKGROUND("background location", "Track location while the app is not on screen", 3),
    CONTACTS("contacts", "Read or change the address book and accounts", 2),
    CALENDAR("calendar", "Read or change calendar events", 1),
    SMS("SMS", "Read, receive or send text messages", 3),
    CALL_LOG("call log", "See who was called and who called", 3),
    PHONE("phone", "Phone state, numbers and placing calls", 1),
    MEDIA("photos and media", "Photos, videos and audio files", 1),
    STORAGE_ALL_FILES("all files", "Every file on shared storage", 2),
    NEARBY_DEVICES("nearby devices", "Bluetooth, Wi-Fi and UWB devices around the phone", 1),
    BODY_SENSORS("body sensors", "Heart rate and other body measurements", 2),
    ACTIVITY_RECOGNITION("physical activity", "Steps, walking, cycling and other movement", 1),
    NOTIFICATIONS("notifications", "Show notifications", 0),
    ACCESSIBILITY("accessibility service", "See the screen and act on the user's behalf in any app", 3),
    OVERLAY("draw over other apps", "Put windows on top of other apps", 2),
    INSTALL_PACKAGES("install apps", "Install other apps", 1),
    DEVICE_ADMIN("device admin", "Enforce device policies such as wiping or locking", 2),
    NOTIFICATION_LISTENER("notification access", "Read every notification, including message contents", 2),
    USAGE_STATS("usage access", "Which apps are used and for how long", 1),
    READ_LOGS("read logs", "Read the system log, which can contain other apps' data", 2),
    NETWORK("network", "Open network connections", 0),
    OTHER("other", "Everything else", 0),
    ;

    /** Groups that count as sensitive for findings. */
    val isSensitive: Boolean get() = weight > 0
}

/** Classifies Android permission names into [PermissionGroup]s. Unknown names are [PermissionGroup.OTHER]. */
object PermissionCatalog {
    private const val P = "android.permission."

    /** GrapheneOS's per-app Sensors toggle, exposed as a requested permission on every app. Marked "verify" in the spec. */
    const val OTHER_SENSORS = P + "OTHER_SENSORS"
    const val INTERNET = P + "INTERNET"

    private val byName: Map<String, PermissionGroup> = buildMap {
        fun put(group: PermissionGroup, vararg names: String) = names.forEach { put(P + it, group) }
        put(PermissionGroup.CAMERA, "CAMERA")
        put(PermissionGroup.MICROPHONE, "RECORD_AUDIO")
        put(PermissionGroup.LOCATION_FINE, "ACCESS_FINE_LOCATION")
        put(PermissionGroup.LOCATION_COARSE, "ACCESS_COARSE_LOCATION")
        put(PermissionGroup.LOCATION_BACKGROUND, "ACCESS_BACKGROUND_LOCATION")
        put(PermissionGroup.CONTACTS, "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")
        put(PermissionGroup.CALENDAR, "READ_CALENDAR", "WRITE_CALENDAR")
        put(PermissionGroup.SMS, "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS", "READ_CELL_BROADCASTS")
        put(PermissionGroup.CALL_LOG, "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS")
        put(PermissionGroup.PHONE, "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS", "ADD_VOICEMAIL", "USE_SIP", "ACCEPT_HANDOVER", "READ_BASIC_PHONE_STATE")
        put(
            PermissionGroup.MEDIA,
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED",
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION",
        )
        put(PermissionGroup.STORAGE_ALL_FILES, "MANAGE_EXTERNAL_STORAGE")
        put(PermissionGroup.NEARBY_DEVICES, "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "NEARBY_WIFI_DEVICES", "UWB_RANGING")
        put(PermissionGroup.BODY_SENSORS, "BODY_SENSORS", "BODY_SENSORS_BACKGROUND")
        put(PermissionGroup.ACTIVITY_RECOGNITION, "ACTIVITY_RECOGNITION")
        put(PermissionGroup.NOTIFICATIONS, "POST_NOTIFICATIONS")
        put(PermissionGroup.ACCESSIBILITY, "BIND_ACCESSIBILITY_SERVICE")
        put(PermissionGroup.OVERLAY, "SYSTEM_ALERT_WINDOW")
        put(PermissionGroup.INSTALL_PACKAGES, "REQUEST_INSTALL_PACKAGES", "INSTALL_PACKAGES")
        put(PermissionGroup.DEVICE_ADMIN, "BIND_DEVICE_ADMIN")
        put(PermissionGroup.NOTIFICATION_LISTENER, "BIND_NOTIFICATION_LISTENER_SERVICE")
        put(PermissionGroup.USAGE_STATS, "PACKAGE_USAGE_STATS")
        put(PermissionGroup.READ_LOGS, "READ_LOGS")
        put(PermissionGroup.NETWORK, "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_MULTICAST_STATE")
    }

    fun groupOf(permission: String): PermissionGroup = byName[permission] ?: when {
        permission.startsWith(P + "health.") -> PermissionGroup.BODY_SENSORS
        else -> PermissionGroup.OTHER
    }

    /** Groups worth listing in a finding, most intrusive first. */
    val sensitive: List<PermissionGroup> = PermissionGroup.entries.filter { it.isSensitive }.sortedByDescending { it.weight }

    /** "android.permission.READ_SMS" -> "READ_SMS". */
    fun shortName(permission: String): String = permission.substringAfterLast('.')

    /** Comma list of group labels, most intrusive first: "background location, camera, contacts". */
    fun describe(groups: Collection<PermissionGroup>): String =
        groups.distinct().sortedWith(compareByDescending<PermissionGroup> { it.weight }.thenBy { it.ordinal }).joinToString(", ") { it.label }

    /**
     * Readable list of raw permission names: sensitive ones by group label, the rest by short name,
     * at most [max] items then "and N more".
     */
    fun describePermissions(permissions: Collection<String>, max: Int = 6): String {
        val groups = permissions.map(::groupOf).filter { it.isSensitive }.distinct()
        val labels = describe(groups).split(", ").filter { it.isNotEmpty() }
        val others = permissions.filter { !groupOf(it).isSensitive }.map(::shortName).sorted()
        val items = labels + others
        if (items.size <= max) return items.joinToString(", ")
        return items.take(max).joinToString(", ") + " and ${items.size - max} more"
    }
}
