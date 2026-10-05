package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.lan.DeviceCensus.Ack
import io.github.stronghorse44.tunnels.lan.DeviceCensus.Baseline
import io.github.stronghorse44.tunnels.lan.DeviceCensus.CensusEvent
import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCensusTest {
    private val t = LanKeys.TUNNEL_ID
    private val tag = "ab12cd34"

    /** Distinct valid tokens: u000...0 style, numbered. */
    private fun tok(n: Int, type: Char = 'n') = type + "%016x".format(n)

    private val now = 2_000L
    private fun compute(baseline: Baseline?, lastReset: Long?, acks: List<Ack>, at: Long = now) = DeviceCensus.compute(baseline, lastReset, acks, at)

    private fun set(vararg n: Int) = Baseline(1_000, DeviceCensus.STATE_SET, n.map { tok(it) }.toSet())

    @Test
    fun noBaselineAndNoAcksIsUnset() {
        val c = compute(null, null, emptyList())
        assertEquals(DeviceCensus.STATE_UNSET, c.state)
        assertTrue(c.known.isEmpty())
        assertFalse(c.full)
        // An unset baseline carries nothing forward.
        val unset = compute(Baseline(5, DeviceCensus.STATE_UNSET, emptySet()), null, emptyList())
        assertEquals(DeviceCensus.STATE_UNSET, unset.state)
    }

    @Test
    fun baselineFromThisNetworkOnly() {
        val mine = listOf(
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, tag),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, "set"),
            Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, "${tok(1)},${tok(2)},junk,${tok(1, 'x')}"),
        )
        val b = DeviceCensus.baselineOf(tag, 77, mine)!!
        assertEquals(77, b.takenAt)
        assertFalse(b.pinned)
        assertTrue(DeviceCensus.baselineOf(tag, 77, mine, pinned = true)!!.pinned)
        assertEquals(setOf(tok(1), tok(2)), b.known)
        assertNull("another network's snapshot", DeviceCensus.baselineOf("ffffeeee", 77, mine))
        assertNull("no census in that snapshot", DeviceCensus.baselineOf(tag, 77, mine.filter { it.key == LanKeys.SCAN_NETWORK }))
        // Unavailable carries no list: it is skipped, never read as an empty list.
        val unavailable = mine.filter { it.key == LanKeys.SCAN_NETWORK } + Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, "unavailable")
        assertNull(DeviceCensus.baselineOf(tag, 77, unavailable))
        val unset = mine.filter { it.key == LanKeys.SCAN_NETWORK } + Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, "unset")
        assertEquals(DeviceCensus.STATE_UNSET, DeviceCensus.baselineOf(tag, 77, unset)!!.state)
        // Only the summary subject counts, and only home_network rows.
        val spoof = listOf(
            Observation(t, "192.168.1.5", LanKeys.SCAN_NETWORK, tag),
            Observation(t, "192.168.1.5", LanKeys.CENSUS_STATE, "set"),
            Observation("other_tunnel", LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, tag),
        )
        assertNull(DeviceCensus.baselineOf(tag, 1, spoof))
        // The list read from a snapshot never exceeds the cap.
        val huge = (1..600).joinToString(",") { tok(it) }
        assertEquals(DeviceCensus.MAX_KNOWN, DeviceCensus.parseKnown(huge).size)
        assertTrue(DeviceCensus.parseKnown(LanKeys.NONE).isEmpty())
        assertTrue(DeviceCensus.parseKnown(null).isEmpty())
    }

    @Test
    fun acksAfterTheLastResetOnly() {
        val acks = listOf(Ack(100, listOf(tok(1))), Ack(300, listOf(tok(2), tok(3))), Ack(200, listOf(tok(4))))
        val c = compute(null, 150, acks)
        assertEquals(setOf(tok(2), tok(3), tok(4)), c.known)
        assertEquals(DeviceCensus.STATE_SET, c.state)
        assertEquals(setOf(tok(1), tok(2), tok(3), tok(4)), compute(null, null, acks).known)
        // An acknowledgement at the instant of the reset is before it.
        assertTrue(compute(null, 300, acks).known.isEmpty())
        // A baseline plus acks is their union.
        assertEquals(setOf(tok(9), tok(2), tok(3), tok(4), tok(1)), compute(set(9), null, acks).known)
    }

    @Test
    fun resetNewerThanBaselineEmptiesTheList() {
        val c = compute(set(1, 2), lastReset = 2_000, acks = emptyList())
        assertEquals(DeviceCensus.STATE_UNSET, c.state)
        assertTrue(c.known.isEmpty())
        // A reset at the baseline's own instant is not older than it either.
        assertEquals(DeviceCensus.STATE_UNSET, compute(set(1, 2), lastReset = 1_000, acks = emptyList()).state)
    }

    @Test
    fun anExpiredResetDoesNotRevivePastTheEventLife() {
        // A reset is an event and expires at 30 days, but an old unpinned list stays in its snapshot: with the event gone
        // (lastReset null) that list must not come back.
        val day = 24L * 60 * 60 * 1000
        val old = Baseline(0, DeviceCensus.STATE_SET, setOf(tok(1), tok(2)))
        val later = 31 * day
        val c = compute(old, lastReset = null, acks = emptyList(), at = later)
        assertEquals(DeviceCensus.STATE_UNSET, c.state)
        assertTrue(c.known.isEmpty())
        // The same list, pinned, still stands; so does an unpinned one inside the 30 days.
        assertEquals(DeviceCensus.STATE_SET, compute(old.copy(pinned = true), null, emptyList(), later).state)
        assertEquals(DeviceCensus.STATE_SET, compute(old, null, emptyList(), 29 * day).state)
        assertEquals("exactly 30 days is expired", DeviceCensus.STATE_UNSET, compute(old, null, emptyList(), 30 * day).state)
        assertTrue(DeviceCensus.isCurrent(1, false, 1 + 30 * day - 1))
        // Acknowledgements are events too, so they are inside their own life by construction.
        assertEquals(setOf(tok(3)), compute(old, null, listOf(Ack(later - 1, listOf(tok(3)))), later).known)
    }

    private val day = 24L * 60 * 60 * 1000

    private fun candidate(takenAt: Long, state: String, vararg known: Int, pinned: Boolean = false, homenetOnly: Boolean = true) =
        DeviceCensus.Candidate(
            takenAt, pinned, homenetOnly,
            listOf(
                Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, tag),
                Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, state),
                Observation(t, LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, LanKeys.list(known.map { tok(it) })),
            ),
        )

    @Test
    fun aHandPinnedMixedSnapshotIsNotExemptFromTheEventLife() {
        // Expired reset, and a full snapshot the user pinned from Snapshots still holds the pre-reset list: the census
        // never unpins a mixed snapshot, so the pin must not keep that list alive.
        val later = 31 * day
        val mixedPinned = candidate(0, DeviceCensus.STATE_SET, 1, 2, pinned = true, homenetOnly = false)
        assertNull(DeviceCensus.baselineFrom(tag, listOf(mixedPinned), later))
        assertEquals(DeviceCensus.STATE_UNSET, compute(null, null, emptyList(), later).state)
        // The same snapshot inside the 30 days still counts, and a Home-network-only pin is exempt at any age.
        assertEquals(DeviceCensus.STATE_SET, DeviceCensus.baselineFrom(tag, listOf(mixedPinned), 29 * day)!!.state)
        val homenetPinned = candidate(0, DeviceCensus.STATE_SET, 1, 2, pinned = true)
        assertEquals(setOf(tok(1), tok(2)), DeviceCensus.baselineFrom(tag, listOf(homenetPinned), 90 * day)!!.known)
        // The pin plan ignores the mixed one: nothing is pinned.
        val plan = CensusPins.plan(listOf(CensusSnap(1, 0, true, false, tag, DeviceCensus.STATE_SET)), tag, null, later)
        assertNull(plan.pin)
        assertTrue(plan.unpin.isEmpty())
    }

    @Test
    fun aStaleMixedListInFrontDoesNotHideAPinnedHomenetListBehindIt() {
        val now = 41 * day
        val staleMixed = candidate(10 * day, DeviceCensus.STATE_SET, 7, pinned = false, homenetOnly = false)
        val pinnedHomenet = candidate(1 * day, DeviceCensus.STATE_SET, 1, 2, pinned = true)
        val b = DeviceCensus.baselineFrom(tag, listOf(staleMixed, pinnedHomenet), now)!!
        assertEquals(setOf(tok(1), tok(2)), b.known)
        val list = compute(b, null, emptyList(), now)
        assertEquals(DeviceCensus.STATE_SET, list.state)
        assertEquals(setOf(tok(1), tok(2)), list.known)
        // A stale unpinned Home-network-only list is skipped the same way; the pinned one behind it is reached.
        val staleHomenet = candidate(10 * day, DeviceCensus.STATE_SET, 7)
        assertEquals(setOf(tok(1), tok(2)), DeviceCensus.baselineFrom(tag, listOf(staleHomenet, pinnedHomenet), now)!!.known)
        // The first current list wins; stale ones are only passed over, never merged.
        val recent = candidate(40 * day, DeviceCensus.STATE_SET, 9)
        assertEquals(setOf(tok(9)), DeviceCensus.baselineFrom(tag, listOf(recent, staleMixed, pinnedHomenet), now)!!.known)
    }

    @Test
    fun theWalkStopsAtUnsetAndSkipsUnavailableAndOtherNetworks() {
        val now = 41 * day
        val pinnedHomenet = candidate(1 * day, DeviceCensus.STATE_SET, 1, pinned = true)
        // An unset snapshot (what a reset produces) ends the walk: the older pinned list behind it is not revived.
        val unset = candidate(40 * day, DeviceCensus.STATE_UNSET)
        assertEquals(DeviceCensus.STATE_UNSET, DeviceCensus.baselineFrom(tag, listOf(unset, pinnedHomenet), now)!!.state)
        // Unavailable carries no list and is passed over.
        val unavailable = candidate(40 * day, DeviceCensus.STATE_UNAVAILABLE)
        assertEquals(setOf(tok(1)), DeviceCensus.baselineFrom(tag, listOf(unavailable, pinnedHomenet), now)!!.known)
        assertNull(DeviceCensus.baselineFrom("ffffeeee", listOf(pinnedHomenet), now))
        assertNull(DeviceCensus.baselineFrom(tag, emptyList(), now))
    }

    @Test
    fun fitsCountsOnlyTokensNotYetListed() {
        val full = (1..512).map { tok(it) }.toSet()
        assertTrue(DeviceCensus.fits(full, listOf(tok(3), tok(4))))
        assertFalse(DeviceCensus.fits(full, listOf(tok(3), tok(900))))
        assertTrue(DeviceCensus.fits((1..510).map { tok(it) }.toSet(), listOf(tok(900), tok(901))))
        assertFalse(DeviceCensus.fits((1..511).map { tok(it) }.toSet(), listOf(tok(900), tok(901))))
        assertTrue(DeviceCensus.fits(emptySet(), emptyList()))
    }

    @Test
    fun baselineTakenAfterResetCounts() {
        val c = compute(set(1, 2), lastReset = 999, acks = emptyList())
        assertEquals(DeviceCensus.STATE_SET, c.state)
        assertEquals(setOf(tok(1), tok(2)), c.known)
    }

    @Test
    fun capKeepsBaseThenOldestAcksAndSetsFull() {
        val base = Baseline(1_000, DeviceCensus.STATE_SET, (1..510).map { tok(it) }.toSet())
        val acks = listOf(
            Ack(30, listOf(tok(900))),
            Ack(10, listOf(tok(700), tok(701), tok(702))),
            Ack(20, listOf(tok(800))),
        )
        val c = compute(base, null, acks)
        assertEquals(DeviceCensus.MAX_KNOWN, c.known.size)
        assertTrue(c.full)
        assertTrue("the base is kept whole", (1..510).all { tok(it) in c.known })
        // The oldest ack goes first: two of its three tokens fit, the primary one among them.
        assertTrue(tok(700) in c.known && tok(701) in c.known)
        assertFalse(tok(702) in c.known || tok(800) in c.known || tok(900) in c.known)
        // Exactly full without overflow is not "full".
        val exact = compute(Baseline(1, DeviceCensus.STATE_SET, (1..511).map { tok(it) }.toSet()), null, listOf(Ack(5, listOf(tok(600), tok(1)))))
        assertEquals(DeviceCensus.MAX_KNOWN, exact.known.size)
        assertFalse(exact.full)
        // An acknowledgement already on the list takes no room even when the list is full.
        assertFalse(compute(Baseline(1, DeviceCensus.STATE_SET, (1..512).map { tok(it) }.toSet()), null, listOf(Ack(5, listOf(tok(3))))).full)
    }

    @Test
    fun malformedEventRowsAreIgnored() {
        val good = DeviceCensus.eventSummary(listOf(tok(1), tok(2)))
        assertEquals("ids=${tok(1)},${tok(2)}", good)
        assertEquals(listOf(tok(1), tok(2)), DeviceCensus.parseEvent(good))
        for (bad in listOf(
            "", "ids=", "ids=,", "ids=${tok(1)},", "ids=junk", "ids=${tok(1)} ${tok(2)}", "ids=${tok(1)},${tok(1)}",
            "IDS=${tok(1)}", " ids=${tok(1)}", "ids=${tok(1)};${tok(2)}", "ids=" + (1..7).joinToString(",") { tok(it) }, "reset",
            "ids=${tok(1)},${tok(2, 'x')}",
        )) {
            assertNull("[$bad]", DeviceCensus.parseEvent(bad))
        }
        assertEquals(6, DeviceCensus.parseEvent("ids=" + (1..6).joinToString(",") { tok(it) })!!.size)
        // Seven tokens are cut to six when written.
        assertEquals(6, DeviceCensus.parseEvent(DeviceCensus.eventSummary((1..7).map { tok(it) }))!!.size)

        val folded = DeviceCensus.fold(
            tag,
            listOf(
                CensusEvent(10, DeviceCensus.KIND_ACK, tag, good),
                CensusEvent(20, DeviceCensus.KIND_ACK, tag, "ids=junk"),
                CensusEvent(30, DeviceCensus.KIND_ACK, "ffffeeee", DeviceCensus.eventSummary(listOf(tok(5)))),
                CensusEvent(40, "mystery", tag, good),
                CensusEvent(50, DeviceCensus.KIND_RESET, tag, "not reset"),
                CensusEvent(60, DeviceCensus.KIND_RESET, "ffffeeee", DeviceCensus.RESET_SUMMARY),
                CensusEvent(70, DeviceCensus.KIND_RESET, tag, DeviceCensus.RESET_SUMMARY),
                CensusEvent(65, DeviceCensus.KIND_RESET, tag, DeviceCensus.RESET_SUMMARY),
            ),
        )
        assertEquals(70L, folded.lastReset)
        assertEquals(listOf(Ack(10, listOf(tok(1), tok(2)))), folded.acks)
        assertNull(DeviceCensus.fold(tag, emptyList()).lastReset)
    }

    @Test
    fun subjectRoundTripsThePrimaryToken() {
        val s = DeviceCensus.subject("Living Room TV", tok(7, 'u'))
        assertEquals("Living Room TV · ${tok(7, 'u')}", s)
        assertEquals(tok(7, 'u'), DeviceCensus.parsePrimary(s))
        // A title that itself holds the separator still round-trips: the last field wins.
        assertEquals(tok(7, 'u'), DeviceCensus.parsePrimary(DeviceCensus.subject("A · B", tok(7, 'u'))))
        assertNull(DeviceCensus.parsePrimary("unidentified · 192.168.1.5"))
        assertNull(DeviceCensus.parsePrimary("Living Room TV"))
        assertNull(DeviceCensus.parsePrimary(tok(7, 'u')))
        assertNull(DeviceCensus.parsePrimary("x · ${tok(7, 'u')} "))
        assertNull(DeviceCensus.parsePrimary("x · ${tok(7, 'u').uppercase()}"))
        val setup = DeviceCensus.setupSubject(tag)
        assertEquals("Device census · ab12cd34", setup)
        assertEquals(tag, DeviceCensus.parseTag(setup))
        assertNull(DeviceCensus.parseTag("Device census · ab12cd3"))
        assertNull(DeviceCensus.parseTag("Device census · AB12CD34"))
        assertNull(DeviceCensus.parseTag("Living Room TV · ab12cd34"))
        assertNull(DeviceCensus.parsePrimary(setup))
        assertEquals("Unnamed device", DeviceCensus.title(null, null))
        assertEquals("Sony", DeviceCensus.title(" ", "Sony"))
        assertEquals("Hue", DeviceCensus.title("Hue", "Philips"))
    }

    @Test
    fun aHostIsListedWhenAnyTokenIs() {
        val known = setOf(tok(1), tok(2))
        assertTrue(DeviceCensus.isListed(listOf(tok(9), tok(2)), known))
        assertFalse(DeviceCensus.isListed(listOf(tok(9)), known))
        assertFalse("no identity is never listed", DeviceCensus.isListed(emptyList(), known))
    }
}
