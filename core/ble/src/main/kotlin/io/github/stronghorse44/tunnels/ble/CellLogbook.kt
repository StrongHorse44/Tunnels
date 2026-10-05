package io.github.stronghorse44.tunnels.ble

/**
 * The cell logbook: which serving cells the phone uses at each place it returns to, kept as keyed hashes only
 * (CLAUDE.md rule 3, the hashed-set exception). Pure Kotlin: the Android side computes the keyed hashes
 * (HMAC under a Keystore key that never leaves it) and hands this code strings.
 *
 * What the row holds: per place a keyed hash of the ~250 m grid cell ([PlaceGrid]), a capped scan count, the best
 * radio rank seen there, and sets of keyed hashes of cells, tracking areas and operators. What it never holds: a
 * coordinate, a raw cell identity, a timestamp, an order of visits. Everything is written sorted by hash, and
 * eviction is by lowest scan count, never by age.
 */

/**
 * One registered cell as the modem reported it. **Memory only**: never stored, logged, put in an observation or an
 * event. Unavailable values are null. A cell without an [cellId] cannot be judged.
 */
data class ServingCell(
    val tech: CellTech,
    val mcc: String?,
    val mnc: String?,
    /** Tracking area code (NR, LTE) or location area code (UMTS, GSM). */
    val area: Long?,
    val cellId: Long?,
    val pci: Int?,
    val channel: Int?,
) {
    /** Text hashed for the cell: every field the modem gave, null as `?`. Starts `cell:v1|`, so it is never a grid-cell id. */
    fun cellInput(): String =
        "cell:v1|${tech.slug}|${mcc ?: "?"}|${mnc ?: "?"}|${area ?: "?"}|${cellId ?: "?"}|${pci ?: "?"}|${channel ?: "?"}"

    /** Text hashed for the tracking or location area; null unless the area and both operator codes are known. */
    fun areaInput(): String? {
        val kind = when (tech) {
            CellTech.NR, CellTech.LTE -> "TA"
            CellTech.UMTS, CellTech.GSM -> "LA"
            CellTech.UNKNOWN -> return null
        }
        if (area == null || mcc == null || mnc == null) return null
        return "area:v1|$kind|$mcc|$mnc|$area"
    }

    /** Text hashed for the operator; null unless both codes are known. */
    fun operatorInput(): String? = if (mcc == null || mnc == null) null else "op:v1|$mcc|$mnc"
}

/**
 * A serving cell's three keyed hashes (32 lowercase hex each) and its technology's rank. Built from a [ServingCell];
 * holds no cell identity. [tech] and [operatorCode] (`310-260`) are what a cell check already keeps; they ride along in
 * memory so a finding can name the judged cell's own technology and operator (never written to the row).
 */
data class CellTokens(val cell: String, val area: String?, val operator: String?, val rank: Int, val tech: CellTech? = null, val operatorCode: String? = null)

/** One place: the keyed hash of its anchor grid cell, how often it was scanned (1-99), the best rank seen, and what the phone used there. */
data class PlaceEntry(
    val place: String,
    val scans: Int,
    val bestRank: Int,
    val cells: Set<String> = emptySet(),
    val areas: Set<String> = emptySet(),
    val operators: Set<String> = emptySet(),
)

/** An unfamiliar tower waiting for "Normal here": not learned until the user says so (or it turns up again without a second signal). */
data class HeldTower(val place: String, val cell: String, val area: String?, val operator: String?) {
    val towerId: String get() = cell.take(CellLog.TOWER_ID_LENGTH)
}

enum class Signal(val slug: String) { AREA("area"), DOWNGRADE("downgrade"), OPERATOR("operator") }

/** In order of how much it should be said: the worst verdict of several cells is the highest. */
enum class TowerVerdict(val slug: String) {
    LEARNING("learning"),
    FAMILIAR("familiar"),
    NEW_NORMAL("new-normal"),
    UNFAMILIAR("unfamiliar"),
    ;

