package io.github.stronghorse44.tunnels.posture

/**
 * Turns the three command outputs into one [Reading] per item. A missing, unreadable or malformed key is UNKNOWN,
 * never weak. Order of judgement: the table failed (unreadable), the key is absent, the value does not parse
 * (malformed), the item is unconfirmed (unknown, value kept), then good or weak.
 */
object PostureReader {
    const val AUTO_REBOOT_MAX_HOURS = 18
    const val LOCK_DELAY_MAX_MS = 60_000L

    /** Longest value kept in an observation. */
    const val MAX_VALUE = 64

    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L

    // USB-C port modes 0..4, as normalised values.
    val USB_VALUES: List<String> = listOf(
        "all off", "charging only", "charging only when locked", "charging only when locked, except before first unlock", "on",
    )
    const val USB_BEFORE_FIRST_UNLOCK_MODE = 3
    const val USB_ON_MODE = 4

    const val DNS_AUTOMATIC = "automatic"
    const val DNS_PROVIDER = "provider"
    const val VPN_NONE = "none"
    const val OFF = "off"
    const val ON = "on"

    private val digits = Regex("""^\d{1,15}$""")
    private val safeName = Regex("""^[A-Za-z0-9_.]+$""")

    fun read(
        global: TableRead,
        secure: TableRead,
        props: TableRead,
        items: List<PostureItem> = PostureKeys.ITEMS,
    ): List<Reading> {
        fun table(t: PostureTable) = when (t) {
            PostureTable.GLOBAL -> global
            PostureTable.SECURE -> secure
            PostureTable.PROPS -> props
        }
        val done = LinkedHashMap<String, Reading>()
        for (item in items) {
            done[item.id] = if (item.id == PostureKeys.VPN_LOCKDOWN) {
                lockdown(item, table(item.table), done[PostureKeys.VPN_ALWAYS_ON])
            } else {
                single(item, table(item.table))
            }
        }
        return items.map { done.getValue(it.id) }
    }

    private fun unknown(item: PostureItem, why: PostureWhy, value: String? = null) = Reading(item, PostureState.UNKNOWN, value, why)

    /** The raw value of [key] in [table], or the unknown reading that stands in for it. */
    private sealed interface Raw {
        data class Value(val text: String) : Raw
        data class Missing(val why: PostureWhy) : Raw
    }

    private fun raw(table: TableRead, key: String, isProp: Boolean): Raw = when (table) {
        is TableRead.Failed -> Raw.Missing(PostureWhy.UNREADABLE)
        is TableRead.Ok -> {
            val v = table.values[key]
            when {
                key in table.conflicts -> Raw.Missing(PostureWhy.MALFORMED)
                // The command prints a line for every property, so no line means the output is not what was asked for.
                v == null -> Raw.Missing(if (isProp) PostureWhy.UNREADABLE else PostureWhy.ABSENT)
                isProp && v.isEmpty() -> Raw.Missing(PostureWhy.ABSENT)
                else -> Raw.Value(v)
            }
        }
    }

    private fun single(item: PostureItem, table: TableRead): Reading {
        val text = when (val r = raw(table, item.key, item.table == PostureTable.PROPS)) {
            is Raw.Missing -> return unknown(item, r.why)
            is Raw.Value -> r.text
        }
        val judged = judge(item.id, text) ?: return unknown(item, PostureWhy.MALFORMED)
        return finish(item, judged)
    }

    /** A parsed value and its verdict, before the item's own flags apply. */
    private class Judged(val state: PostureState, val value: String)

    private fun finish(item: PostureItem, j: Judged): Reading {
        val value = cap(j.value)
        return when {
            !item.confirmed -> unknown(item, PostureWhy.UNCONFIRMED, value)
            // No finding exists for it (no way to tell whether a second PIN is in use): the value shows, the state is not a verdict.
            item.id == PostureKeys.PIN_SCRAMBLE_2 -> Reading(item, PostureState.NA, value, null)
            else -> Reading(item, j.state, value, null)
        }
    }

