package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingCellTest {
    @Test
    fun inputsMarkUnavailableFields() {
        assertEquals("cell:v1|LTE|310|260|4242|123|7|5230", ServingCell(CellTech.LTE, "310", "260", 4242, 123, 7, 5230).cellInput())
        assertEquals("cell:v1|NR|?|?|?|123|?|?", ServingCell(CellTech.NR, null, null, null, 123, null, null).cellInput())
        assertEquals("cell:v1|GSM|262|01|9|8|?|62", ServingCell(CellTech.GSM, "262", "01", 9, 8, null, 62).cellInput())
        assertNotEquals(ServingCell(CellTech.LTE, "310", "260", 1, 2, 3, 4).cellInput(), ServingCell(CellTech.NR, "310", "260", 1, 2, 3, 4).cellInput())
    }

    @Test
    fun areaAndOperatorInputsNeedTheirCodes() {
        val full = ServingCell(CellTech.LTE, "310", "260", 4242, 123, 7, 5230)
        assertEquals("area:v1|TA|310|260|4242", full.areaInput())
        assertEquals("op:v1|310|260", full.operatorInput())
        assertEquals("area:v1|TA|310|260|4242", full.copy(tech = CellTech.NR).areaInput())
        assertEquals("area:v1|LA|310|260|4242", full.copy(tech = CellTech.UMTS).areaInput())
        assertEquals("area:v1|LA|310|260|4242", full.copy(tech = CellTech.GSM).areaInput())
        assertNull(full.copy(tech = CellTech.UNKNOWN).areaInput())
        assertNull(full.copy(area = null).areaInput())
        assertNull(full.copy(mcc = null).areaInput())
        assertNull(full.copy(mnc = null).areaInput())
        assertEquals("op:v1|310|260", full.copy(area = null).operatorInput())
        assertNull(full.copy(mcc = null).operatorInput())
        assertNull(full.copy(mnc = null).operatorInput())
        // A tracking area and a location area of the same number are not the same area.
        assertNotEquals(full.areaInput(), full.copy(tech = CellTech.UMTS).areaInput())
    }

    @Test
    fun inputsCannotCollideWithGridIds() {
        val cells = listOf(PlaceGrid.cellOf(40.7128, -74.0060), PlaceGrid.cellOf(-33.8688, 151.2093), PlaceGrid.cellOf(0.0, 0.0)).flatMap { PlaceGrid.block(it) }
        for (c in cells) assertFalse(c.id, c.id.contains('|'))
        val serving = ServingCell(CellTech.LTE, "310", "260", 4242, 123, 7, 5230)
        val inputs = listOf(serving.cellInput(), serving.areaInput()!!, serving.operatorInput()!!)
        for (i in inputs) {
            assertTrue(i, i.contains('|'))
            assertTrue(i, cells.none { it.id == i })
        }
        assertEquals(3, inputs.map { it.substringBefore('|') }.toSet().size)
        assertTrue(inputs.all { it.substringBefore('|').endsWith(":v1") })
    }
}