    companion object {
        fun bySlug(slug: String?): TowerVerdict? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * What one scan found. [signals] is only filled for [TowerVerdict.UNFAMILIAR] and [towerId] only then too.
 * [placeScans] and [placeCells] are the place's counts after this scan, [places] the book's.
 */
data class CellJudgement(
    val verdict: TowerVerdict,
    val signals: Set<Signal>,
    val towerId: String?,
    val placeScans: Int,
    val placeCells: Int,
    val places: Int = 0,
    /** The book was started again because the Keystore key changed. */
    val restarted: Boolean = false,
    /** The judged cell's own technology and operator (`310-260`), only for [TowerVerdict.UNFAMILIAR], so the finding names the right cell on a dual SIM. */
    val tech: CellTech? = null,
    val operatorCode: String? = null,
)

/** The logbook row. [keyId] says which Keystore key made the hashes. */
data class CellLogbook(val keyId: String, val places: List<PlaceEntry> = emptyList(), val held: List<HeldTower> = emptyList()) {
    /**
     * The row's text, canonical: every line sorted, every hash 32 lowercase hex. Fails closed: a book over a cap or
     * with a value out of range throws IllegalStateException and nothing is written (nothing is cut down silently;
     * [CellLog.observe] and [CellLog.accept] keep a book within its caps themselves).
     */
    fun encode(): String {
        check(CellLog.isHash(keyId, 8)) { "key id" }
        check(places.size <= CellLog.MAX_PLACES) { "too many places" }
        check(places.map { it.place }.toSet().size == places.size) { "duplicate place" }
        check(held.size <= CellLog.MAX_HELD) { "too many held towers" }
        check(held.map { it.cell }.toSet().size == held.size) { "duplicate held tower" }
        val known = places.mapTo(HashSet()) { it.place }
        for (p in places) {
            check(CellLog.isHash(p.place) && p.scans in 1..CellLog.MAX_SCANS && p.bestRank in 0..CellLog.MAX_RANK) { "place entry" }
            check(p.cells.size <= CellLog.MAX_CELLS && p.areas.size <= CellLog.MAX_AREAS && p.operators.size <= CellLog.MAX_OPERATORS) { "place set over its cap" }
            check((p.cells + p.areas + p.operators).all { CellLog.isHash(it) }) { "not a hash" }
        }
        for (h in held) {
            check(h.place in known && CellLog.isHash(h.place) && CellLog.isHash(h.cell)) { "held tower" }
            check((h.area == null || CellLog.isHash(h.area)) && (h.operator == null || CellLog.isHash(h.operator))) { "held tower" }
        }
        return buildString {
            append(CellLog.MAGIC).append('\n')
            append("kid ").append(keyId).append('\n')
            for (p in places.sortedBy { it.place }) {
                append("P ").append(p.place).append(' ').append(p.scans).append(' ').append(p.bestRank).append(' ')
                append(list(p.cells)).append(' ').append(list(p.areas)).append(' ').append(list(p.operators)).append('\n')
            }
            for (h in held.sortedBy { it.cell }) {
                append("H ").append(h.place).append(' ').append(h.cell).append(' ').append(h.area ?: "-").append(' ').append(h.operator ?: "-").append('\n')
            }
        }
    }

    private fun list(set: Set<String>): String = if (set.isEmpty()) "-" else set.sorted().joinToString(",")

    companion object {
        /**
         * The book [raw] holds, or null when it is anything else: wrong header, a line type it does not know, a hash
         * that is not 32 lowercase hex, a duplicate, a count out of range, a place over a cap, or text that is not
         * exactly what [encode] writes. A null is **unreadable** and the caller must never overwrite the row.
         */
        fun decode(raw: String): CellLogbook? {
            if (raw.length > CellLog.MAX_ROW_CHARS || !raw.endsWith("\n")) return null
            val lines = raw.substring(0, raw.length - 1).split('\n')
            if (lines.size < 2 || lines[0] != CellLog.MAGIC) return null
            if (!lines[1].startsWith("kid ")) return null
            val kid = lines[1].substring(4)
            if (!KID.matches(kid)) return null
            val places = ArrayList<PlaceEntry>()
            val held = ArrayList<HeldTower>()
            for (line in lines.drop(2)) {
                val f = line.split(' ')
                when (f[0]) {
                    "P" -> {
                        if (f.size != 7 || held.isNotEmpty() || !HASH.matches(f[1])) return null
                        val scans = int(f[2], 1, CellLog.MAX_SCANS) ?: return null
                        val rank = int(f[3], 0, CellLog.MAX_RANK) ?: return null
                        places += PlaceEntry(f[1], scans, rank, set(f[4], CellLog.MAX_CELLS) ?: return null, set(f[5], CellLog.MAX_AREAS) ?: return null, set(f[6], CellLog.MAX_OPERATORS) ?: return null)
                    }
                    "H" -> {
                        if (f.size != 5 || !HASH.matches(f[1]) || !HASH.matches(f[2])) return null
                        if (!(f[3] == "-" || HASH.matches(f[3])) || !(f[4] == "-" || HASH.matches(f[4]))) return null
                        held += HeldTower(f[1], f[2], f[3].takeIf { it != "-" }, f[4].takeIf { it != "-" })
                    }
                    else -> return null
                }
                if (places.size > CellLog.MAX_PLACES || held.size > CellLog.MAX_HELD) return null
            }
            if (places.map { it.place }.toSet().size != places.size) return null
            if (held.map { it.cell }.toSet().size != held.size) return null
            val known = places.mapTo(HashSet()) { it.place }
            if (held.any { it.place !in known }) return null
            val book = CellLogbook(kid, places, held)
            // Sorted, no stray spaces or zeros: only text the encoder writes is a readable row.
            return book.takeIf { runCatching { it.encode() }.getOrNull() == raw }
        }

        private val HASH = Regex("[0-9a-f]{32}")
        private val KID = Regex("[0-9a-f]{8}")

        private fun int(s: String, min: Int, max: Int): Int? =
            if (s.isNotEmpty() && s.length <= 2 && s.all { it in '0'..'9' }) s.toInt().takeIf { it in min..max } else null

        /** `-` is an empty set; otherwise comma-separated hashes, within [cap]. */
        private fun set(s: String, cap: Int): Set<String>? {
            if (s == "-") return emptySet()
            val parts = s.split(',')
            if (parts.size > cap || parts.any { !HASH.matches(it) }) return null
            val set = parts.toSet()
            return set.takeIf { it.size == parts.size }
        }
    }
}

/** The logbook's rules: caps, the familiarity threshold, and the judgement of a scan's serving cells against a place's sets. */
object CellLog {
    const val MAGIC = "CLOG1"
    const val MAX_PLACES = 64
    const val MAX_CELLS = 48
    const val MAX_AREAS = 16
    const val MAX_OPERATORS = 8
    const val MAX_HELD = 8
    const val MAX_SCANS = 99
    const val MAX_RANK = 4

    /** Scans of a place before an unseen cell there is judged. Fewer, and the logbook is still learning it. */
    const val FAMILIAR_SCANS = 4
    const val TOWER_ID_LENGTH = 8

    /** A row longer than this is refused before it is parsed (64 full places are about 160 000 characters). */
    const val MAX_ROW_CHARS = 200_000

    private val HASH = Regex("[0-9a-f]{32}")
    private val KID = Regex("[0-9a-f]{8}")

    /** True when [s] is [length] lowercase hex characters: 32 for a keyed hash, 8 for a key id. */
    fun isHash(s: String, length: Int = 32): Boolean = (if (length == 8) KID else HASH).matches(s)

    /** The tokens of the cells in [cells] that have an id, [hmac] being the keyed hash (32 lowercase hex). Cells without an id cannot be judged and are left out. */
    fun tokensOf(cells: List<ServingCell>, hmac: (String) -> String): List<CellTokens> =
        cells.filter { it.cellId != null }
            .map {
                val code = if (it.mcc != null && it.mnc != null) "${it.mcc}-${it.mnc}" else null
                CellTokens(hmac(it.cellInput()), it.areaInput()?.let(hmac), it.operatorInput()?.let(hmac), it.tech.rank, it.tech, code)
            }
            .distinctBy { it.cell }

    /**
     * Judges one scan and returns the book to keep. [block] is the scan's place: the keyed hashes of its grid cell
     * and the eight around it, its own first. [cells] are the registered cells' tokens (at least one, see [tokensOf]);
     * [previousRank] is the rank of the technology the previous scan's cell check saw (0 when there was none).
     *
     * A place is learning until it has been scanned [FAMILIAR_SCANS] times: everything seen is learned and nothing is
     * judged. After that a cell not in the place's set is learned silently when nothing else about it is new, and
     * **held, not learned**, when a second signal comes with it: a tracking area or an operator the place has not
     * had, or a drop to 3G or 2G (from the best rank the place had, or from the previous scan). A held tower is
     * learned by [accept] or when it turns up again without a signal.
     *
     * Throws IllegalArgumentException when an input is not a keyed hash, so a malformed value is never written.
     */
    fun observe(book: CellLogbook, keyId: String, block: List<String>, cells: List<CellTokens>, previousRank: Int): Pair<CellLogbook, CellJudgement> {
        require(KID.matches(keyId)) { "key id" }
        require(block.isNotEmpty() && block.all { HASH.matches(it) }) { "place block" }
        require(cells.isNotEmpty()) { "no cells to judge" }
        require(cells.all { HASH.matches(it.cell) && (it.area == null || HASH.matches(it.area)) && (it.operator == null || HASH.matches(it.operator)) }) { "cell tokens" }

        val restarted = book.keyId != keyId
        val start = if (restarted) CellLogbook(keyId) else book
        var held = start.held
        var others = start.places
        val found = block.firstNotNullOfOrNull { h -> others.firstOrNull { it.place == h } }
        val entry0 = if (found != null) {
            others = others.filter { it.place != found.place }
            found
        } else {
            if (others.size >= MAX_PLACES) {
                val victim = others.minWith(compareBy<PlaceEntry> { it.scans }.thenBy { it.place })
                others = others.filter { it.place != victim.place }
                held = held.filter { it.place != victim.place }
            }
            PlaceEntry(block[0], 0, 0)
        }

        val familiar = entry0.scans >= FAMILIAR_SCANS
        var cellSet = entry0.cells
        var areaSet = entry0.areas
        var operatorSet = entry0.operators
        var best = entry0.bestRank
        val results = ArrayList<Triple<TowerVerdict, Set<Signal>, CellTokens>>()

        fun learn(c: CellTokens) {
            if (cellSet.size < MAX_CELLS) cellSet = cellSet + c.cell
            if (c.area != null && areaSet.size < MAX_AREAS) areaSet = areaSet + c.area
            if (c.operator != null && operatorSet.size < MAX_OPERATORS) operatorSet = operatorSet + c.operator
            best = maxOf(best, c.rank)
            held = held.filter { it.cell != c.cell }
        }

        // Signals are read against the place as it was when the scan began, so several registered cells (dual SIM) do not shape each other's judgement.
        for (c in cells.sortedBy { it.cell }) {
            val signals = sortedSetOf<Signal>()
            if (c.area != null && c.area !in entry0.areas) signals += Signal.AREA
            if (c.operator != null && c.operator !in entry0.operators) signals += Signal.OPERATOR
            if (c.rank in 1..CellTech.UMTS.rank && (entry0.bestRank >= CellTech.LTE.rank || isDrop(previousRank, c.rank))) signals += Signal.DOWNGRADE
            val verdict = when {
                !familiar -> TowerVerdict.LEARNING.also { learn(c) }
                c.cell in entry0.cells -> TowerVerdict.FAMILIAR
                signals.isEmpty() -> TowerVerdict.NEW_NORMAL.also { learn(c) }
                else -> TowerVerdict.UNFAMILIAR.also {
                    held = held.filter { h -> h.cell != c.cell }
                    if (held.size >= MAX_HELD) held = held.minus(held.minWith(compareBy<HeldTower> { it.towerId }.thenBy { it.cell }))
                    held = held + HeldTower(entry0.place, c.cell, c.area, c.operator)
                }
            }
            results += Triple(verdict, signals, c)
        }

        val entry = entry0.copy(scans = minOf(entry0.scans + 1, MAX_SCANS), bestRank = best, cells = cellSet, areas = areaSet, operators = operatorSet)
        val worst = results.maxBy { it.first.ordinal }
        val unfamiliar = worst.first == TowerVerdict.UNFAMILIAR
        val next = CellLogbook(keyId, others + entry, held)
        val judgement = CellJudgement(
            verdict = worst.first,
            signals = if (unfamiliar) worst.second else emptySet(),
            towerId = if (unfamiliar) worst.third.cell.take(TOWER_ID_LENGTH) else null,
            placeScans = entry.scans,
            placeCells = entry.cells.size,
            places = next.places.size,
            restarted = restarted,
            tech = if (unfamiliar) worst.third.tech else null,
            operatorCode = if (unfamiliar) worst.third.operatorCode else null,
        )
        return next to judgement
    }

    /** True when the phone fell from 4G/5G ([previousRank] 3 or 4) to a lower rank: [CellHeuristics.isDowngrade] on ranks. */
    private fun isDrop(previousRank: Int, rank: Int): Boolean = previousRank >= CellTech.LTE.rank && rank < previousRank

    /** What [accept] did: the book to keep, and whether the tower went into its place's list ([learned] false: the list was full). */
    data class Accepted(val book: CellLogbook, val learned: Boolean)

    /**
     * "Normal here": moves the held tower [towerId] into its place's sets and drops the hold. Returns null when no
     * such tower is held. When the place's cell list is already at its cap nothing is added: the hold is dropped and
     * [Accepted.learned] is false, so the caller can say so.
     */
    fun accept(book: CellLogbook, towerId: String): Accepted? {
        val tower = book.held.filter { it.towerId == towerId }.minByOrNull { it.cell } ?: return null
        var learned = false
        val places = book.places.map {
            if (it.place != tower.place) it else {
                learned = it.cells.size < MAX_CELLS || tower.cell in it.cells
                it.copy(
                    cells = if (it.cells.size < MAX_CELLS) it.cells + tower.cell else it.cells,
                    areas = if (tower.area != null && it.areas.size < MAX_AREAS) it.areas + tower.area else it.areas,
                    operators = if (tower.operator != null && it.operators.size < MAX_OPERATORS) it.operators + tower.operator else it.operators,
                )
            }
        }
        return Accepted(book.copy(places = places, held = book.held.filter { it.cell != tower.cell }), learned)
    }
}
