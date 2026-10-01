package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerGuidesTest {
    @Test
    fun everyTrackerTypeHasAFullGuide() {
        assertEquals(TrackerType.entries.toSet(), TrackerGuides.byType.keys)
        for (type in TrackerType.entries) {
            val g = TrackerGuides.of(type)
            assertTrue("$type identify", g.identify.length > 40)
            assertTrue("$type disable", g.disable.length > 40)
            assertTrue("$type report", g.reportNote.length > 20)
        }
    }

    @Test
    fun guidesNameTheBrandSpecificSteps() {
        assertTrue(TrackerGuides.apple.identify.contains("found.apple.com"))
        assertTrue(TrackerGuides.apple.identify.contains("NFC"))
        assertTrue(TrackerGuides.apple.disable.contains("counter-clockwise") && TrackerGuides.apple.disable.contains("CR2032"))
        assertTrue(TrackerGuides.samsung.identify.contains("SmartThings"))
        assertTrue(TrackerGuides.samsung.disable.contains("Pry") || TrackerGuides.samsung.disable.contains("pry"))
        assertTrue(TrackerGuides.tile.identify.contains("Scan and Secure"))
        assertTrue(TrackerGuides.tile.disable.contains("metal"))
        assertTrue(TrackerGuides.chipolo.identify.contains("Find My Device"))
        assertTrue(TrackerGuides.google.identify.contains("Unknown tracker alerts"))
        // The generic report steps cover screenshots, the serial and the police request.
        assertTrue(TrackerGuides.report.size >= 3)
        assertTrue(TrackerGuides.report.any { it.contains("Screenshot") })
        assertTrue(TrackerGuides.report.any { it.contains("serial") })
        assertTrue(TrackerGuides.report.any { it.contains("police") })
        assertTrue(TrackerGuides.SEARCH.contains("Find-it"))
        assertTrue(TrackerGuides.UNKNOWN_TRACKER_ALERTS.contains("Safety & emergency"))
    }
}
