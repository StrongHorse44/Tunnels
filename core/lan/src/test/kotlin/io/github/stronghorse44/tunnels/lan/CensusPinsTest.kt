package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CensusPinsTest {
    private val tag = "ab12cd34"
    private val other = "ffffeeee"

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
        val plan = CensusPins.plan(listOf(snap(1, 100), snap(2, 300), snap(3, 200)), tag, null)
        assertEquals(2L, plan.pin)
        assertTrue(plan.unpin.isEmpty())
        // The id breaks a tie in taken_at.
        assertEquals(5L, CensusPins.plan(listOf(snap(4, 500), snap(5, 500)), tag, null).pin)
        // An unset snapshot is no target: only a list that exists is worth keeping.
        assertEquals(1L, CensusPins.plan(listOf(snap(1, 100), snap(2, 300, state = DeviceCensus.STATE_UNSET)), tag, null).pin)
        assertNull(CensusPins.plan(listOf(snap(2, 300, state = DeviceCensus.STATE_UNSET), snap(3, 400, state = DeviceCensus.STATE_UNAVAILABLE)), tag, null).pin)
        // Already pinned: nothing to do.
        val done = CensusPins.plan(listOf(snap(1, 100), snap(2, 300, pinned = true)), tag, null)
        assertNull(done.pin)
        assertTrue(done.unpin.isEmpty())
    }

    @Test
    fun neverPinsAMixedSnapshot() {
        val plan = CensusPins.plan(listOf(snap(1, 100), snap(2, 300, only = false)), tag, null)
        assertEquals("the older Home-network-only one", 1L, plan.pin)
        assertNull(CensusPins.plan(listOf(snap(2, 300, only = false)), tag, null).pin)
        // A mixed snapshot someone pinned is not ours to unpin either.
        val pinnedMixed = CensusPins.plan(listOf(snap(1, 100), snap(2, 300, only = false, pinned = true)), tag, null)
        assertEquals(1L, pinnedMixed.pin)
        assertTrue(pinnedMixed.unpin.isEmpty())
    }

    @Test
    fun unpinsOlderCensusPinsOfThisNetworkOnly() {
        val plan = CensusPins.plan(
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
        val plan = CensusPins.plan(
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
        assertEquals(PinPlan(null, emptyList()), CensusPins.plan(emptyList(), tag, null))
    }

    @Test
    fun resetUnpinsAndPinsNothingOlder() {
        val snaps = listOf(snap(1, 100, pinned = true), snap(2, 200))
        val reset = CensusPins.plan(snaps, tag, lastReset = 250)
        assertNull(reset.pin)
        assertEquals(listOf(1L), reset.unpin)
        // A set snapshot taken after the reset is the target again; the one at the reset instant is not.
        assertEquals(3L, CensusPins.plan(snaps + snap(3, 251), tag, 250).pin)
        assertNull(CensusPins.plan(snaps + snap(3, 250), tag, 250).pin)
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