    private fun judge(id: String, text: String): Judged? = when (id) {
        PostureKeys.AUTO_REBOOT -> millis(text)?.let { ms ->
            val weak = ms == 0L || ms > AUTO_REBOOT_MAX_HOURS * HOUR_MS
            Judged(if (weak) PostureState.WEAK else PostureState.GOOD, if (ms == 0L) OFF else duration(ms))
        }
        PostureKeys.USB_PORT -> mode(text, USB_VALUES.size - 1)?.let { m ->
            Judged(if (m >= USB_BEFORE_FIRST_UNLOCK_MODE) PostureState.WEAK else PostureState.GOOD, USB_VALUES[m])
        }
        PostureKeys.VPN_ALWAYS_ON -> when {
            text.isEmpty() || text == "null" -> Judged(PostureState.NA, VPN_NONE)
            safeName.matches(text) -> Judged(PostureState.GOOD, text)
            else -> null
        }
        PostureKeys.PRIVATE_DNS -> when (text) {
            "off" -> Judged(PostureState.WEAK, OFF)
            "opportunistic" -> Judged(PostureState.GOOD, DNS_AUTOMATIC)
            // The named provider is never read or stored.
            "hostname" -> Judged(PostureState.GOOD, DNS_PROVIDER)
            else -> null
        }
        PostureKeys.PIN_SCRAMBLE, PostureKeys.PIN_SCRAMBLE_2 -> flag(text)?.let { Judged(if (it) PostureState.GOOD else PostureState.WEAK, onOff(it)) }
        // 1 = Allow for third-party apps (weak); 0 = the restricted option.
        PostureKeys.CLIPBOARD_DEFAULT -> flag(text)?.let { Judged(if (it) PostureState.WEAK else PostureState.GOOD, if (it) "allow" else "restricted") }
        PostureKeys.CLIPBOARD_NOTICES -> flag(text)?.let { Judged(if (it) PostureState.GOOD else PostureState.WEAK, onOff(it)) }
        PostureKeys.SENSORS_DEFAULT -> flag(text)?.let { Judged(if (it) PostureState.WEAK else PostureState.GOOD, onOff(it)) }
        PostureKeys.WIFI_AUTO_OFF, PostureKeys.BT_AUTO_OFF, PostureKeys.NFC_AUTO_OFF -> millis(text)?.let { ms ->
            if (ms == 0L) Judged(PostureState.WEAK, OFF) else Judged(PostureState.GOOD, "after ${duration(ms)}")
        }
        PostureKeys.LOCK_DELAY -> millis(text)?.let { ms ->
            Judged(if (ms >= LOCK_DELAY_MAX_MS) PostureState.WEAK else PostureState.GOOD, duration(ms))
        }
        else -> null
    }

    /** The VPN lockdown reads two keys and depends on the always-on VPN's reading. */
    private fun lockdown(item: PostureItem, table: TableRead, app: Reading?): Reading {
        if (app == null) return unknown(item, PostureWhy.UNREADABLE)
        when (app.state) {
            PostureState.NA -> return Reading(item, PostureState.NA, null, null)
            PostureState.UNKNOWN -> return unknown(item, app.why ?: PostureWhy.UNREADABLE)
            else -> Unit
        }
        val flagText = when (val r = raw(table, item.key, isProp = false)) {
            is Raw.Missing -> return unknown(item, r.why)
            is Raw.Value -> r.text
        }
        val on = flag(flagText) ?: return unknown(item, PostureWhy.MALFORMED)
        if (!on) return finish(item, Judged(PostureState.WEAK, "lockdown off"))
        val extra = item.extraKey ?: return unknown(item, PostureWhy.MALFORMED)
        val exemptText = when (val r = raw(table, extra, isProp = false)) {
            is Raw.Missing -> return unknown(item, r.why)
            is Raw.Value -> r.text
        }
        val exempt = exemptCount(exemptText) ?: return unknown(item, PostureWhy.MALFORMED)
        return finish(item, Judged(if (exempt == 0) PostureState.GOOD else PostureState.WEAK, "lockdown on, $exempt exempt"))
    }

    /** Non-negative whole milliseconds, or null. */
    private fun millis(text: String): Long? = if (digits.matches(text)) text.toLong() else null

    /** `0` or `1`, or null. */
    private fun flag(text: String): Boolean? = when (text) {
        "0" -> false
        "1" -> true
        else -> null
    }

    private fun mode(text: String, max: Int): Int? = if (digits.matches(text)) text.toLong().takeIf { it <= max }?.toInt() else null

    private fun onOff(b: Boolean) = if (b) ON else OFF

    /** Number of packages in the lockdown's comma-separated exempt list; null when an entry is not a package name. */
    private fun exemptCount(text: String): Int? {
        if (text.isEmpty() || text == "null") return 0
        val entries = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (entries.all { safeName.matches(it) }) entries.size else null
    }

    /** "12 h", "90 min", "1 h 30 min", "45 s"; whole seconds only below a minute, else rounded down to the minute. */
    fun duration(ms: Long): String {
        if (ms < MINUTE_MS) return "${ms / 1000} s"
        val hours = ms / HOUR_MS
        val minutes = ms % HOUR_MS / MINUTE_MS
        return when {
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }

    fun cap(value: String): String = if (value.length > MAX_VALUE) value.take(MAX_VALUE - 1) + "…" else value
}
