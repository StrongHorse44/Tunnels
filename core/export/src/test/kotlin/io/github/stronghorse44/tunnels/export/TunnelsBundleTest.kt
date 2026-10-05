package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.Samples.assertCode
import io.github.stronghorse44.tunnels.export.Samples.pass
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The Tunnels bundle (schema 1): round trip, the names `--list` shows, and every way a file can be refused. */
class TunnelsBundleTest {
    private fun read(file: ByteArray, passphrase: String = Samples.PASSPHRASE) =
        TunnelsBundle.read(ByteArrayInputStream(file), passphrase.toCharArray())

    private fun parse(entries: List<Pair<String, ByteArray>>) {
        val it = entries.iterator()
        TunnelsBundle.parse { if (it.hasNext()) it.next().let { (n, b) -> SourceEntry(n, b.size.toLong(), ByteArrayInputStream(b)) } else null }
    }

    private fun entries(data: TunnelsData = Samples.full) = Samples.entriesOf(data)

    private fun replace(list: MutableList<Pair<String, ByteArray>>, name: String, text: String) {
        val i = list.indexOfFirst { it.first == name }
        list[i] = name to text.toByteArray()
    }

    private fun assertPayload(list: List<Pair<String, ByteArray>>) = assertCode(FwxError.MALFORMED_PAYLOAD) { parse(list) }

    // Round trip

    @Test
    fun aFullBundleRoundTrips() {
        assertEquals(Samples.full, read(Samples.bundle()))
    }

    @Test
    fun anEmptyBundleRoundTrips() {
        assertEquals(TunnelsData.EMPTY, read(Samples.bundle(TunnelsData.EMPTY)))
    }

    @Test
    fun theWriterProducesTheDocumentedEntries() {
        val out = java.io.ByteArrayOutputStream()
        val encoded = TunnelsBundle.encode(Samples.full, "0.1.0-debug")
        TunnelsBundle.write(out, pass(), encoded, 1_791_028_800_000L)
        val file = out.toByteArray()
        assertEquals(listOf("manifest.json", "snapshots.tsnap1", "settings.tsv", "pairing-pins.txt", "networks.txt"), Samples.listNames(file))
        assertEquals(TunnelsBundle.NAMES, Samples.listNames(file))
        // The header names the app and the schema, in the clear.
        val info = TunnelsBundle.inspect(ByteArrayInputStream(file), file.size.toLong())
        assertEquals(Inspected.Fwx(1, 1_791_028_800_000L, file.size.toLong()), info)
    }

    @Test
    fun theManifestCountsWhatTheBundleHolds() {
        val manifest = String(Samples.entriesOf().first { it.first == "manifest.json" }.second)
        assertEquals(
            "{\"app\":\"tunnels\",\"schema\":1,\"app_version\":\"test\",\"snapshots\":3,\"observations\":4," +
                "\"settings\":3,\"pairing_pins\":2,\"networks\":2}",
            manifest,
        )
        assertEquals(BundleCounts(3, 4, 3, 2, 2), Samples.full.counts)
    }

    @Test
    fun theVersionStringIsMadeSafeForTheManifest() {
        val m = String(TunnelsBundle.encode(TunnelsData.EMPTY, "1.0 \"x\"\\\n").first().bytes)
        assertTrue(m, "\"app_version\":\"1.0__x___\"" in m)
    }

    // Before the passphrase (container spec 5.1, steps 1 to 6)

    @Test
    fun anotherAppsBundleIsRefusedByNameBeforeAnyPassphrase() {
        val lumen = Samples.craft(listOf("manifest.json" to "{}".toByteArray()), appId = "lumen")
        try {
            TunnelsBundle.inspect(ByteArrayInputStream(lumen))
            throw AssertionError("accepted")
        } catch (e: io.github.stronghorse44.tunnels.export.fwx.FwxException) {
            assertEquals(FwxError.WRONG_APP, e.code)
            assertEquals("lumen", e.otherAppId)
            assertEquals("This is a Lumen export, not a Tunnels export. Nothing was changed.", TransferMessages.importFailure(e))
        }
        // The reader refuses it too, whatever passphrase it is given.
        assertCode(FwxError.WRONG_APP) { read(lumen, "any passphrase at all") }
    }

