package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.dns.BlockPolicy
import io.github.stronghorse44.tunnels.dns.Upstream
import io.github.stronghorse44.tunnels.pairing.Pin
import io.github.stronghorse44.tunnels.watchrules.WatchSettings

/**
 * The small records of a Tunnels bundle besides snapshots, in their text forms, with the checks an import applies
 * to each. Nothing here trusts the file: every value is decoded by the code that owns it and written back in that
 * code's own canonical encoding, so what reaches the store is never the file's text.
 */
object BundleSettings {
    /**
     * The settings-table keys a bundle carries: Traffic's blocking and upstream choices and the background-check
     * choices. Left out on purpose: `watch.status` and `inbox.lastVisit` (this phone's history, not a choice) and
     * `pairing.verifierId` (this phone's identity; a restore must not give two phones the same one). The pairing
     * pins have their own entry. The app lock switch is a per-phone choice kept outside the store and is not carried.
     */
    val KEYS: List<String> = listOf(BlockPolicy.KEY, Upstream.KEY, WatchSettings.KEY)

    /** The settings-table key that holds the paired phones; they travel in their own entry and are merged, not replaced. */
    const val PINS_KEY = Pin.KEY

    private const val SETTINGS_HEADER = "TSET1"
    const val MAX_PINS = 1_000
    const val MAX_NETWORKS = 10_000
    private val NETWORK = Regex("[0-9a-f]{64}")

    /** [raw] as its owner would store it, or null for a key a bundle does not carry. */
    fun canonical(key: String, raw: String): String? = when (key) {
        BlockPolicy.KEY -> BlockPolicy.decode(raw).encode()
        Upstream.KEY -> Upstream.decode(raw).encode()
        WatchSettings.KEY -> WatchSettings.decode(raw).encode()
        else -> null
    }

    /** The carried keys of [raw] (key to stored text; keys never set are null or absent), canonical. */
    fun canonicalSettings(raw: Map<String, String?>): Map<String, String> =
        KEYS.mapNotNull { k -> raw[k]?.let { v -> canonical(k, v)?.let { k to it } } }.toMap()

    fun encodeSettings(settings: Map<String, String>): String = buildString {
        append(SETTINGS_HEADER).append('\n')
        for (k in KEYS) settings[k]?.let { append(k).append('\t').append(BundleFormat.escape(it)).append('\n') }
    }

    /** Strict: the header, then `key<TAB>value` rows for known keys, each at most once. */
    fun parseSettings(text: String): Map<String, String> {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.firstOrNull() != SETTINGS_HEADER) throw BundleFormatException("Settings entry: missing header")
        if (lines.size - 1 > KEYS.size) throw BundleFormatException("Settings entry: too many rows")
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val tab = line.indexOf('\t')
            if (tab <= 0) throw BundleFormatException("Settings entry: malformed row")
            val key = line.substring(0, tab)
            if (key !in KEYS) throw BundleFormatException("Settings entry: unknown key")
            if (key in out) throw BundleFormatException("Settings entry: duplicate key")
            out[key] = canonical(key, BundleFormat.unescape(line.substring(tab + 1)))!!
        }
        return KEYS.mapNotNull { k -> out[k]?.let { k to it } }.toMap()
    }

    // Pairing pins

    /** [raw] (the stored pins text, possibly null) as one pin per line, sorted by id, one per id, damaged lines dropped. */
    fun canonicalPins(raw: String?): String = Pin.encode(distinctPins(Pin.decode(raw)))

    /** Strict: every line must be a well-formed pin, ids unique, at most [MAX_PINS]. Returns the canonical text. */
    fun parsePins(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size > MAX_PINS) throw BundleFormatException("More than $MAX_PINS paired phones")
        val pins = Pin.decode(text)
        if (pins.size != lines.size) throw BundleFormatException("A paired phone's record is damaged")
        if (pins.map { it.id }.toSet().size != pins.size) throw BundleFormatException("A paired phone appears twice")
        return Pin.encode(distinctPins(pins))
    }

    private fun distinctPins(pins: List<Pin>): List<Pin> =
        pins.groupBy { it.id }.values.map { same -> same.maxByOrNull { it.lastAuditAt } ?: same.first() }.sortedBy { it.id }

    /**
     * The pins text after an import: what the phone has, plus the bundle's pins, and where both have the same phone
     * the one audited more recently. Returns the new text and how many phones were new.
     */
    fun mergePins(existing: String?, incoming: String): Pair<String, Int> {
        val have = Pin.decode(existing)
        val byId = LinkedHashMap<String, Pin>()
        have.forEach { byId[it.id] = it }
        var added = 0
        for (p in Pin.decode(incoming)) {
            val current = byId[p.id]
            if (current == null) {
                byId[p.id] = p
                added++
            } else if (p.lastAuditAt.isAfter(current.lastAuditAt)) {
                byId[p.id] = p
            }
        }
        return Pin.encode(byId.values.toList()) to added
    }

    // Confirmed networks

    fun encodeNetworks(networks: Set<String>): String = networks.sorted().joinToString("") { it + "\n" }

    /** Strict: one lowercase SHA-256 hex per line, unique, at most [MAX_NETWORKS]. */
    fun parseNetworks(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            if (!NETWORK.matches(line)) throw BundleFormatException("Networks entry: a line is not a SHA-256")
            if (!out.add(line)) throw BundleFormatException("Networks entry: a network appears twice")
            if (out.size > MAX_NETWORKS) throw BundleFormatException("More than $MAX_NETWORKS networks")
        }
        return out
    }

    /** Only well-formed hashes of what the phone holds; anything else in the preferences file is not carried. */
    fun validNetworks(held: Collection<String>): Set<String> = held.filter { NETWORK.matches(it) }.toSet()
}
