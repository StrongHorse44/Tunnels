package io.github.stronghorse44.tunnels.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PatchLevelTest {
    @Test
    fun parsesMonthlyAndDailyLevels() {
        assertEquals(LocalDate.of(2025, 5, 1), PatchLevel.parse(202505))
        assertEquals(LocalDate.of(2025, 5, 5), PatchLevel.parse(20250505))
        assertEquals(LocalDate.of(2025, 9, 5), PatchLevel.parse("20250905"))
        assertEquals(LocalDate.of(2025, 9, 1), PatchLevel.parse("202509"))
        assertEquals(LocalDate.of(2025, 9, 5), PatchLevel.parse("2025-09-05"))
    }

    @Test
    fun rejectsImpossibleValues() {
        assertNull(PatchLevel.parse(0))
        assertNull(PatchLevel.parse(-1))
        assertNull(PatchLevel.parse(202513))
        assertNull(PatchLevel.parse(20250232))
        assertNull(PatchLevel.parse(2025))
        assertNull(PatchLevel.parse(""))
        assertNull(PatchLevel.parse(null))
        assertNull(PatchLevel.parse("abc"))
        assertNull(PatchLevel.parse("2025-13-01"))
    }

    @Test
    fun ageAndFormatting() {
        val today = LocalDate.of(2026, 10, 1)
        assertEquals(153L, PatchLevel.ageDays(202605, today))
        assertEquals(0L, PatchLevel.ageDays(20261001, today))
        assertEquals(-30L, PatchLevel.ageDays(20261031, today))
        assertNull(PatchLevel.ageDays(0, today))
        assertEquals("2025-05", PatchLevel.format(202505))
        assertEquals("2025-05-05", PatchLevel.format(20250505))
        assertEquals("7", PatchLevel.format(7))
        assertEquals("current", PatchLevel.describeAge(0))
        assertEquals("5 days old", PatchLevel.describeAge(5))
        assertEquals("6 weeks old", PatchLevel.describeAge(45))
        assertEquals("5 months old", PatchLevel.describeAge(153))
        assertEquals("over 1 year old", PatchLevel.describeAge(400))
        assertEquals("over 2 years old", PatchLevel.describeAge(800))
    }
}