    @Test
    fun aNewerOrOlderSchemaIsRefusedBeforeAnyPassphrase() {
        val newer = Samples.craft(listOf("manifest.json" to "{}".toByteArray()), schema = 2)
        assertCode(FwxError.SCHEMA_TOO_NEW) { TunnelsBundle.inspect(ByteArrayInputStream(newer)) }
        assertCode(FwxError.SCHEMA_TOO_NEW) { read(newer) }
    }

    @Test
    fun notAnExportIsRefused() {
        assertCode(FwxError.NOT_AN_EXPORT) { TunnelsBundle.inspect(ByteArrayInputStream("hello, this is a text file".toByteArray())) }
        assertCode(FwxError.NOT_AN_EXPORT) { TunnelsBundle.inspect(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun aLegacyFileIsRecognisedFromItsFirstBytes() {
        val legacy = LegacySeal.seal("TSNAP1\n".toByteArray(), pass())
        assertEquals(Inspected.Legacy, TunnelsBundle.inspect(ByteArrayInputStream(legacy)))
    }

    // After the passphrase

    @Test
    fun aWrongPassphraseChangesNothingAndSaysSo() {
        assertCode(FwxError.WRONG_PASSPHRASE) { read(Samples.bundle(), "correct horse battery stapl") }
    }

    @Test
    fun aTruncatedOrDamagedFileIsRefused() {
        val file = Samples.bundle()
        assertCode(FwxError.DAMAGED) { read(file.copyOf(file.size - 1)) }
        assertCode(FwxError.DAMAGED) { read(file.copyOf(120)) }
        val flipped = file.copyOf().also { it[file.size / 2] = (it[file.size / 2].toInt() xor 1).toByte() }
        assertCode(FwxError.DAMAGED) { read(flipped) }
        assertCode(FwxError.DAMAGED) { read(file + byteArrayOf(0)) }
    }

    // Entries (parsed with the same function an export checks itself with, so no key derivation is needed)

    @Test
    fun theEntriesParseBackToTheData() {
        assertEquals(Samples.full, TunnelsBundle.check(TunnelsBundle.encode(Samples.full, "x")))
    }

    @Test
    fun manifestMustComeFirst() {
        val e = entries()
        e.add(0, e.removeAt(1))
        assertPayload(e)
    }

    @Test
    fun anEntryMissingOrAnUnknownEntryIsRefused() {
        for (name in TunnelsBundle.NAMES) assertPayload(entries().filter { it.first != name })
        assertPayload(entries() + ("extra.bin" to ByteArray(1)))
        assertPayload(emptyList())
    }

    @Test
    fun aDuplicateEntryIsRefused() {
        for (name in TunnelsBundle.NAMES) assertPayload(entries() + entries().first { it.first == name })
    }

    @Test
    fun countsThatDisagreeWithTheManifestAreRefused() {
        val e = entries()
        replace(e, "networks.txt", "")
        assertPayload(e)
        val f = entries()
        replace(f, "manifest.json", String(f[0].second).replace("\"snapshots\":3", "\"snapshots\":4"))
        assertPayload(f)
    }

    @Test
    fun theManifestIsStrict() {
        val good = String(entries()[0].second)
        for (bad in listOf(
            good.replace("tunnels", "lumen"),
            good.replace("\"schema\":1", "\"schema\":2"),
            good.replace("{", "{ "),
            good.replace("\"networks\":2", "\"networks\":-2"),
            good.replace("\"networks\":2", "\"networks\":9999999999"),
            good.replace(",\"networks\":2", ""),
            good.replace("\"test\"", "\"te\\\"st\""),
            good + "x",
            "[]",
            "",
        )) {
            val e = entries()
            replace(e, "manifest.json", bad)
            assertPayload(e)
        }
    }

    @Test
    fun invalidUtf8IsRefused() {
        val e = entries()
        e[1] = "snapshots.tsnap1" to byteArrayOf('T'.code.toByte(), 0xC3.toByte(), 0x28)
        assertPayload(e)
    }

    @Test
    fun aSnapshotsEntryThatIsNotTsnap1IsRefused() {
        for (text in listOf("", "TSNAP2\n", "TSNAP1\nbogus\trecord\n", "TSNAP1\nobs\t9\tx\ts\tk\tv\n")) {
            val e = entries(TunnelsData.EMPTY)
            replace(e, "snapshots.tsnap1", text)
            assertPayload(e)
        }
    }

    @Test
    fun aSnapshotsEntryOverTheCapsIsRefused() {
        val e = entries(TunnelsData.EMPTY)
        val rows = StringBuilder("TSNAP1\n")
        repeat(ParseLimits.IMPORT.maxSnapshots + 1) { rows.append("snapshot\t$it\t$it\t0\tx\n") }
        replace(e, "snapshots.tsnap1", rows.toString())
        assertPayload(e)

        val long = entries(TunnelsData.EMPTY)
        replace(long, "snapshots.tsnap1", "TSNAP1\nsnapshot\t1\t1\t0\t" + "x".repeat(ParseLimits.IMPORT.maxLineChars + 1))
        assertPayload(long)
    }

    @Test
    fun anOversizedSmallEntryIsRefused() {
        val e = entries()
        replace(e, "networks.txt", "0".repeat(TunnelsBundle.MAX_SMALL_ENTRY_BYTES + 1))
        assertPayload(e)
    }

    @Test
    fun settingsRowsAreStrictAndOnlyCarryKnownKeys() {
        val ok = String(entries().first { it.first == "settings.tsv" }.second)
        for (bad in listOf(
            "",
            "TSET2\n",
            ok + "traffic.block\ton=true\n",
            ok + "pairing.verifierId\t00\n",
            ok + "watch.status\trun=1\n",
            "TSET1\nnotatab\n",
            "TSET1\n\tvalue\n",
        )) {
            val e = entries()
            replace(e, "settings.tsv", bad)
            assertPayload(e)
        }
    }

    @Test
    fun aSettingIsStoredAsItsOwnersCanonicalText() {
        val e = entries(TunnelsData.EMPTY)
        replace(e, "settings.tsv", "TSET1\nwatch.settings\tenabled=true;interval=7;notify=BOGUS;extra=nothing\n")
        replace(e, "manifest.json", String(e[0].second).replace("\"settings\":0", "\"settings\":1"))
        val data = TunnelsBundle.check(e.map { EncodedEntry(it.first, it.second, it.second.size) })
        // An interval and a level the app does not offer fall back to its defaults; the file's own text is never kept.
        assertEquals(io.github.stronghorse44.tunnels.watchrules.WatchSettings(enabled = true).encode(), data.settings["watch.settings"])
    }

    @Test
    fun pinsMustBeWellFormedAndUnique() {
        val line = Samples.pins.lines().first()
        for (bad in listOf("not a pin\n", line + "\n" + line, line.replace("Pixel", "Pix|el"), line.replaceFirst(Regex("^[0-9a-f]+"), "0".repeat(64)))) {
            val e = entries()
            replace(e, "pairing-pins.txt", bad)
            assertPayload(e)
        }
        val tooMany = entries()
        replace(tooMany, "pairing-pins.txt", (0..BundleSettings.MAX_PINS).joinToString("\n") { Samples.pin(it).let { p -> io.github.stronghorse44.tunnels.pairing.Pin.encode(listOf(p)) } })
        assertPayload(tooMany)
    }

    @Test
    fun networksMustBeLowercaseSha256ThatAppearOnce() {
        val a = "0f".repeat(32)
        for (bad in listOf("zz\n", a.uppercase() + "\n", a + "\n" + a + "\n", a.drop(1) + "\n", "$a $a\n")) {
            val e = entries()
            replace(e, "networks.txt", bad)
            assertPayload(e)
        }
    }

    @Test
    fun encodingTheSameDataTwiceGivesTheSameEntries() {
        val a = TunnelsBundle.encode(Samples.full, "x")
        val b = TunnelsBundle.encode(Samples.full, "x")
        for (i in a.indices) assertTrue(a[i].bytes.copyOf(a[i].length).contentEquals(b[i].bytes.copyOf(b[i].length)))
    }
}
