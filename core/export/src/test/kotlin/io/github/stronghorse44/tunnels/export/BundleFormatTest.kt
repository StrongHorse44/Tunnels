package io.github.stronghorse44.tunnels.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BundleFormatTest {
    private val sample = SnapshotBundle(
        snapshots = listOf(
            BundleSnapshot(1, 1_700_000_000_000, pinned = false, tunnelIds = listOf("permissions", "doors")),
            BundleSnapshot(7, 1_700_000_060_000, pinned = true, tunnelIds = emptyList()),
        ),
        observations = listOf(
            BundleObservation(1, "permissions", "com.example.app", "perm:CAMERA", "granted"),
            BundleObservation(1, "doors", "com.example.app", "exported:activities", "3"),
            BundleObservation(7, "permissions", "com.example.other", "perm:LOCATION", "denied"),
        ),
    )

    @Test
    fun roundTrip() {
        val text = BundleFormat.write(sample)
        assertTrue(text.startsWith("TSNAP1\n"))
        assertEquals(sample, BundleFormat.parse(text))
    }

    @Test
    fun emptyBundleRoundTrips() {
        assertEquals(SnapshotBundle.EMPTY, BundleFormat.parse(BundleFormat.write(SnapshotBundle.EMPTY)))
        assertEquals(SnapshotBundle.EMPTY, BundleFormat.parse("TSNAP1"))
    }

    @Test
    fun escapesTabsNewlinesAndBackslashes() {
        val nasty = "a\tb\nc\rd\\e\\tf"
        val bundle = SnapshotBundle(
            listOf(BundleSnapshot(1, 0, false, listOf("x"))),
            listOf(BundleObservation(1, nasty, nasty, nasty, nasty)),
        )
        val text = BundleFormat.write(bundle)
        // One header, one snapshot, one observation: raw newlines inside values never leak into the line structure.
        assertEquals(3, text.trimEnd('\n').split('\n').size)
        assertEquals(bundle, BundleFormat.parse(text))
        assertEquals("a\\tb\\nc\\rd\\\\e\\\\tf", BundleFormat.escape(nasty))
        assertEquals(nasty, BundleFormat.unescape(BundleFormat.escape(nasty)))
    }

    @Test
    fun toleratesCrlfCommentsAndBlankLines() {
        val text = "TSNAP1\r\n# exported by a test\r\n\r\nsnapshot\t3\t12\t1\tdoors\r\nobs\t3\tdoors\ts\tk\tv\r\n\r\n"
        val parsed = BundleFormat.parse(text)
        assertEquals(listOf(BundleSnapshot(3, 12, true, listOf("doors"))), parsed.snapshots)
        assertEquals(listOf(BundleObservation(3, "doors", "s", "k", "v")), parsed.observations)
    }

    @Test
    fun rejectsUnknownHeader() {
        assertRejected("TSNAP2\n")
        assertRejected("hello\n")
        assertRejected("")
        assertRejected("\n\n")
    }

    @Test
    fun rejectsMalformedRecords() {
        assertRejected("TSNAP1\nsnapshot\t1\t2\n")                       // too few fields
        assertRejected("TSNAP1\nsnapshot\tx\t2\t0\t\n")                   // bad id
        assertRejected("TSNAP1\nsnapshot\t1\t2\tyes\t\n")                 // bad pinned flag
        assertRejected("TSNAP1\nsnapshot\t1\t2\t0\t\nsnapshot\t1\t3\t0\t\n") // duplicate id
        assertRejected("TSNAP1\nobs\t9\tt\ts\tk\tv\n")                    // unknown snapshot
        assertRejected("TSNAP1\nfinding\t1\tx\n")                         // unknown record
        assertRejected("TSNAP1\nsnapshot\t1\t2\t0\t\nobs\t1\tt\ts\tk\tv\\q\n") // bad escape
        assertRejected("TSNAP1\nsnapshot\t1\t2\t0\t\nobs\t1\tt\ts\tk\tv\\\n")  // dangling backslash
    }

    @Test
    fun deduplicatedKeepsLastValueAndDropsOrphans() {
        val bundle = SnapshotBundle(
            listOf(BundleSnapshot(1, 0, false, emptyList()), BundleSnapshot(1, 5, true, emptyList())),
            listOf(
                BundleObservation(1, "t", "s", "k", "old"),
                BundleObservation(1, "t", "s", "k", "new"),
                BundleObservation(2, "t", "s", "k", "orphan"),
            ),
        )
        val clean = bundle.deduplicated()
        assertEquals(1, clean.snapshots.size)
        assertEquals(listOf(BundleObservation(1, "t", "s", "k", "new")), clean.observations)
    }

    private fun assertRejected(text: String) {
        try {
            BundleFormat.parse(text)
            fail("expected rejection of ${text.replace("\n", "\\n").replace("\t", "\\t")}")
        } catch (_: BundleFormatException) {
        }
    }
}
