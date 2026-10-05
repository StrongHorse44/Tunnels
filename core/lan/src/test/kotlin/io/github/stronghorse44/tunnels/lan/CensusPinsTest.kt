package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CensusPinsTest {
    private val tag = "ab12cd34"
    private val other = "ffffeeee"

    private val now = 1_000L
    private fun plan(snaps: List<CensusSnap>, tag: String, lastReset: Long?, at: Long = now) = CensusPins.plan(snaps, tag, lastReset, at)

    private fun snap(
        id: Long,
        at: Long,
        pinned: Boolean = false,
        only: Boolean = true,
        tag: String? = this.tag,
        state: String? = DeviceCensus.STATE_SET,
    ) = CensusSnap(id, at, pinned, only, tag, state)

    @Test
    fun pinsTheNewestHomeNetworkOnlySetSnapshot() {
        val plan = plan(listOf(snap(1, 100), snap(2, 300), snap(3, 200)), tag, null)
        assertEquals(2L, plan.pin)
        assertTrue(plan.unpin.isEmpty())
        // The id breaks a tie in taken_at.
        assertEquals(5L, plan(listOf(snap(4, 500), snap(5, 500)), tag, null).pin)
        // An unset snapshot is no target: only a list that exists is worth keeping.
        assertEquals(1L, plan(listOf(snap(1, 100), snap(2, 300, state = DeviceCensus.STATE_UNSET)), tag, null).pin)
        assertNull(plan(listOf(snap(2, 300, state = DeviceCensus.STATE_UNSET), snap(3, 400, state = DeviceCensus.STATE_UNAVAILABLE)), tag, null).pin)
        // Already pinned: nothing to do.
        val done = plan(listOf(snap(1, 100), snap(2, 300, pinned = true)), tag, null)
        assertNull(done.pin)
        assertTrue(done.unpin.isEmpty())
    }

    @Test
    fun neverPinsAMixedSnapshot() {
        val plan = plan(listOf(snap(1, 100), snap(2, 300, only = false)), tag, null)
        assertEquals("the older Home-network-only one", 1L, plan.pin)
        assertNull(plan(listOf(snap(2, 300, only = false)), tag, null).pin)
        // A mixed snapshot someone pinned is not ours to unpin either.
        val pinnedMixed = plan(listOf(snap(1, 100), snap(2, 300, only = false, pinned = true)), tag, null)
        assertEquals(1L, pinnedMixed.pin)
        assertTrue(pinnedMixed.unpin.isEmpty())
    }

    @Test
    fun unpinsOlderCensusPinsOfThisNetworkOnly() {
        val plan = plan(
            listOf(
                snap(1, 100, pinned = true),
                snap(2, 200, pinned = true),
                snap(3, 300),
                snap(4, 250, pinned = true, tag = other),
                snap(5, 260, pinned = true, state = DeviceCensus.STATE_UNAVAILABLE),
            ),
            tag, null,
        )
        assertEquals(3L, plan.pin)
        assertEquals(setOf(1L, 2L, 5L), plan.unpin.toSet())
        assertTrue("another network's pin stays", 4L !in plan.unpin)
    }

    @Test
    fun snapshotsWithoutCensusAreNeverTouched() {
        val plan = plan(
            listOf(
                snap(1, 100, pinned = true, tag = null, state = null),
                snap(2, 200, pinned = true, state = null),
                snap(3, 300, pinned = true, tag = null),
                snap(4, 400),
            ),
            tag, null,
        )
        assertEquals(4L, plan.pin)
        assertTrue(plan.unpin.isEmpty())
        assertEquals(PinPlan(null, emptyList()), plan(emptyList(), tag, null))
    }

    @Test
    fun anExpiredResetDoesNotPinAnOldUnpinnedList() {
        // lastReset is null because the reset event expired; the old unpinned set snapshot must not be pinned back.
        val day = 24L * 60 * 60 * 1000
        val old = snap(1, 100)
        assertNull(plan(listOf(old), tag, null, at = 100 + 31 * day).pin)
        assertEquals(1L, plan(listOf(old), tag, null, at = 100 + 29 * day).pin)
        // A pinned one stays the target however old it is.
        val pinnedOld = plan(listOf(old.copy(pinned = true)), tag, null, at = 100 + 90 * day)
        assertNull(pinnedOld.pin)
        assertTrue(pinnedOld.unpin.isEmpty())
        // With nothing current, another old pinned snapshot of this tag is unpinned only when it is not the target.
        val both = plan(listOf(old.copy(pinned = true), snap(2, 200, pinned = true)), tag, null, at = 200 + 40 * day)
        assertEquals(listOf(1L), both.unpin)
    }

    @Test
    fun resetUnpinsAndPinsNothingOlder() {
        val snaps = listOf(snap(1, 100, pinned = true), snap(2, 200))
        val reset = plan(snaps, tag, lastReset = 250)
        assertNull(reset.pin)
        assertEquals(listOf(1L), reset.unpin)
        // A set snapshot taken after the reset is the target again; the one at the reset instant is not.
        assertEquals(3L, plan(snaps + snap(3, 251), tag, 250).pin)
        assertNull(plan(snaps + snap(3, 250), tag, 250).pin)
    }

    @Test
    fun snapOfReadsTagAndStateFromTheSummary() {
        val t = LanKeys.TUNNEL_ID
        val obs = listOf(
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, tag),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, "set"),
            Observation(t, "192.168.1.5", LanKeys.SCAN_NETWORK, other),
            Observation("other_tunnel", LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, "unset"),
        )
        assertEquals(CensusSnap(9, 5, true, true, tag, "set"), CensusPins.snapOf(9, 5, true, true, obs))
        assertEquals(CensusSnap(9, 5, false, false, null, null), CensusPins.snapOf(9, 5, false, false, emptyList()))
    }
}
