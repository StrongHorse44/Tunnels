package io.github.stronghorse44.tunnels.lan

import java.time.LocalDate
import java.util.Locale

/**
 * What Home network remembers about each device besides the census list (RJ, 2026-10-11): how often it is there
 * ([Presence], counts only, no timeline), the name the user gave it ([Labels]) and whether the user watches it
 * ([Watch]). All three are per network and keyed by [DeviceKeys]. Pure functions, plain JVM, unit-tested.
 */
object DeviceKeys {
    private const val ADDRESS = "ip:"

    /** A host as the notes see it: its census tokens (primary first; none when it gave nothing to recognise it by) and its address. */
    data class Host(val ids: List<String>, val ip: String) {
        /** Its key: the primary census token when it has one (it follows the device across addresses), else its address. */
        val key: String get() = of(DeviceIdentity.primaryOf(ids), ip)
    }

    /**
     * A device's key: its primary census token when it has one, else its address on this network, which follows the
     * address: if the router gives that address to another device that gives nothing to recognise it by, the notes go
     * with the address.
     */
    fun of(primaryId: String?, ip: String): String = primaryId ?: (ADDRESS + ip)

    fun address(ip: String): String = ADDRESS + ip

    fun isAddressKey(key: String): Boolean = key.startsWith(ADDRESS)

    private val SHAPE = Regex("(ip:[0-9a-fA-F:.]{2,45})|${DeviceIdentity.TOKEN.pattern}")

    fun isKey(text: String): Boolean = SHAPE.matches(text)

    /**
     * [notes] with each host's entry moved to its key ([Host.key]) when it was kept under another: one of its other tokens
     * (a lost SSDP reply can change which token is primary), or its address while it gave nothing to recognise it by. The
     * address entry moves only to a host whose tokens are all router or direct names (`h`, `c`): a device that announces
     * nothing and became recognisable later, after a look closer. A device that announces itself never takes over what
     * was noted under its address, which may have been another device's. A host whose key already holds an entry keeps it.
     */
    fun <T> rekey(notes: Map<String, T>, hosts: Collection<Host>): Map<String, T> {
        val out = LinkedHashMap(notes)
        for (h in hosts) {
            val key = h.key
            if (key in out || isAddressKey(key)) continue
            val quiet = h.ids.all { it.startsWith("h") || it.startsWith("c") }
            val from = h.ids.firstOrNull { it in out } ?: address(h.ip).takeIf { quiet && it in out } ?: continue
            out.remove(from)?.let { out[key] = it }
        }
        return out
    }
}

/** How often a device is on the network: counts and first and last days, never a list of times (RJ's choice). */
object Presence {
    /** Devices remembered per network; past it, the one seen longest ago is forgotten. */
    const val MAX_ENTRIES = 128

    /** A device not seen for this many days is forgotten, like every event (rule 4). */
    const val RETENTION_DAYS = 30

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

