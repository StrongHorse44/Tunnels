package io.github.stronghorse44.tunnels.ble

import kotlin.math.cos
import kotlin.math.floor

/**
 * Whether the phone moved between scans, without keeping where it was.
 *
 * A position fix is snapped to a grid of cells about [CELL_METERS] across. The current place has an anchor
 * cell; a scan is at that place while its cell is the anchor or one of the anchor's eight neighbours (a block
 * about 750 m across), and at a new place once it is further: at least [CELL_METERS] and usually about 500 m
 * away. Each new place gets the next number and sightings carry that number, so two sightings with consecutive
 * numbers sit on both sides of a move. Bluetooth reaches tens of metres, so a tag heard on both sides of a move
 * travelled too.
 *
 * Stored: the current place's number and a keyed hash of its anchor cell ([PlaceAnchor]), one row, replaced on
 * every scan. The key lives in the phone's keystore and never leaves it, so the hash cannot be turned back into
 * a cell anywhere else. No coordinates, no history of places.
 */
object PlaceGrid {
    const val CELL_METERS = 250.0

    /** Fixes less accurate than this give no place: a wrong move would be worse than none. */
    const val MAX_ACCURACY_METERS = 100f

    /** Longitude steps are fixed within bands this many degrees tall, so neighbouring rows of a band line up. */
    private const val BAND_DEGREES = 5.0
    private const val METERS_PER_DEGREE = 111_320.0

    /** A grid cell; [id] is what gets hashed. */
    data class Cell(val band: Int, val x: Long, val y: Long) {
        val id: String get() = "$band:$x:$y"
    }

    fun cellOf(latitude: Double, longitude: Double): Cell {
        val y = floor(latitude * METERS_PER_DEGREE / CELL_METERS).toLong()
        val band = floor(latitude / BAND_DEGREES).toInt()
        val bandCentre = (band + 0.5) * BAND_DEGREES
        val metersPerDegreeLon = METERS_PER_DEGREE * cos(Math.toRadians(bandCentre)).coerceAtLeast(0.05)
        val x = floor(longitude * metersPerDegreeLon / CELL_METERS).toLong()
        return Cell(band, x, y)
    }

    /** [cell] first, then its eight neighbours: a fix in any of them is at the same place as an anchor in [cell]. */
    fun block(cell: Cell): List<Cell> =
        listOf(cell) + (-1L..1L).flatMap { dy -> (-1L..1L).map { dx -> Cell(cell.band, cell.x + dx, cell.y + dy) } }.filter { it != cell }
}

/** The current place: its number and the keyed hash of its anchor cell. The only place data that is stored. */
data class PlaceAnchor(val place: Long, val anchorHash: String) {
    fun encode(): String = "place=$place;anchor=$anchorHash"

    companion object {
        fun parse(summary: String): PlaceAnchor? {
            val fields = summary.split(';').mapNotNull { f ->
                val i = f.indexOf('=')
                if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
            }.toMap()
            val place = fields["place"]?.toLongOrNull() ?: return null
            val hash = fields["anchor"]?.takeIf { it.isNotEmpty() } ?: return null
            return PlaceAnchor(place, hash)
        }
    }
}

object PlaceTracking {
    /**
     * The place a scan is at. [blockHashes] are the keyed hashes of the scan's cell and its neighbours, the cell's
     * own first ([PlaceGrid.block] order); [stored] is the current anchor, if any; [firstPlace] numbers the first
     * place when nothing is stored. Returns the scan's place with the anchor to store from now on.
     */
    fun next(stored: PlaceAnchor?, blockHashes: List<String>, firstPlace: () -> Long): PlaceAnchor {
        require(blockHashes.isNotEmpty()) { "the scan's own cell comes first" }
        return when {
            stored == null -> PlaceAnchor(firstPlace(), blockHashes.first())
            stored.anchorHash in blockHashes -> stored
            else -> PlaceAnchor(stored.place + 1, blockHashes.first())
        }
    }

    /**
     * A first place number when nothing is stored (a fresh install, or 30 days without a scan): the current minute.
     * Place numbers only go up by one per move from there, so a later restart lands far above every number still
     * in the 30-day history and is never mistaken for the place after one of them.
     */
    fun firstPlace(nowMs: Long): Long = nowMs / 60_000
}

/** How one tracker identity's sightings relate to the phone's moves. */
enum class Movement(val slug: String) {
    /** Seen on both sides of at least one move: it travelled with the phone. */
    MOVED("moved"),

    /** Seen in [FollowingHeuristic.MIN_SESSIONS] or more scans with a known place, never across a move: it stays put. */
    STAYED("stayed"),

    /** Too few of its scans had a place (location off, no fix) to tell. */
    UNKNOWN("unknown");

    companion object {
        fun bySlug(slug: String?): Movement = entries.firstOrNull { it.slug == slug } ?: UNKNOWN

        /**
         * [placeRun] is the longest run of consecutive place numbers the identity was seen at, [placedSessions] how
         * many of its scans had a place at all.
         */
        fun of(placeRun: Int, placedSessions: Int): Movement = when {
            placeRun >= 2 -> MOVED
            placedSessions >= FollowingHeuristic.MIN_SESSIONS -> STAYED
            else -> UNKNOWN
        }

        /** The longest run of consecutive numbers in [places]: 3 for {5, 6, 7}, 1 for {5, 7}, 0 when empty. */
        fun longestRun(places: Collection<Long>): Int {
            var best = 0
            var run = 0
            var previous: Long? = null
            for (p in places.toSortedSet()) {
                run = if (previous != null && p == previous + 1) run + 1 else 1
                if (run > best) best = run
                previous = p
            }
            return best
        }
    }
}
