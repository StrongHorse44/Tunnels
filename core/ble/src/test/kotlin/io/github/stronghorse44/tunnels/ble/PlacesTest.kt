package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class PlacesTest {
    /** Metres north and east of a point, as degrees: good enough over a few kilometres. */
    private fun offset(lat: Double, lon: Double, north: Double, east: Double) =
        Pair(lat + north / 111_320.0, lon + east / (111_320.0 * cos(Math.toRadians(lat))))

    private fun sameBlock(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
        PlaceGrid.cellOf(b.first, b.second) in PlaceGrid.block(PlaceGrid.cellOf(a.first, a.second))

    @Test
    fun cellsAreAbout250MetresAndBlocksSpanNeighbours() {
        val home = 52.5200 to 13.4050
        val cell = PlaceGrid.cellOf(home.first, home.second)
        val block = PlaceGrid.block(cell)
        assertEquals(9, block.size)
        assertEquals(cell, block.first())
        assertEquals(9, block.toSet().size)
        // A few dozen metres away (GPS jitter, the next room) is the same place.
        assertTrue(sameBlock(home, offset(home.first, home.second, 40.0, -30.0)))
        assertTrue(sameBlock(home, offset(home.first, home.second, -60.0, 60.0)))
        // Any 200 m step stays inside the block; 800 m always leaves it.
        for (dir in listOf(0.0 to 200.0, 200.0 to 0.0, -200.0 to 0.0, 0.0 to -200.0, 140.0 to 140.0)) {
            assertTrue("200 m $dir", sameBlock(home, offset(home.first, home.second, dir.first, dir.second)))
        }
        for (dir in listOf(0.0 to 800.0, 800.0 to 0.0, -800.0 to 0.0, 0.0 to -800.0, 600.0 to 600.0)) {
            assertFalse("800 m $dir", sameBlock(home, offset(home.first, home.second, dir.first, dir.second)))
        }
        // The cell id carries no coordinates in degrees, only grid indices; it is hashed before it is stored anyway.
        assertFalse(cell.id.contains("52.5"))
        // Near the equator and far north the cells are still about the same size east-west.
        for (lat in listOf(0.5, 64.1)) {
            assertTrue(sameBlock(lat to 10.0, offset(lat, 10.0, 0.0, 200.0)))
            assertFalse(sameBlock(lat to 10.0, offset(lat, 10.0, 0.0, 800.0)))
        }
    }

    @Test
    fun anchorStaysUntilThePhoneLeavesTheBlock() {
        val hash = { c: PlaceGrid.Cell -> "h(${c.id})" }
        fun blockHashes(lat: Double, lon: Double) = PlaceGrid.block(PlaceGrid.cellOf(lat, lon)).map(hash)
        val home = 48.8566 to 2.3522
        val first = PlaceTracking.next(null, blockHashes(home.first, home.second)) { 29_000_000L }
        assertEquals(29_000_000L, first.place)
        assertEquals(hash(PlaceGrid.cellOf(home.first, home.second)), first.anchorHash)
        // Wandering around the flat: same place, same anchor.
        val nearby = offset(home.first, home.second, 50.0, 80.0)
        assertEquals(first, PlaceTracking.next(first, blockHashes(nearby.first, nearby.second)) { error("not a first place") })
        // Two kilometres away: the next place, anchored there.
        val work = offset(home.first, home.second, 1500.0, 1400.0)
        val second = PlaceTracking.next(first, blockHashes(work.first, work.second)) { error("not a first place") }
        assertEquals(first.place + 1, second.place)
        assertNotEquals(first.anchorHash, second.anchorHash)
        // Home again is a new place number, not the old one: only moves are counted, places are not recognised.
        val back = PlaceTracking.next(second, blockHashes(home.first, home.second)) { error("not a first place") }
        assertEquals(first.place + 2, back.place)
        assertEquals(29_000_000L, PlaceTracking.firstPlace(29_000_000L * 60_000 + 59_999))
    }

    @Test
    fun anchorRowRoundTrips() {
        val a = PlaceAnchor(29_123_456L, "9f86d081884c7d65")
        assertEquals("place=29123456;anchor=9f86d081884c7d65", a.encode())
        assertEquals(a, PlaceAnchor.parse(a.encode()))
        assertNull(PlaceAnchor.parse("place=x;anchor=y"))
        assertNull(PlaceAnchor.parse("place=5"))
        assertNull(PlaceAnchor.parse("anchor=abc"))
        assertNull(PlaceAnchor.parse(""))
    }

    @Test
    fun movementFromPlaceRuns() {
        assertEquals(0, Movement.longestRun(emptyList()))
        assertEquals(1, Movement.longestRun(listOf(5L, 5L, 5L)))
        assertEquals(1, Movement.longestRun(listOf(5L, 7L)))
        assertEquals(3, Movement.longestRun(listOf(7L, 5L, 6L, 6L)))
        assertEquals(2, Movement.longestRun(listOf(3L, 5L, 6L, 9L)))
        assertEquals(Movement.MOVED, Movement.of(placeRun = 2, placedSessions = 2))
        assertEquals(Movement.STAYED, Movement.of(placeRun = 1, placedSessions = 3))
        assertEquals(Movement.UNKNOWN, Movement.of(placeRun = 1, placedSessions = 2))
        assertEquals(Movement.UNKNOWN, Movement.of(placeRun = 0, placedSessions = 0))
        assertEquals(Movement.UNKNOWN, Movement.bySlug(null))
        Movement.entries.forEach { assertEquals(it, Movement.bySlug(it.slug)) }
    }
}