            fun parse(text: String?): Book {
                val parts = text?.split(';') ?: return EMPTY
                if (parts.size < 3 || parts[0] != "v1") return EMPTY
                val scanDays = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return EMPTY
                val lastScanDay = parts[2].toLongOrNull() ?: return EMPTY
                val entries = HashMap<String, Entry>()
                for (p in parts.drop(3)) {
                    val key = p.substringBefore('=')
                    if (!DeviceKeys.isKey(key)) continue
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

/** The names the user gives devices, per network. */
object Labels {
    const val MAX_LABELS = 128
    const val MAX_LENGTH = 40

    /** A label as kept: printable, whitespace collapsed, at most [MAX_LENGTH]; null for an empty one (which clears it). */
    fun clean(text: String?): String? = Greetings.clean(text, MAX_LENGTH)

    fun encode(labels: Map<String, String>): String =
        labels.toSortedMap().entries.take(MAX_LABELS).joinToString(";") { (k, v) -> "$k=${LookCloser.escape(v)}" }

    fun parse(text: String?): Map<String, String> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (p in text.split(';')) {
            val key = p.substringBefore('=')
            if (!DeviceKeys.isKey(key) || out.size >= MAX_LABELS) continue
            val value = LookCloser.unescape(p.substringAfter('=', ""))?.let(::clean) ?: continue
            out[key] = value
        }
        return out
    }

    /**
     * [labels] with [key] set to [label], or removed when [label] is blank; null when a new name does not fit
     * ([MAX_LABELS]), so the caller can say so instead of losing one.
     */
    fun with(labels: Map<String, String>, key: String, label: String?): Map<String, String>? {
        val clean = clean(label)
        if (clean != null && key !in labels && labels.size >= MAX_LABELS) return null
        val out = LinkedHashMap(labels)
        if (clean == null) out.remove(key) else out[key] = clean
        return out
    }
}

/**
 * Devices the user watches, each with what it was like at the last scan of its network, so the next scan can say it came
 * back or changed (RJ, 2026-10-11). Background checks never touch the network, so news arrives with the user's next scan.
 * One list for every network (each entry names its network's tag), so "Stop watching" works from a finding alone.
 */
object Watch {
    const val MAX_WATCHED = 32

    private val TAG = Regex("[0-9a-f]{${NetworkFingerprint.PREFIX_LENGTH}}")

    /** One watched device on the network [tag] as last seen: there or not, its open ports and its name. */
    data class State(val tag: String, val key: String, val present: Boolean, val ports: List<Int>, val name: String?)

    /** What changed since the last scan. */
    sealed interface Event {
        data object Back : Event
        data class Changed(val what: List<String>) : Event
    }

    /** An event as a scan noted it: on which day (days since 1970) and what. */
    data class Noted(val day: Long, val event: Event)

    /** What a scan saw of a watched device. */
    data class Now(val ports: List<Int>, val name: String?)

    fun encode(states: Collection<State>): String = states.sortedWith(compareBy({ it.tag }, { it.key })).take(MAX_WATCHED).joinToString(";") { s ->
        listOf(s.tag, s.key, if (s.present) "1" else "0", s.ports.sorted().joinToString("."), LookCloser.escape(s.name.orEmpty())).joinToString(",")
    }

    fun parse(text: String?): List<State> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(';').mapNotNull { p ->
            val f = p.split(',')
            if (f.size != 5 || !TAG.matches(f[0]) || !DeviceKeys.isKey(f[1])) return@mapNotNull null
            State(
                tag = f[0],
                key = f[1],
                present = f[2] == "1",
                ports = f[3].split('.').mapNotNull { it.toIntOrNull() }.filter { it in 1..65535 }.distinct().sorted(),
                name = LookCloser.unescape(f[4])?.takeIf { it.isNotEmpty() },
            )
        }.distinctBy { it.tag to it.key }.take(MAX_WATCHED)
    }

    fun isWatched(states: Collection<State>, tag: String, key: String): Boolean = states.any { it.tag == tag && it.key == key }

    /**
     * [states] with the device [key] on [tag] watched from now, as the user sees it ([now]), or no longer watched; null
     * when a new watch does not fit ([MAX_WATCHED]).
     */
    fun with(states: List<State>, tag: String, key: String, watch: Boolean, now: Now): List<State>? {
        val rest = states.filterNot { it.tag == tag && it.key == key }
        if (!watch) return rest
        if (rest.size >= MAX_WATCHED) return null
        return rest + State(tag, key, present = true, ports = now.ports.distinct().sorted(), name = now.name)
    }

    /** [states] without [key] on any network: "Stop watching" from a finding, which names the device but not the network. */
    fun without(states: List<State>, key: String): List<State> = states.filterNot { it.key == key }

    /**
     * What a scan says about a watched device compared with [before]: back (absent at the last scan, here now), changed
     * (here both times, with other open ports or another name), or nothing. Gone is not news: the device list shows it.
     * [portsComplete] false (the port stage ran out of time) leaves the ports out of the comparison.
     */
    fun event(before: State, now: Now?, portsComplete: Boolean = true): Event? {
        if (now == null) return null
        if (!before.present) return Event.Back
        val what = ArrayList<String>()
        if (portsComplete) {
            val added = now.ports.toSet() - before.ports.toSet()
            val removed = before.ports.toSet() - now.ports.toSet()
            if (added.isNotEmpty()) what += "opened ${PortCatalog.describe(added)}"
            if (removed.isNotEmpty()) what += "closed ${PortCatalog.describe(removed)}"
        }
        val oldName = before.name?.lowercase(Locale.ROOT)
        val newName = now.name?.lowercase(Locale.ROOT)
        if (oldName != null && newName != null && oldName != newName) what += "now calls itself \"${now.name}\" (was \"${before.name}\")"
        return if (what.isEmpty()) null else Event.Changed(what)
    }

    /** The scan's result for the network [tag]: the events per key, and the states to keep. */
    data class Outcome(val events: Map<String, Event>, val states: List<State>)

    /**
     * Compares every watched device of [tag] with what a scan saw ([seen], by key) and returns the events and the updated
     * states: a device not seen is kept as absent, with what it last had; one seen takes its new ports (unless the port
     * stage was cut short) and name. Other networks' entries are left as they are.
     */
    fun afterScan(states: List<State>, tag: String, seen: Map<String, Now>, portsComplete: Boolean): Outcome {
        val events = LinkedHashMap<String, Event>()
        val out = states.map { s ->
            if (s.tag != tag) return@map s
            val now = seen[s.key]
            event(s, now, portsComplete)?.let { events[s.key] = it }
            if (now == null) {
                s.copy(present = false)
            } else {
                s.copy(present = true, ports = if (portsComplete) now.ports.distinct().sorted() else s.ports, name = now.name ?: s.name)
            }
        }
        return Outcome(events, out)
    }

    /** The observation value of an event on [day]: `<day>;back`, or `<day>;changed:<what>,<what>` (each escaped). */
    fun observation(day: Long, event: Event): String = "$day;" + when (event) {
        Event.Back -> "back"
        is Event.Changed -> "changed:" + event.what.joinToString(",") { LookCloser.escape(it) }
    }

    fun parseObservation(value: String?): Noted? {
        val day = value?.substringBefore(';', "")?.toLongOrNull() ?: return null
        val rest = value.substringAfter(';')
        val event = when {
            rest == "back" -> Event.Back
            rest.startsWith("changed:") -> {
                val what = rest.removePrefix("changed:").split(',').map { LookCloser.unescape(it) ?: return null }.filter { it.isNotEmpty() }
                if (what.isEmpty()) return null
                Event.Changed(what)
            }
            else -> return null
        }
        return Noted(day, event)
    }
}
