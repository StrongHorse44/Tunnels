package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** The cell logbook over synthetic cell sequences. The keyed hash is stood in for by SHA-256 with a fixed prefix. */
class CellLogbookTest {
    private val kid = "0a1b2c3d"

    private fun hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(("test-key|$s").toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /** A place's block: its own grid cell first, then eight neighbours. [n] numbers the place; [shared] swaps the neighbour at index 3 for another place's anchor. */
    private fun block(n: Int, shared: Int? = null): List<String> =
        (0 until 9).map { hex("grid:$n:$it") }.toMutableList().also { if (shared != null) it[3] = hex("grid:$shared:0") }

    private fun lte(id: Long, area: Long? = 100, mnc: String? = "260", mcc: String? = "310") =
        ServingCell(CellTech.LTE, mcc, mnc, area, id, 7, 5230)

    private fun umts(id: Long, area: Long? = 100) = ServingCell(CellTech.UMTS, "310", "260", area, id, 12, 4357)
    private fun gsm(id: Long) = ServingCell(CellTech.GSM, "310", "260", 100, id, 3, 62)

    private fun tokens(vararg cells: ServingCell) = CellLog.tokensOf(cells.toList(), ::hex)

    private fun scan(book: CellLogbook, block: List<String>, vararg cells: ServingCell, previousRank: Int = 0, key: String = kid) =
        CellLog.observe(book, key, block, tokens(*cells), previousRank)

    /** A book that has [scans] scans of the place, all on [cells]. */
    private fun learned(n: Int = 1, scans: Int = 4, vararg cells: ServingCell = arrayOf(lte(1000)), start: CellLogbook = CellLogbook(kid)): CellLogbook {
        var book = start
        repeat(scans) { book = scan(book, block(n), *cells).first }
        return book
    }

    private fun place(book: CellLogbook, n: Int = 1) = book.places.single { it.place == block(n)[0] }

    // Learning and judging

    @Test
    fun firstVisitsLearnWithoutFindingsEvenWithSignals() {
        var book = CellLogbook(kid)
        val seq = listOf(
            lte(1000),
            lte(2000, area = 200), // a new tracking area
            umts(3000), // a drop to 3G from the place's best
            lte(4000, mnc = "480"), // a new operator
        )
        seq.forEachIndexed { i, c ->
            val (next, j) = scan(book, block(1), c)
            assertEquals("scan ${i + 1}", TowerVerdict.LEARNING, j.verdict)
            assertTrue(j.signals.isEmpty())
            assertNull(j.towerId)
            assertEquals(i + 1, j.placeScans)
            book = next
        }
        assertEquals(4, place(book).cells.size)
        assertTrue(book.held.isEmpty())
        assertEquals(setOf(Signal.AREA), signalsOf(learned(scans = 4), lte(2000, area = 200)))
    }

    private fun signalsOf(book: CellLogbook, cell: ServingCell, previousRank: Int = 0): Set<Signal> {
        val (_, j) = scan(book, block(1), cell, previousRank = previousRank)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        return j.signals
    }

    @Test
    fun normalHandoverAtAFamiliarPlaceIsLearnedSilently() {
        val book = learned()
        val (next, j) = scan(book, block(1), lte(1001))
        assertEquals(TowerVerdict.NEW_NORMAL, j.verdict)
        assertTrue(j.signals.isEmpty())
        assertNull(j.towerId)
        assertEquals(2, place(next).cells.size)
        assertTrue(next.held.isEmpty())
        assertEquals(TowerVerdict.FAMILIAR, scan(next, block(1), lte(1001)).second.verdict)
    }

    @Test
    fun unfamiliarCellWithNewAreaRaises() {
        val b = lte(1001, area = 999)
        val (next, j) = scan(learned(), block(1), b)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(setOf(Signal.AREA), j.signals)
        assertEquals(tokens(b).single().cell.take(8), j.towerId)
    }

    @Test
    fun unfamiliarCellWithDowngradeRaises() {
        // The place has had LTE; a new 3G cell there is a drop from the place's best.
        // (Without a known area, so the drop is the only new thing; a 3G area is a different area from a 4G one.)
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(learned(), umts(1001, area = null)))
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(learned(), gsm(1001).copy(area = null)))
        assertEquals(setOf(Signal.AREA, Signal.DOWNGRADE), signalsOf(learned(), umts(1001)))
    }

    @Test
    fun downgradeAgainstThePreviousScanCounts() {
        // A place only ever seen on 3G: a new 3G cell is normal, unless the scan before was on 4G.
        val threeG = learned(cells = arrayOf(umts(1000)))
        assertEquals(2, place(threeG).bestRank)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), umts(1001), previousRank = 2).second.verdict)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), umts(1001), previousRank = 0).second.verdict)
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(threeG, umts(1001), previousRank = 3))
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(threeG, umts(1001), previousRank = 4))
        // Going up is no drop.
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), lte(1001, area = null), previousRank = 2).second.verdict)
    }

    @Test
    fun aDropCountsOnlyFromLteOrBetter() {
        // A place seen only on 3G, so the place's own best rank gives no signal. A new 2G cell after a 3G scan is
        // a step down, but not from 4G/5G: no downgrade signal. After a 4G scan it is one.
        val threeG = learned(cells = arrayOf(umts(1000)))
        val g = gsm(1001)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), g, previousRank = 2).second.verdict)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), g, previousRank = 1).second.verdict)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(threeG, block(1), g, previousRank = 0).second.verdict)
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(threeG, g, previousRank = 3))
        assertEquals(setOf(Signal.DOWNGRADE), signalsOf(threeG, g, previousRank = 4))
        // An unknown technology (rank 0) is never a downgrade, even after 4G.
        val unknown = ServingCell(CellTech.UNKNOWN, "310", "260", null, 1002, null, null)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(learned(), block(1), unknown, previousRank = 4).second.verdict)
    }

    @Test
    fun unfamiliarCellWithNewOperatorRaises() {
        // With the tracking area unknown the operator is the only new thing.
        assertEquals(setOf(Signal.OPERATOR), signalsOf(learned(), lte(1001, area = null, mnc = "480")))
        // With the area known, the area hash carries the operator codes, so it is new too.
        assertEquals(setOf(Signal.AREA, Signal.OPERATOR), signalsOf(learned(), lte(1001, mnc = "480")))
    }

    @Test
    fun unknownAreaOrOperatorIsNoSignal() {
        val c = lte(1001, area = null, mcc = null, mnc = null)
        val t = tokens(c).single()
        assertNull(t.area)
        assertNull(t.operator)
        val (_, j) = scan(learned(), block(1), c)
        assertEquals(TowerVerdict.NEW_NORMAL, j.verdict)
        // Unknown codes do not make an area either.
        assertNull(tokens(lte(1002, area = 5, mcc = "310", mnc = null)).single().area)
    }

    @Test
    fun unfamiliarTowerIsHeldNotLearned() {
        val b = lte(1001, area = 999)
        val (next, _) = scan(learned(), block(1), b)
        assertFalse(tokens(b).single().cell in place(next).cells)
        assertFalse(tokens(b).single().area in place(next).areas)
        assertEquals(1, next.held.size)
        assertEquals(block(1)[0], next.held[0].place)
        // Again with its signals: still unfamiliar, still one hold, still not learned.
        val (again, j) = scan(next, block(1), b)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(1, again.held.size)
        assertEquals(1, place(again).cells.size)
        // Only the lowest of the held towers goes when 8 are held.
        var book = learned()
        val ids = (0 until 10).map { lte(2000L + it, area = 900L + it) }
        ids.forEach { book = scan(book, block(1), it).first }
        assertEquals(CellLog.MAX_HELD, book.held.size)
        val newest = tokens(ids.last()).single().cell
        assertTrue(book.held.any { it.cell == newest })
        assertEquals(book.held.map { it.cell }.toSet().size, book.held.size)
        // Exactly which one goes: with 8 held, the next unfamiliar tower pushes out the one with the lowest tower id.
        var eight = learned()
        (0 until CellLog.MAX_HELD).forEach { i -> eight = scan(eight, block(1), lte(3000L + i, area = 800L + i)).first }
        assertEquals(CellLog.MAX_HELD, eight.held.size)
        val lowest = eight.held.minWith(compareBy<HeldTower> { it.towerId }.thenBy { it.cell })
        val ninth = lte(4000, area = 850)
        val after = scan(eight, block(1), ninth).first
        assertEquals(CellLog.MAX_HELD, after.held.size)
        assertFalse(after.held.any { it.cell == lowest.cell })
        assertEquals((eight.held.map { it.cell }.toSet() - lowest.cell) + tokens(ninth).single().cell, after.held.map { it.cell }.toSet())
        // A tower already held is replaced in place, and nothing is pushed out.
        val replaced = scan(eight, block(1), lte(3003, area = 803)).first
        assertEquals(eight.held.map { it.cell }.toSet(), replaced.held.map { it.cell }.toSet())
    }

    @Test
    fun theJudgementNamesTheJudgedCellsOwnTechAndOperator() {
        // Dual SIM: a known 4G cell of one operator and an unfamiliar 3G cell of another. The notice is about the second.
        val own = lte(1000)
        val other = ServingCell(CellTech.UMTS, "262", "01", 55, 9001, 3, 10564)
        val (book, j) = scan(learned(), block(1), own, other)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(CellTech.UMTS, j.tech)
        assertEquals("262-01", j.operatorCode)
        val map = SurroundingsKeys.logObservations("on", j, book).associate { it.key to it.value }
        assertEquals("UMTS", map[SurroundingsKeys.LOG_TECH])
        assertEquals("262-01", map[SurroundingsKeys.LOG_OPERATOR])
        // Only for an unfamiliar verdict, and never in the row.
        assertNull(scan(learned(), block(1), own).second.tech)
        assertFalse(book.encode().contains("262"))
    }

    @Test
    fun acceptLearnsTheHeldTower() {
        val b = lte(1001, area = 999)
        val t = tokens(b).single()
        val (held, j) = scan(learned(), block(1), b)
        val result = CellLog.accept(held, j.towerId!!)!!
        assertTrue(result.learned)
        val accepted = result.book
        assertTrue(accepted.held.isEmpty())
        assertTrue(t.cell in place(accepted).cells)
        assertTrue(t.area in place(accepted).areas)
        assertEquals(TowerVerdict.FAMILIAR, scan(accepted, block(1), b).second.verdict)
        assertNull(CellLog.accept(accepted, j.towerId!!))
        assertNull(CellLog.accept(held, "00000000"))
    }

    @Test
    fun heldTowerSeenLaterWithoutSignalIsLearned() {
        val threeG = learned(cells = arrayOf(umts(1000)))
        val b = umts(1001)
        val (held, j) = scan(threeG, block(1), b, previousRank = 3)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(1, held.held.size)
        val (later, j2) = scan(held, block(1), b, previousRank = 2)
        assertEquals(TowerVerdict.NEW_NORMAL, j2.verdict)
        assertTrue(later.held.isEmpty())
        assertTrue(tokens(b).single().cell in place(later).cells)
    }

    @Test
    fun seenCellNeverRaisesEvenOn2G() {
        val book = learned(cells = arrayOf(lte(1000), gsm(2000)))
        assertEquals(3, place(book).bestRank)
        val (_, j) = scan(book, block(1), gsm(2000), previousRank = 3)
        assertEquals(TowerVerdict.FAMILIAR, j.verdict)
        assertTrue(j.signals.isEmpty())
    }

    @Test
    fun neighbouringGridCellFindsThePlace() {
        val book = learned(n = 1, scans = 2)
        // A scan whose own cell is another one, but whose block holds place 1's anchor, is at place 1.
        val (next, j) = scan(book, block(2, shared = 1), lte(1000))
        assertEquals(1, next.places.size)
        assertEquals(3, j.placeScans)
        // A block that holds none of the known anchors is a new place.
        val (other, j2) = scan(next, block(3), lte(1000))
        assertEquals(2, other.places.size)
        assertEquals(1, j2.placeScans)
        assertEquals(2, j2.places)
    }

    @Test
    fun twoRegisteredCellsReportTheWorstVerdict() {
        val book = learned()
        val known = lte(1000)
        val fresh = lte(1001) // new cell, nothing else new
        val odd = lte(1002, area = 999)
        assertEquals(TowerVerdict.NEW_NORMAL, scan(book, block(1), known, fresh).second.verdict)
        val (_, j) = scan(book, block(1), known, fresh, odd)
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(tokens(odd).single().cell.take(8), j.towerId)
        assertEquals(TowerVerdict.FAMILIAR, scan(book, block(1), known).second.verdict)
        // Order of the cells does not matter.
        assertEquals(j, scan(book, block(1), odd, fresh, known).second)
    }

    // Caps

    private fun filler(n: Int, count: Int) = (0 until count).map { hex("fill:$n:$it") }.toSet()

    @Test
    fun placeCapEvictsFewestScansNeverTheCurrentPlace() {
        val entries = (0 until CellLog.MAX_PLACES).map { PlaceEntry(hex("p$it"), 2 + it % 5, 3) }
        // The lowest scan count is 2; among those the lowest hash goes.
        val victim = entries.filter { it.scans == 2 }.minBy { it.place }
        val heldOfVictim = HeldTower(victim.place, hex("hold"), null, null)
        val book = CellLogbook(kid, entries, listOf(heldOfVictim))
        val (next, j) = scan(book, block(900), lte(1000))
        assertEquals(CellLog.MAX_PLACES, next.places.size)
        assertTrue(next.places.none { it.place == victim.place })
        assertTrue(next.places.any { it.place == block(900)[0] && it.scans == 1 })
        assertTrue(next.held.isEmpty())
        assertEquals(1, j.placeScans)
        // A scan at a place the book already has evicts nothing, even when that place has the fewest scans.
        val (same, _) = scan(book, listOf(victim.place) + block(901).drop(1), lte(1000))
        assertEquals(CellLog.MAX_PLACES, same.places.size)
        assertEquals(3, same.places.single { it.place == victim.place }.scans)
        // And the cut is by count, not age: the book never learns order, so the same book gives the same victim.
        assertEquals(next, scan(book, block(900), lte(1000)).first)
    }

    @Test
    fun fullPlaceSetsStopLearning() {
        val full = PlaceEntry(block(1)[0], 6, 3, filler(1, CellLog.MAX_CELLS), filler(2, CellLog.MAX_AREAS), filler(3, CellLog.MAX_OPERATORS))
        val book = CellLogbook(kid, listOf(full))
        // A familiar place: a new cell with a new area is judged as before, and nothing is added.
        val (next, j) = scan(book, block(1), lte(5000, area = 77))
        assertEquals(TowerVerdict.UNFAMILIAR, j.verdict)
        assertEquals(full, next.places.single().copy(scans = 6))
        // Learning places stop at the caps too.
        val young = CellLogbook(kid, listOf(full.copy(scans = 1)))
        val (next2, j2) = scan(young, block(1), lte(5001, area = 78))
        assertEquals(TowerVerdict.LEARNING, j2.verdict)
        val e = next2.places.single()
        assertEquals(CellLog.MAX_CELLS, e.cells.size)
        assertEquals(CellLog.MAX_AREAS, e.areas.size)
        assertEquals(CellLog.MAX_OPERATORS, e.operators.size)
        assertEquals(2, e.scans)
        // A quiet new cell at a full familiar place is normal, and is not added either.
        val (next3, j3) = scan(book, block(1), lte(5002, area = null, mcc = null, mnc = null))
        assertEquals(TowerVerdict.NEW_NORMAL, j3.verdict)
        assertEquals(CellLog.MAX_CELLS, next3.places.single().cells.size)
        // The row stays within its caps whatever was done to it.
        assertNotNull(CellLogbook.decode(next3.encode()))
    }

    @Test
    fun scanCountStopsAt99() {
        var book = learned(scans = 4)
        repeat(110) { book = scan(book, block(1), lte(1000)).first }
        assertEquals(99, place(book).scans)
        assertEquals(99, scan(book, block(1), lte(1000)).second.placeScans)
        assertNotNull(CellLogbook.decode(book.encode()))
    }

    @Test
    fun keyChangeRestartsTheLogbook() {
        val old = CellLogbook("ffffffff", listOf(PlaceEntry(hex("old"), 9, 3, setOf(hex("c")))), listOf(HeldTower(hex("old"), hex("t"), null, null)))
        val (next, j) = scan(old, block(1), lte(1000), key = kid)
        assertTrue(j.restarted)
        assertEquals(TowerVerdict.LEARNING, j.verdict)
        assertEquals(kid, next.keyId)
        assertEquals(1, next.places.size)
        assertEquals(block(1)[0], next.places[0].place)
        assertTrue(next.held.isEmpty())
        assertFalse(scan(next, block(1), lte(1000)).second.restarted)
    }

    @Test
    fun noCellIdIsNotJudged() {
        val none = lte(0).copy(cellId = null)
        assertTrue(tokens(none).isEmpty())
        assertEquals(1, tokens(none, lte(5)).size)
        try {
            CellLog.observe(CellLogbook(kid), kid, block(1), emptyList(), 0)
            fail("a scan with no cell id is not judged")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun malformedInputsAreRefusedBeforeAnythingIsWritten() {
        for (bad in listOf("", "XYZ", hex("a").uppercase(), hex("a").dropLast(1))) {
            try {
                CellLog.observe(CellLogbook(kid), kid, listOf(bad), tokens(lte(1)), 0)
                fail("block $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            CellLog.observe(CellLogbook(kid), "xyz", block(1), tokens(lte(1)), 0)
            fail("key id")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CellLog.observe(CellLogbook(kid), kid, block(1), listOf(CellTokens("nothex", null, null, 3)), 0)
            fail("token")
        } catch (_: IllegalArgumentException) {
        }
    }

    // Codec

    private fun sample(): CellLogbook {
        var book = CellLogbook(kid)
        for (n in listOf(5, 2, 9)) book = learned(n = n, scans = 4 + n, start = book)
        book = scan(book, block(2), lte(7000, area = 321)).first
        book = scan(book, block(9), umts(7001)).first
        return book
    }

    @Test
    fun roundTripIsCanonicalAndSorted() {
        val book = sample()
        val text = book.encode()
        val back = CellLogbook.decode(text)!!
        assertEquals(book.keyId, back.keyId)
        assertEquals(book.places.sortedBy { it.place }, back.places)
        assertEquals(book.held.sortedBy { it.cell }, back.held)
        assertEquals(text, back.encode())
        val lines = text.trimEnd('\n').split('\n')
        assertEquals("CLOG1", lines[0])
        assertEquals("kid $kid", lines[1])
        val ps = lines.filter { it.startsWith("P ") }.map { it.split(' ')[1] }
        assertEquals(ps.sorted(), ps)
        val hs = lines.filter { it.startsWith("H ") }.map { it.split(' ')[2] }
        assertEquals(hs.sorted(), hs)
        for (l in lines.filter { it.startsWith("P ") }) {
            val f = l.split(' ')
            assertEquals(l, 7, f.size)
            for (list in f.drop(4)) if (list != "-") assertEquals(list.split(',').sorted(), list.split(','))
        }
        // The empty book is a readable row; a book with the places in another order writes the same text.
        assertEquals("CLOG1\nkid $kid\n", CellLogbook(kid).encode())
        assertNotNull(CellLogbook.decode(CellLogbook(kid).encode()))
        assertEquals(text, book.copy(places = book.places.reversed(), held = book.held.reversed()).encode())
    }

    private fun raw(vararg lines: String) = lines.joinToString("\n", postfix = "\n")
    private fun p(place: String, scans: String = "4", rank: String = "3", cells: String = "-", areas: String = "-", ops: String = "-") = "P $place $scans $rank $cells $areas $ops"

    @Test
    fun malformedOrOverCapRowIsUnreadable() {
        val a = hex("a")
        val b = hex("b")
        val (lo, hi) = listOf(a, b).sorted()
        val good = raw("CLOG1", "kid $kid", p(lo, cells = lo), p(hi))
        assertNotNull(CellLogbook.decode(good))
        val bad = mapOf(
            "empty" to "",
            "no newline at end" to good.trimEnd('\n'),
            "carriage returns" to good.replace("\n", "\r\n"),
            "header" to good.replace("CLOG1", "CLOG2"),
            "no kid" to raw("CLOG1", p(lo)),
            "short kid" to raw("CLOG1", "kid 0a1b2c", p(lo)),
            "upper-case kid" to raw("CLOG1", "kid 0A1B2C3D", p(lo)),
            "two kids" to raw("CLOG1", "kid $kid", "kid $kid", p(lo)),
            "unknown line" to raw("CLOG1", "kid $kid", "X $lo"),
            "upper-case hash" to raw("CLOG1", "kid $kid", p(lo.uppercase())),
            "short hash" to raw("CLOG1", "kid $kid", p(lo.dropLast(1))),
            "duplicate place" to raw("CLOG1", "kid $kid", p(lo), p(lo)),
            "places out of order" to raw("CLOG1", "kid $kid", p(hi), p(lo)),
            "scans 0" to raw("CLOG1", "kid $kid", p(lo, scans = "0")),
            "scans 100" to raw("CLOG1", "kid $kid", p(lo, scans = "100")),
            "scans leading zero" to raw("CLOG1", "kid $kid", p(lo, scans = "04")),
            "scans negative" to raw("CLOG1", "kid $kid", p(lo, scans = "-4")),
            "rank 5" to raw("CLOG1", "kid $kid", p(lo, rank = "5")),
            "unsorted cells" to raw("CLOG1", "kid $kid", p(lo, cells = "$hi,$lo")),
            "duplicate cells" to raw("CLOG1", "kid $kid", p(lo, cells = "$lo,$lo")),
            "empty list" to raw("CLOG1", "kid $kid", p(lo, cells = "")),
            "extra space" to raw("CLOG1", "kid $kid", p(lo) + " "),
            "missing field" to raw("CLOG1", "kid $kid", "P $lo 4 3 - -"),
            "held for no place" to raw("CLOG1", "kid $kid", p(lo), "H $hi $a - -"),
            "held before places" to raw("CLOG1", "kid $kid", "H $lo $a - -", p(lo)),
            "held without a hash" to raw("CLOG1", "kid $kid", p(lo), "H $lo $a x -"),
            "duplicate held cell" to raw("CLOG1", "kid $kid", p(lo), "H $lo $a - -", "H $lo $a - -"),
            "cell as decimal" to raw("CLOG1", "kid $kid", p(lo, cells = "123456789")),
        )
        for ((why, text) in bad) assertNull(why, CellLogbook.decode(text))

        // Over each cap by one.
        fun h(prefix: String, n: Int) = (0 until n).map { hex("$prefix$it") }.sorted()
        val places = h("pl", CellLog.MAX_PLACES + 1)
        assertNull("65 places", CellLogbook.decode(raw("CLOG1", "kid $kid", *places.map { p(it) }.toTypedArray())))
        assertNotNull("64 places", CellLogbook.decode(raw("CLOG1", "kid $kid", *places.take(CellLog.MAX_PLACES).map { p(it) }.toTypedArray())))
        assertNull("49 cells", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, cells = h("c", 49).joinToString(",")))))
        assertNotNull("48 cells", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, cells = h("c", 48).joinToString(",")))))
        assertNull("17 areas", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, areas = h("a", 17).joinToString(",")))))
        assertNotNull("16 areas", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, areas = h("a", 16).joinToString(",")))))
        assertNull("9 operators", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, ops = h("o", 9).joinToString(",")))))
        assertNotNull("8 operators", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo, ops = h("o", 8).joinToString(",")))))
        val held = h("h", CellLog.MAX_HELD + 1).map { "H $lo $it - -" }
        assertNull("9 held", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo), *held.toTypedArray())))
        assertNotNull("8 held", CellLogbook.decode(raw("CLOG1", "kid $kid", p(lo), *held.take(CellLog.MAX_HELD).toTypedArray())))
        assertNull("too long", CellLogbook.decode(good + " ".repeat(CellLog.MAX_ROW_CHARS)))
    }

    @Test
    fun encodeRefusesAnOverCapBook() {
        fun refused(why: String, book: CellLogbook) {
            try {
                book.encode()
                fail("encode wrote $why")
            } catch (_: IllegalStateException) {
            }
        }
        val one = PlaceEntry(hex("x"), 3, 2)
        refused("65 places", CellLogbook(kid, (0..CellLog.MAX_PLACES).map { PlaceEntry(hex("pl$it"), 1, 0) }))
        refused("49 cells", CellLogbook(kid, listOf(one.copy(cells = filler(1, CellLog.MAX_CELLS + 1)))))
        refused("17 areas", CellLogbook(kid, listOf(one.copy(areas = filler(2, CellLog.MAX_AREAS + 1)))))
        refused("9 operators", CellLogbook(kid, listOf(one.copy(operators = filler(3, CellLog.MAX_OPERATORS + 1)))))
        refused("9 held", CellLogbook(kid, listOf(one), (0..CellLog.MAX_HELD).map { HeldTower(one.place, hex("hh$it"), null, null) }))
        refused("held for no place", CellLogbook(kid, listOf(one), listOf(HeldTower(hex("elsewhere"), hex("t"), null, null))))
        refused("scans 0", CellLogbook(kid, listOf(one.copy(scans = 0))))
        refused("scans 100", CellLogbook(kid, listOf(one.copy(scans = 100))))
        refused("rank 5", CellLogbook(kid, listOf(one.copy(bestRank = 5))))
        refused("a value that is not a hash", CellLogbook(kid, listOf(one.copy(cells = setOf("123456789")))))
        refused("duplicate place", CellLogbook(kid, listOf(one, one)))
        refused("a bad key id", CellLogbook("xyz", listOf(one)))
        // Exactly at every cap is fine and reads back.
        val full = PlaceEntry(hex("full"), 99, 4, filler(1, CellLog.MAX_CELLS), filler(2, CellLog.MAX_AREAS), filler(3, CellLog.MAX_OPERATORS))
        val book = CellLogbook(
            kid,
            (0 until CellLog.MAX_PLACES - 1).map { PlaceEntry(hex("pl$it"), 1, 0) } + full,
            (0 until CellLog.MAX_HELD).map { HeldTower(full.place, hex("hh$it"), null, null) },
        )
        assertEquals(book.places.sortedBy { it.place }, CellLogbook.decode(book.encode())!!.places)
    }

    @Test
    fun acceptIntoAFullListDropsTheHoldWithoutLearning() {
        val full = PlaceEntry(block(1)[0], 6, 3, filler(1, CellLog.MAX_CELLS))
        val b = lte(1001, area = 999)
        val (held, j) = scan(CellLogbook(kid, listOf(full)), block(1), b)
        assertEquals(1, held.held.size)
        val result = CellLog.accept(held, j.towerId!!)!!
        assertFalse(result.learned)
        assertTrue(result.book.held.isEmpty())
        assertFalse(tokens(b).single().cell in result.book.places.single().cells)
    }

    @Test
    fun rowHoldsNoTimestampsNoOrderNoRawIdentity() {
        // Distinctive inputs, so a leak of any of them is easy to see in the text.
        val cellIds = listOf(987654321L, 876543210L, 765432109L)
        val areas = listOf(55443L, 66554L)
        val serving = listOf(
            ServingCell(CellTech.LTE, "310", "260", areas[0], cellIds[0], 391, 66486),
            ServingCell(CellTech.LTE, "310", "260", areas[1], cellIds[1], 392, 66486),
            ServingCell(CellTech.NR, "310", "260", areas[0], cellIds[2], 393, 387410),
        )
        fun build(order: List<Int>): String {
            var book = CellLogbook(kid)
            // Same visits, different order: five scans at each of three places.
            for (n in order) repeat(5) { r -> book = scan(book, block(n), serving[r % 3]).first }
            book = scan(book, block(1), ServingCell(CellTech.LTE, "310", "260", 77889L, 555666777L, 1, 2)).first
            return book.encode()
        }
        val text = build(listOf(1, 2, 3))
        // No order: any order of the same visits gives the same row.
        assertEquals(text, build(listOf(3, 1, 2)))
        assertEquals(text, build(listOf(2, 3, 1)))

        val lines = text.trimEnd('\n').split('\n')
        val hash = "[0-9a-f]{32}"
        val list = "(-|$hash(,$hash)*)"
        for (l in lines.drop(2)) {
            val ok = Regex("P $hash [1-9][0-9]? [0-4] $list $list $list").matches(l) || Regex("H $hash $hash (-|$hash) (-|$hash)").matches(l)
            assertTrue(l, ok)
        }
        assertEquals(listOf("CLOG1", "kid $kid"), lines.take(2))
        // No raw identity: none of the inputs, as a number or a code, and nothing that is not a hash, a count or a rank.
        val digits = text.split(Regex("[^0-9]+"))
        for (secret in cellIds.map { it.toString() } + areas.map { it.toString() } + listOf("555666777", "77889", "391", "66486", "387410")) {
            assertFalse("leaked $secret", text.contains(secret))
            assertFalse("leaked $secret", digits.contains(secret))
        }
        assertFalse(text.contains("310-260"))
        assertFalse(text.contains("LTE"))
        // No timestamps: no run of digits that looks like a date or an epoch, no word that is not a line type.
        assertFalse(Regex("(19|20)\\d\\d-\\d\\d").containsMatchIn(text))
        assertFalse(Regex("\\b1[0-9]{9,12}\\b").containsMatchIn(text))
        val words = lines.flatMap { it.split(' ', ',') }.filter { it.isNotEmpty() && it != "-" }
        for (w in words) assertTrue(w, w in setOf("CLOG1", "kid", "P", "H") || Regex(hash).matches(w) || Regex("[0-9]{1,2}").matches(w) || w == kid)
        // Everything the book holds is a hash.
        val book = CellLogbook.decode(text)!!
        assertTrue(book.places.all { it.place.matches(Regex(hash)) && it.cells.all { c -> c.matches(Regex(hash)) } })
    }

    // Tokens

    @Test
    fun tokensAreKeyedHashesOfTheInputs() {
        val c = lte(42)
        val t = tokens(c).single()
        assertEquals(hex(c.cellInput()), t.cell)
        assertEquals(hex(c.areaInput()!!), t.area)
        assertEquals(hex(c.operatorInput()!!), t.operator)
        assertEquals(CellTech.LTE.rank, t.rank)
        assertEquals(t, tokens(c, c).single())
    }

    // The panel's words

    @Test
    fun theLineSaysWhatTheRowAndTheLastScanSay() {
        val on = CellLogText.Row.On(12)
        fun obs(vararg kv: Pair<String, String>) = mapOf(*kv)
        assertEquals(CellLogText.OFF, CellLogText.line(CellLogText.Row.Off, obs(SurroundingsKeys.LOG_STATE to "on")))
        assertEquals(CellLogText.UNREADABLE, CellLogText.line(CellLogText.Row.Unreadable, emptyMap()))
        assertEquals(CellLogText.ON_NO_SCAN, CellLogText.line(on, emptyMap()))
        assertEquals(CellLogText.ON_NO_SCAN, CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "off")))
        assertEquals(CellLogText.NO_PLACE, CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "no-place")))
        assertEquals(CellLogText.NO_CELL_ID, CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "no-cell-id")))
        assertEquals(CellLogText.RESTARTED, CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "restarted")))
        assertEquals("Learning this place (2 of 4 scans) · 1 tower", CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "on", SurroundingsKeys.LOG_PLACE_SCANS to "2", SurroundingsKeys.LOG_PLACE_CELLS to "1")))
        assertEquals("Familiar place · 7 towers known here · 12 places", CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "on", SurroundingsKeys.LOG_PLACE_SCANS to "9", SurroundingsKeys.LOG_PLACE_CELLS to "7")))
        assertEquals("Familiar place · 1 tower known here · 1 place", CellLogText.line(CellLogText.Row.On(1), obs(SurroundingsKeys.LOG_STATE to "on", SurroundingsKeys.LOG_PLACE_SCANS to "4", SurroundingsKeys.LOG_PLACE_CELLS to "1")))
        assertEquals(CellLogText.FAILED, CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "failed")))
        // A write that failed shows the verdict only, never counts from a book that was not saved.
        assertEquals(
            "Unfamiliar tower at a familiar place (not saved this scan)",
            CellLogText.line(on, obs(SurroundingsKeys.LOG_STATE to "failed", SurroundingsKeys.LOG_VERDICT to "unfamiliar", SurroundingsKeys.LOG_PLACE_SCANS to "9", SurroundingsKeys.LOG_PLACE_CELLS to "7")),
        )
        // A row that is on with no places yet (just started, maybe after a Clear) ignores scan facts that predate it.
        val stale = obs(SurroundingsKeys.LOG_STATE to "on", SurroundingsKeys.LOG_PLACE_SCANS to "9", SurroundingsKeys.LOG_PLACE_CELLS to "7")
        assertEquals(CellLogText.ON_NO_SCAN, CellLogText.line(CellLogText.Row.On(0), stale))
        assertEquals(CellLogText.ON_NO_SCAN, CellLogText.line(CellLogText.Row.On(0), obs(SurroundingsKeys.LOG_STATE to "unreadable")))
        assertEquals(CellLogText.ON_NO_SCAN, CellLogText.line(CellLogText.Row.On(0), obs(SurroundingsKeys.LOG_STATE to "no-place")))
        assertEquals(CellLogText.OFF, CellLogText.line(CellLogText.Row.Off, stale))
        assertEquals(CellLogText.Row.Off, CellLogText.row(null))
        assertEquals(CellLogText.Row.Unreadable, CellLogText.row("junk"))
        assertEquals(CellLogText.Row.On(0), CellLogText.row(CellLogbook(kid).encode()))
    }

    @Test
    fun observationsAreSummariesOnly() {
        val b = lte(1001, area = 999)
        val (book, j) = scan(learned(), block(1), b)
        val obs = SurroundingsKeys.logObservations(SurroundingsKeys.LOG_ON, j, book)
        val map = obs.associate { it.key to it.value }
        assertEquals("on", map[SurroundingsKeys.LOG_STATE])
        assertEquals("unfamiliar", map[SurroundingsKeys.LOG_VERDICT])
        assertEquals("area", map[SurroundingsKeys.LOG_SIGNALS])
        assertEquals(tokens(b).single().cell.take(8), map[SurroundingsKeys.LOG_TOWER])
        assertEquals("1", map[SurroundingsKeys.LOG_PLACES])
        assertEquals("5", map[SurroundingsKeys.LOG_PLACE_SCANS])
        assertEquals("1", map[SurroundingsKeys.LOG_PLACE_CELLS])
        assertTrue(obs.all { it.subject == SurroundingsKeys.CELL_SUMMARY && it.tunnelId == SurroundingsKeys.TUNNEL_ID })
        assertTrue("no full hash is an observation", obs.none { Regex("[0-9a-f]{32}").containsMatchIn(it.value) })
        val off = SurroundingsKeys.logObservations(SurroundingsKeys.LOG_OFF).associate { it.key to it.value }
        assertEquals(mapOf(SurroundingsKeys.LOG_STATE to "off"), off)
        val familiar = SurroundingsKeys.logObservations("on", scan(learned(), block(1), lte(1000)).second).associate { it.key to it.value }
        assertEquals("none", familiar[SurroundingsKeys.LOG_SIGNALS])
        assertNull(familiar[SurroundingsKeys.LOG_TOWER])
    }
}
