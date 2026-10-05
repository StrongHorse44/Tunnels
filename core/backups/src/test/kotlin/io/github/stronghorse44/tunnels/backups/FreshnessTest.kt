package io.github.stronghorse44.tunnels.backups

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FreshnessTest {
    private val now = Fixtures.NOW
    private val day = Fixtures.DAY
    private fun summary(daysAgo: Long, suspicious: Int = 0) =
        AppSummary("prikey", 1, if (daysAgo < 0) 0 else now - daysAgo * day, 1, false, suspicious)

    @Test
    fun freshUntilThePastTheThreshold() {
        assertEquals(AppStatus.FRESH, Freshness.status(true, summary(29), now, 30))
        assertEquals(AppStatus.FRESH, Freshness.status(true, summary(30), now, 30))
        assertEquals(AppStatus.STALE, Freshness.status(true, AppSummary("prikey", 1, now - 30 * day - 1, 1, false, 0), now, 30))
        assertEquals(AppStatus.STALE, Freshness.status(true, summary(31), now, 30))
    }

    @Test
    fun theThresholdIsTheUsersToSet() {
        assertEquals(AppStatus.STALE, Freshness.status(true, summary(2), now, 1))
        assertEquals(AppStatus.FRESH, Freshness.status(true, summary(2), now, 7))
    }

    @Test
    fun missingWhenTrackedAndNothingFound() {
        assertEquals(AppStatus.MISSING, Freshness.status(true, null, now, 30))
        assertEquals(AppStatus.MISSING, Freshness.status(true, AppSummary("prikey", 0, 0, 0, false, 0), now, 30))
    }

    @Test
    fun onlyFutureDatedBundlesAreSuspicious() {
        assertEquals(AppStatus.SUSPICIOUS, Freshness.status(true, summary(-1, suspicious = 2), now, 30))
    }

    @Test
    fun untrackedNeverRaisesAnything() {
        assertEquals(AppStatus.UNTRACKED, Freshness.status(false, summary(500), now, 30))
        assertEquals(AppStatus.UNTRACKED, Freshness.status(false, null, now, 30))
    }

    @Test
    fun ageIsWholeDaysAndNeverNegative() {
        assertEquals(0, Freshness.ageDays(now - day + 1, now))
        assertEquals(1, Freshness.ageDays(now - day, now))
        assertEquals(0, Freshness.ageDays(now + 5 * day, now))
    }

    @Test
    fun drillIsDueNinetyDaysAfterTheLastOne() {
        val today = LocalDate.of(2026, 10, 5)
        assertEquals(DrillStatus.NONE, Freshness.drillStatus(null, today))
        assertEquals(DrillStatus.OK, Freshness.drillStatus(today.minusDays(89), today))
        assertEquals(DrillStatus.DUE, Freshness.drillStatus(today.minusDays(90), today))
        assertEquals(DrillStatus.OK, Freshness.drillStatus(today.plusDays(3), today))
    }

    private fun fileDated(daysAgo: Long, undated: Int = 1) = AppSummary("tunnels", 2, now - daysAgo * day, 0, true, 0, 0, undated)
    private fun fwxDated(daysAgo: Long, undated: Int = 1) = AppSummary("tunnels", 2, now - daysAgo * day, 1, false, 0, 0, undated)

    @Test
    fun anUndatedOldFormatFileKeepsAnOldFormatDatedFileFromBeingCalledStale() {
        // It might be newer than the one old-format file that has a date.
        assertEquals(AppStatus.UNKNOWN_DATE, Freshness.status(true, fileDated(80), now, 30))
        assertEquals(AppStatus.FRESH, Freshness.status(true, fileDated(5), now, 30))
    }

    @Test
    fun anUndatedOldFormatFileCannotKeepAnFwxBundleFromBeingCalledStale() {
        // The old format is no longer written, so it is never newer than an FWX header's date.
        assertEquals(AppStatus.STALE, Freshness.status(true, fwxDated(400), now, 30))
        assertEquals(AppStatus.FRESH, Freshness.status(true, fwxDated(3), now, 30))
    }

    @Test
    fun onlyLumenItemFilesAreAnIncompleteExportNotAnUnknownDate() {
        val items = AppSummary("lumen", 0, 0, 0, false, 0, items = 600)
        assertEquals(AppStatus.NO_MANIFEST, Freshness.status(true, items, now, 30))
        assertEquals(AppStatus.INCOMPLETE, Freshness.status(true, items, now, 30, Hold.CUT))
        assertEquals(AppStatus.UNREADABLE, Freshness.status(true, items, now, 30, Hold.FAILED))
    }

    @Test
    fun aHeldAppIsNeverCalledStaleMissingOrSuspiciousButAFreshOneStaysFresh() {
        assertEquals(AppStatus.FRESH, Freshness.status(true, summary(2), now, 30, Hold.CUT))
        assertEquals(AppStatus.INCOMPLETE, Freshness.status(true, summary(40), now, 30, Hold.CUT))
        assertEquals(AppStatus.INCOMPLETE, Freshness.status(true, null, now, 30, Hold.CUT))
        assertEquals(AppStatus.INCOMPLETE, Freshness.status(true, summary(-1, suspicious = 1), now, 30, Hold.CUT))
        assertEquals(AppStatus.UNREADABLE, Freshness.status(true, summary(40), now, 30, Hold.FAILED))
        assertEquals(AppStatus.UNREADABLE, Freshness.status(true, null, now, 30, Hold.FAILED))
        assertEquals(AppStatus.UNTRACKED, Freshness.status(false, null, now, 30, Hold.FAILED))
    }
}
