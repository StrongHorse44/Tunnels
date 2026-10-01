package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellHeuristicsTest {
    @Test
    fun downgradeIsADropToLegacyFromModern() {
        assertTrue(CellHeuristics.isDowngrade(CellTech.LTE, CellTech.GSM))
        assertTrue(CellHeuristics.isDowngrade(CellTech.NR, CellTech.UMTS))
        assertTrue(CellHeuristics.isDowngrade(CellTech.LTE, CellTech.UMTS))
        assertFalse(CellHeuristics.isDowngrade(CellTech.NR, CellTech.LTE)) // 5G to 4G is normal
        assertFalse(CellHeuristics.isDowngrade(CellTech.UMTS, CellTech.GSM)) // already legacy
        assertFalse(CellHeuristics.isDowngrade(CellTech.LTE, CellTech.UNKNOWN)) // lost the cell, not downgraded
        assertFalse(CellHeuristics.isDowngrade(CellTech.UNKNOWN, CellTech.GSM))
        assertFalse(CellHeuristics.isDowngrade(CellTech.GSM, CellTech.LTE))
        assertTrue(CellHeuristics.isLegacy(CellTech.GSM))
        assertFalse(CellHeuristics.isLegacy(CellTech.UMTS))
    }

    @Test
    fun operatorCodes() {
        assertEquals("262-01", CellHeuristics.operatorOf("26201"))
        assertEquals("310-260", CellHeuristics.operatorOf("310260"))
        assertEquals("?", CellHeuristics.operatorOf(""))
        assertEquals("?", CellHeuristics.operatorOf(null))
        assertEquals("?", CellHeuristics.operatorOf("Vodafone"))
        assertEquals("?", CellHeuristics.operatorOf("2620"))
    }

    @Test
    fun summaryRoundTrip() {
        val c = CellSummary(CellTech.LTE, "262-01", 5)
        assertEquals("type=LTE;op=262-01;n=5", c.encode())
        assertEquals(c, CellSummary.parse(c.encode()))
        assertEquals(CellTech.UNKNOWN, CellSummary.parse("type=weird;op=?;n=0")!!.registered)
        assertNull(CellSummary.parse("garbage"))
        assertEquals(CellTech.NR, CellTech.bySlug("nr"))
    }

    @Test
    fun observations() {
        val obs = SurroundingsKeys.cellObservations(CellSummary(CellTech.NR, "262-01", 3), SurroundingsKeys.AVAILABLE_YES, changedSinceLast = true, downgradesRecorded = 2)
        val m = obs.associate { it.key to it.value }
        assertEquals(setOf(SurroundingsKeys.CELL_SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("NR", m[SurroundingsKeys.CELL_TYPE])
        assertEquals("262-01", m[SurroundingsKeys.CELL_OPERATOR])
        assertEquals("3", m[SurroundingsKeys.CELL_NEIGHBOURS])
        assertEquals("true", m[SurroundingsKeys.CELL_CHANGED])
        assertEquals("2", m[SurroundingsKeys.CELL_DOWNGRADES_RECORDED])
        val none = SurroundingsKeys.cellObservations(null, SurroundingsKeys.AVAILABLE_NO_PERMISSION, null, 0).associate { it.key to it.value }
        assertEquals("no permission", none[SurroundingsKeys.CELL_AVAILABLE])
        assertNull(none[SurroundingsKeys.CELL_TYPE])
    }
}
