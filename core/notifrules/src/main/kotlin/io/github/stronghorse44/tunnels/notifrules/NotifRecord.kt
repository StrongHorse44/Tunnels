package io.github.stronghorse44.tunnels.notifrules

/**
 * The flags the listener keeps about one posted notification, and nothing more. Serialised as a compact
 * `key=value;key=value` string in the events table, e.g. `imp=HIGH;vis=PUBLIC;cat=msg;ongoing=0;silent=0;night=1;h=23`.
 */
data class NotifRecord(
    val importance: Importance = Importance.UNKNOWN,
    val visibility: Visibility = Visibility.UNSET,
    /** Android notification category ("msg", "email", "promo", ...), or empty when the app set none. */
    val category: String = "",
    val ongoing: Boolean = false,
    /** Delivered without sound or vibration. */
    val silent: Boolean = false,
    /** Local hour of day (0..23) at post time, or -1 when unknown. */
    val hour: Int = -1,
    /** Posted between 23:00 and 06:00. Derived from [hour] when that is known. */
    val night: Boolean = false,
) {
    /** The channel's effective importance, in Android's own words. */
    enum class Importance(val androidValue: Int) {
        UNKNOWN(-1000), NONE(0), MIN(1), LOW(2), DEFAULT(3), HIGH(4);

        /** Peeks over other apps and may sound: HIGH or above. */
        val urgent: Boolean get() = this == HIGH

        companion object {
            /** Maps `NotificationManager.IMPORTANCE_*`; values above HIGH (the legacy MAX) count as HIGH. */
            fun fromAndroid(value: Int): Importance = when {
                value >= HIGH.androidValue -> HIGH
                value == DEFAULT.androidValue -> DEFAULT
                value == LOW.androidValue -> LOW
                value == MIN.androidValue -> MIN
                value == NONE.androidValue -> NONE
                else -> UNKNOWN
            }
        }
    }

    /** Effective lock-screen visibility. */
    enum class Visibility(val androidValue: Int) {
        UNSET(-1000), SECRET(-1), PRIVATE(0), PUBLIC(1);

        companion object {
            /** Maps `Notification.VISIBILITY_*`. */
            fun fromAndroid(value: Int): Visibility = entries.firstOrNull { it.androidValue == value } ?: UNSET
        }
    }

    val lockScreenPublic: Boolean get() = visibility == Visibility.PUBLIC

    fun encode(): String = buildString {
        append("imp=").append(importance.name)
        append(";vis=").append(visibility.name)
        append(";cat=").append(category)
        append(";ongoing=").append(if (ongoing) 1 else 0)
        append(";silent=").append(if (silent) 1 else 0)
        append(";night=").append(if (night) 1 else 0)
        if (hour in 0..23) append(";h=").append(hour)
    }

    companion object {
        /** The value stored for any category outside [knownCategories]. */
        const val OTHER_CATEGORY = "other"

        /** Android's `Notification.CATEGORY_*` vocabulary (API 36). Only these are stored verbatim. */
        val knownCategories: Set<String> = setOf(
            "alarm", "call", "email", "err", "event", "location_sharing", "missed_call", "msg", "navigation",
            "progress", "promo", "recommendation", "reminder", "service", "social", "status", "stopwatch", "sys",
            "transport", "voicemail", "workout",
        )

        /** Hours that count as night: 23:00 up to but excluding 06:00. */
        fun isNightHour(hour: Int): Boolean = hour == 23 || hour in 0..5

        /**
         * Reduces a raw category string to Android's closed vocabulary. Anything else, including a
         * developer-chosen custom string, becomes [OTHER_CATEGORY] so no free-form text reaches the store.
         */
        fun sanitiseCategory(raw: String?): String {
            val c = raw?.trim()?.lowercase().orEmpty()
            return when {
                c.isEmpty() -> ""
                c in knownCategories -> c
                else -> OTHER_CATEGORY
            }
        }

        /** Builds a record from Android's raw values, deriving [night] from the hour. */
        fun of(
            importance: Int,
            visibility: Int,
            category: String?,
            ongoing: Boolean,
            silent: Boolean,
            hour: Int,
        ): NotifRecord = NotifRecord(
            importance = Importance.fromAndroid(importance),
            visibility = Visibility.fromAndroid(visibility),
            category = sanitiseCategory(category),
            ongoing = ongoing,
            silent = silent,
            hour = if (hour in 0..23) hour else -1,
            night = hour in 0..23 && isNightHour(hour),
        )

        /** Parses an [encode]d string. Unknown keys are ignored, malformed fields fall back to defaults; null for garbage. */
        fun parse(summary: String): NotifRecord? {
            if (summary.isBlank() || !summary.contains('=')) return null
            var importance = Importance.UNKNOWN
            var visibility = Visibility.UNSET
            var category = ""
            var ongoing = false
            var silent = false
            var night: Boolean? = null
            var hour = -1
            for (field in summary.split(';')) {
                val eq = field.indexOf('=')
                if (eq <= 0) continue
                val k = field.substring(0, eq).trim()
                val v = field.substring(eq + 1).trim()
                when (k) {
                    "imp" -> importance = Importance.entries.firstOrNull { it.name == v } ?: Importance.UNKNOWN
                    "vis" -> visibility = Visibility.entries.firstOrNull { it.name == v } ?: Visibility.UNSET
                    "cat" -> category = sanitiseCategory(v)
                    "ongoing" -> ongoing = v == "1" || v == "true"
                    "silent" -> silent = v == "1" || v == "true"
                    "night" -> night = v == "1" || v == "true"
                    "h" -> hour = v.toIntOrNull()?.takeIf { it in 0..23 } ?: -1
                }
            }
            return NotifRecord(
                importance = importance,
                visibility = visibility,
                category = category,
                ongoing = ongoing,
                silent = silent,
                hour = hour,
                night = if (hour >= 0) isNightHour(hour) else (night ?: false),
            )
        }
    }
}
