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
    fun commentsAndBlankLinesMayPrecedeTheHeader() {
        val parsed = BundleFormat.parse("# a note\n\nTSNAP1\nsnapshot\t3\t12\t1\tdoors\n")
        assertEquals(listOf(BundleSnapshot(3, 12, true, listOf("doors"))), parsed.snapshots)
        assertRejected("# only a comment\n")
    }

    @Test
    fun parsesFromAReaderWithoutAStringCopy() {
        val bytes = BundleFormat.write(sample).toByteArray(Charsets.UTF_8)
        assertEquals(sample, BundleFormat.parse(bytes.inputStream().reader(Charsets.UTF_8)))
    }

    @Test
    fun rejectsUnknownHeader() {
        assertRejected("TSNAP2\n")
        assertRejected("hello\n")
        assertRejected("")
        assertRejected("\n\n")
        assertRejected("\nsnapshot\t1\t2\t0\t\n") // a record where the header should be
    }

    @Test
    fun writerRefusesTunnelIdsWithCommas() {
        val bundle = SnapshotBundle(listOf(BundleSnapshot(1, 0, false, listOf("a,b"))), emptyList())
        try {
            BundleFormat.write(bundle)
            fail("comma in tunnel id accepted")
        } catch (_: IllegalArgumentException) {
        }
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

    @Test
    fun writeToAppendableMatchesWrite() {
        val sb = StringBuilder()
        BundleFormat.writeTo(sample, sb)
        assertEquals(BundleFormat.write(sample), sb.toString())
    }

    @Test
    fun parseStopsAtTheCaps() {
        val limits = ParseLimits(maxSnapshots = 2, maxObservations = 2, maxLineChars = 40)
        // Within the caps.
        assertEquals(2, BundleFormat.parse("TSNAP1\nsnapshot\t1\t1\t0\tx\nsnapshot\t2\t2\t0\tx\n".reader(), limits).snapshots.size)
        // A third snapshot.
        assertFails("TSNAP1\nsnapshot\t1\t1\t0\tx\nsnapshot\t2\t2\t0\tx\nsnapshot\t3\t3\t0\tx\n", limits)
        // A third observation.
        assertFails("TSNAP1\nsnapshot\t1\t1\t0\tx\nobs\t1\tx\ts\tk\t1\nobs\t1\tx\ts\tk2\t1\nobs\t1\tx\ts\tk3\t1\n", limits)
        // A line longer than the cap, with no line break to stop it.
        assertFails("TSNAP1\nsnapshot\t1\t1\t0\t" + "x".repeat(100), limits)
        assertFails("TSNAP1\n" + "#".repeat(41), limits)
    }

    @Test
    fun theImportLimitsAreGenerousButFinite() {
        assertTrue(ParseLimits.IMPORT.maxObservations in 100_000..1_000_000)
        assertTrue(ParseLimits.IMPORT.maxSnapshots in 100..10_000)
    }

    private fun assertFails(text: String, limits: ParseLimits) {
        try {
            BundleFormat.parse(text.reader(), limits)
            fail("accepted")
        } catch (_: BundleFormatException) {
        }
    }
}
