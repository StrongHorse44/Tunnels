package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.Presence
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
     * address entry moves only to a host whose tokens are all router or direct names or its quiet address (`h`, `c`, `a`):
     * a device that announces nothing and became recognisable later, after a look closer or as a quiet device. A device that announces itself never takes over what
     * was noted under its address, which may have been another device's. A host whose key already holds an entry keeps it.
     */
    fun <T> rekey(notes: Map<String, T>, hosts: Collection<Host>): Map<String, T> {
        val out = LinkedHashMap(notes)
        for (h in hosts) {
            val key = h.key
            if (key in out || isAddressKey(key)) continue
            val quiet = h.ids.all { it.startsWith("h") || it.startsWith("c") || it.startsWith("a") }
            val from = h.ids.firstOrNull { it in out } ?: address(h.ip).takeIf { quiet && it in out } ?: continue
            out.remove(from)?.let { out[key] = it }
        }
        return out
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
