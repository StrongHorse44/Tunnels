package io.github.stronghorse44.tunnels.engine

import java.time.LocalDate

/*
 * Moved from core/lan (DeviceNotes.kt) so the store's maintenance can enforce its retention without depending on the
 * Home network logic (audit L5, review Q4). Plain java.time only.
 */

/** How often a device is on the network (Home network's device notes): counts and first and last days, never a list of times (RJ's choice). */
object Presence {
    /** Devices remembered per network; past it, the one seen longest ago is forgotten. */
    const val MAX_ENTRIES = 128

    /** A device not seen for this many days is forgotten, like every event (rule 4). */
    const val RETENTION_DAYS = 30

    /** Each network's book is the settings row with this prefix and the network's tag (`homenet.presence.<tag>`). */
    const val SETTINGS_PREFIX = "homenet.presence."

    /** One device: first and last day seen (days since 1970), scans and days it was there, and the network's scan-day count when first seen. */
    data class Entry(val firstDay: Long, val lastDay: Long, val scans: Int, val days: Int, val scanDaysAtFirst: Int)

    /** One network's book: on how many days it was scanned, the last of them, and its devices. */
    data class Book(val scanDays: Int, val lastScanDay: Long, val entries: Map<String, Entry>) {
        fun encode(): String = buildString {
            append("v1;").append(scanDays).append(';').append(lastScanDay)
            for ((key, e) in entries.toSortedMap()) {
                append(';').append(key).append('=').append(listOf(e.firstDay, e.lastDay, e.scans, e.days, e.scanDaysAtFirst).joinToString(","))
            }
        }

        companion object {
            val EMPTY = Book(0, -1, emptyMap())

            private val PLAIN_KEY = Regex("[A-Za-z0-9:._-]{1,64}")

            private fun plainKey(key: String): Boolean = PLAIN_KEY.matches(key)

            /**
             * The book stored as [text]. [isKey] says which device keys are well formed: Home network passes
             * `DeviceKeys.isKey` (census tokens and addresses); the default only refuses what the encoding reserves.
             */
            fun parse(text: String?, isKey: (String) -> Boolean = ::plainKey): Book {
                val parts = text?.split(';') ?: return EMPTY
                if (parts.size < 3 || parts[0] != "v1") return EMPTY
                val scanDays = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return EMPTY
                val lastScanDay = parts[2].toLongOrNull() ?: return EMPTY
                val entries = HashMap<String, Entry>()
                for (p in parts.drop(3)) {
                    val key = p.substringBefore('=')
                    if (!isKey(key)) continue
                    val n = p.substringAfter('=', "").split(',').map { it.toLongOrNull() }
                    if (n.size != 5 || n.any { it == null || it < 0 }) continue
                    entries[key] = Entry(n[0]!!, n[1]!!, n[2]!!.toInt(), n[3]!!.toInt(), n[4]!!.toInt())
                }
                return Book(scanDays, lastScanDay, entries)
            }
        }
    }

    /**
     * The book after a scan on [today] that saw the devices [seen] (their keys): counts updated, devices not seen for
     * [RETENTION_DAYS] forgotten, at most [MAX_ENTRIES] (the ones seen longest ago go first).
     */
    fun update(book: Book, today: Long, seen: Collection<String>): Book {
        val newDay = today != book.lastScanDay
        val scanDays = book.scanDays + if (newDay) 1 else 0
        val entries = HashMap(book.entries)
        for (key in seen.toSet()) {
            val e = entries[key]
            entries[key] = if (e == null) {
                Entry(today, today, 1, 1, scanDays)
            } else {
                e.copy(lastDay = today, scans = e.scans + 1, days = e.days + if (e.lastDay != today) 1 else 0)
            }
        }
        val kept = entries.filterValues { today - it.lastDay <= RETENTION_DAYS }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.lastDay }.thenBy { it.key })
            .take(MAX_ENTRIES).associate { it.key to it.value }
        return Book(scanDays, today, kept)
    }

    /**
     * The stored row [text] with retention applied on [today] (rule 4, audit L5), for the store's routine maintenance:
     * devices not seen for [RETENTION_DAYS] are forgotten even when the network is never scanned again, and the row goes
     * (null) once no device is left and the network itself was last scanned more than [RETENTION_DAYS] ago. A row that is
     * not a v1 book is returned as it is; a row with nothing to forget is returned unchanged.
     */
    fun prune(text: String, today: Long): String? {
        if (!text.startsWith("v1;")) return text
        val book = Book.parse(text)
        if (book == Book.EMPTY) return text
        val kept = book.entries.filterValues { today - it.lastDay <= RETENTION_DAYS }
        if (kept.isEmpty() && today - book.lastScanDay > RETENTION_DAYS) return null
        if (kept.size == book.entries.size) return text
        return book.copy(entries = kept).encode()
    }

    enum class Pattern(val words: String) {
        NEW("new today"),
        ALWAYS("always here"),
        OFTEN("here most days"),
        SOMETIMES("comes and goes"),
        ONCE("seen on one day only"),
    }

    /** Days the network was scanned since the device was first seen: the most days it could have been seen on. */
    fun possibleDays(e: Entry, book: Book): Int = (book.scanDays - e.scanDaysAtFirst + 1).coerceAtLeast(1)

    /**
     * Its pattern: new (first seen [today], on a network first scanned on an earlier day), always (seen on at least 3
     * days and on 90% of the days scanned since), often (half or more), comes and goes, or seen on one day only.
     */
    fun pattern(e: Entry, book: Book, today: Long): Pattern {
        val possible = possibleDays(e, book)
        return when {
            e.firstDay == today && e.scanDaysAtFirst > 1 -> Pattern.NEW
            e.days <= 1 -> Pattern.ONCE
            e.days >= 3 && e.days * 10 >= possible * 9 -> Pattern.ALWAYS
            e.days * 2 >= possible -> Pattern.OFTEN
            else -> Pattern.SOMETIMES
        }
    }

    /** "Here on 12 of 14 days scanned, 31 scans, since 2026-10-01": the counts in words. */
    fun words(e: Entry, book: Book): String {
        val possible = possibleDays(e, book)
        return "Here on ${e.days} of $possible ${if (possible == 1) "day" else "days"} scanned, " +
            "${e.scans} ${if (e.scans == 1) "scan" else "scans"}, since ${LocalDate.ofEpochDay(e.firstDay)}"
    }
}
