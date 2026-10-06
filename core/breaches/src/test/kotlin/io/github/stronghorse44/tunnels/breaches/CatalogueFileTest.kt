package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogueFileTest {
    private val example = Section9Example.text

    private fun refused(text: String, mention: String? = null) = refused(text.toByteArray(Charsets.UTF_8), mention)

    private fun refused(bytes: ByteArray, mention: String? = null) {
        try {
            CatalogueFile.read(bytes)
            fail("accepted")
        } catch (e: CatalogueException) {
            if (mention != null) assertTrue(e.message, e.message!!.contains(mention))
        }
    }

    private fun row(name: String, extra: String = "") =
        "b\t$name\tTitle\texample.org\t2020-01-01\t2020-01-02\t10\tV\tEmail addresses$extra"

    /** A file of [rows] under the example's header. */
    private fun file(vararg rows: String, count: Int = rows.size, end: Int = rows.size): String =
        example.lines().take(7).joinToString("\n") + "\ncount\t$count\n" +
            "columns\tname\ttitle\tdomain\tbreach_date\tadded_date\tpwn_count\tflags\tdata_classes\n" +
            rows.joinToString("") { it + "\n" } + "end\t$end\n"

    @Test
    fun exampleParses() {
        val c = CatalogueFile.read(Section9Example.bytes)
        assertEquals("Test attribution, CC BY 4.0.", c.meta.attribution)
        assertEquals("2026-10-05T23:00:00Z", c.meta.fetched)
        assertEquals(0, c.meta.skipped)
        assertEquals(listOf("AlphaShop", "BetaForum", "GammaList"), c.rows.map { it.name })
        assertEquals(listOf("Email addresses", "Passwords"), c.rows[0].dataClasses)
        assertEquals("", c.rows[2].domain)
        assertEquals("P", c.rows[2].flags)
        assertEquals(700000L, c.rows[2].pwnCount)
        // Written again, it is the same bytes.
        assertTrue(Section9Example.bytes.contentEquals(CatalogueFile.write(c.rows, c.meta)))
    }

    @Test
    fun exampleWithoutTheLastLineFeedAlsoReads() {
        assertEquals(3, CatalogueFile.read(example.removeSuffix("\n").toByteArray()).rows.size)
    }

    @Test
    fun headerOrderIsFixed() {
        val lines = example.lines()
        // Swap two header lines, drop one, rename one, add one.
        refused((listOf(lines[0], lines[2], lines[1]) + lines.drop(3)).joinToString("\n"))
        refused((lines.take(3) + lines.drop(4)).joinToString("\n"))
        refused(example.replace("licence\t", "license\t"))
        refused(example.replace("columns\tname\ttitle", "columns\ttitle\tname"))
        refused(example.replace("fieldwork-breaches\t1", "fieldwork-breaches\t2"))
        refused(example.replace("source\thibp-v3-breaches", "source\telsewhere"))
        refused(example.replace("licence\tCC BY 4.0", "licence\tMIT"))
        refused(example.replace("skipped\t0", "extra\t0\nskipped\t0"))
        refused(example.replace("source-sha256\t" + "0".repeat(64), "source-sha256\t" + "A".repeat(64)))
        refused(example.replace("source-sha256\t" + "0".repeat(64), "source-sha256\t" + "0".repeat(63)))
        refused(example.replace("fetched\t2026-10-05T23:00:00Z", "fetched\t2026-10-05 23:00:00"))
        refused(example.replace("fetched\t2026-10-05T23:00:00Z", "fetched\t2026-13-05T23:00:00Z"))
        refused(example.replace("skipped\t0", "skipped\t-1"))
        refused(example.replace("skipped\t0", "skipped\t01"))
    }

    @Test
    fun countMismatchRefused() {
        refused(example.replace("count\t3", "count\t2"), "count")
        refused(example.replace("count\t3", "count\t4"), "count")
        refused(example.replace("end\t3", "end\t2"), "end")
        refused(example.replace("count\t3", "count\t20001"))
    }

    @Test
    fun missingEndRefused() {
        refused(example.substringBeforeLast("end\t"), "end")
        refused(example.replace("end\t3\n", ""), "end")
        refused(example + "b\tZed\tZed\t\t2020-01-01\t2020-01-02\t1\t\t\n")
        refused(example + "\n")
        refused(example + "end\t3\n")
        refused("")
    }

    @Test
    fun crRefused() {
        refused(example.replace("\n", "\r\n"), "carriage")
        refused(example.replace("Alpha Shop", "Alpha\rShop"), "carriage")
    }

    @Test
    fun controlCharRefused() {
        refused(example.replace("Alpha Shop", "Alpha\u0001Shop"), "control")
        refused(example.replace("Alpha Shop", "Alpha\u007fShop"), "control")
        refused(example.replace("Alpha Shop", "Alpha\u0085Shop"), "control")
        refused(example.replace("Alpha Shop", "Alpha\u009fShop"), "control")
        refused(example.replace("Alpha Shop", "Alpha\tShop"))
        refused("﻿$example", "byte order")
        refused(byteArrayOf(0xC3.toByte(), 0x28), "UTF-8")
        // Printable non-ASCII is fine.
        val c = CatalogueFile.read(example.replace("Alpha Shop", "Alpha Shöp 😀").toByteArray())
        assertEquals("Alpha Shöp 😀", c.rows[0].title)
    }

    @Test
    fun unsortedOrDuplicateNameRefused() {
        refused(file(row("Bravo"), row("Alpha")), "sorted")
        refused(file(row("Alpha"), row("Alpha")), "unique")
        refused(file(row("alpha"), row("Alpha")), "sorted") // byte order: upper case first
        CatalogueFile.read(file(row("Alpha"), row("alpha")).toByteArray())
        refused(file(row("1bad name")))
        refused(file(row("-Alpha")))
        refused(file(row("A".repeat(65))))
        CatalogueFile.read(file(row("A".repeat(64))).toByteArray())
    }

    @Test
    fun domainsOutsideTheNormalisedFormRefused() {
        // The reader matches Linx's: a stored domain is a fixed point of 7.5 normalisation, or empty.
        for (bad in listOf("example.123", "1.2.3.4", "www.alpha.example", "www.www.alpha.example", "alpha.example.", "Alpha.example", "alpha", "-a.example", "a-.example", "a..example", "a_b.example")) {
            refused(example.replace("\talpha.example\t", "\t$bad\t"), "domain")
        }
        for (ok in listOf("123.example", "xn--bcher-kva.example", "a.b.c.d.example", "wwwx.example", "www-x.example", "1-2.3x")) {
            CatalogueFile.read(example.replace("\talpha.example\t", "\t$ok\t").toByteArray())
        }
    }

    @Test
    fun badDateRefused() {
        refused(example.replace("2021-03-01", "2021-02-30"))
        refused(example.replace("2021-03-01", "2021-3-1"))
        refused(example.replace("2021-03-01", "1989-12-31"))
        refused(example.replace("2021-06-10", "20210610"))
        refused(example.replace("2021-06-10", "2021-06-10T00:00:00Z"))
        CatalogueFile.read(example.replace("2021-03-01", "1990-01-01").toByteArray())
    }

    @Test
    fun futureBreachDateRefused() {
        refused(example.replace("2021-03-01", "2026-10-07")) // fetched is 2026-10-05: only up to the 6th
        refused(example.replace("2021-06-10", "2026-10-07"))
        CatalogueFile.read(example.replace("2021-03-01", "2026-10-06").toByteArray())
    }

    @Test
    fun flagsOutOfOrderRefused() {
        refused(example.replace("\tVR\t", "\tRV\t"))
        refused(example.replace("\tVR\t", "\tVV\t"))
        refused(example.replace("\tVR\t", "\tvr\t"))
        refused(example.replace("\tVR\t", "\tVX\t"))
        CatalogueFile.read(example.replace("\tVR\t", "\tVFSRPML\t").toByteArray())
        CatalogueFile.read(example.replace("\tVR\t", "\t\t").toByteArray())
    }

    @Test
    fun semicolonInClassImpossible() {
        // The writer cannot be given one; the reader sees `;` only as the separator.
        val bad = BreachRow("A", "A", "", "2020-01-01", "2020-01-02", 1, "", listOf("x;y"))
        try {
            CatalogueFile.write(listOf(bad), CatalogueMeta("a", "2026-10-05T23:00:00Z", "0".repeat(64), 0))
            fail("wrote a class with a semicolon")
        } catch (_: CatalogueException) {
        }
        refused(file(row("A", ";")), "data class")
        refused(file(row("A", ";;x")), "data class")
        refused(file(row("A", ";" + "y".repeat(61))))
        CatalogueFile.read(file(row("A", ";" + "y".repeat(60))).toByteArray())
        val forty = List(40) { "c$it" }.joinToString(";")
        CatalogueFile.read(file("b\tA\tT\t\t2020-01-01\t2020-01-02\t1\t\t$forty").toByteArray())
        refused(file("b\tA\tT\t\t2020-01-01\t2020-01-02\t1\t\t$forty;c40"), "data classes")
    }

    @Test
    fun oversizeFileRefused() {
        refused(ByteArray(Catalogue.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }, "larger")
        // 20,001 rows is refused even when the file is small enough.
        val rows = (0..Catalogue.MAX_ROWS).map { "b\tN%06d\tT\t\t2020-01-01\t2020-01-02\t1\t\t".format(it) }
        refused(file(*rows.toTypedArray(), count = Catalogue.MAX_ROWS), "rows")
        CatalogueFile.read(file(*rows.take(Catalogue.MAX_ROWS).toTypedArray()).toByteArray())
    }

    @Test
    fun overlongLineRefused() {
        refused(example.replace("Test attribution, CC BY 4.0.", "x".repeat(5_000)), "longer")
        val titleLine = "b\tA\t${"é".repeat(120)}\t\t2020-01-01\t2020-01-02\t1\t\t" + List(40) { "é".repeat(60) }.joinToString(";")
        refused(file(titleLine), "longer") // over 4 KiB in bytes though under in characters
    }

    @Test
    fun attributionRequired() {
        refused(example.replace("attribution\tTest attribution, CC BY 4.0.", "attribution\t"))
        refused(example.replace("attribution\tTest attribution, CC BY 4.0.", "attribution"))
        refused(example.replace("Test attribution, CC BY 4.0.", "x".repeat(401)))
        CatalogueFile.read(example.replace("Test attribution, CC BY 4.0.", "x".repeat(400)).toByteArray())
        try {
            CatalogueFile.write(emptyList(), CatalogueMeta("", "2026-10-05T23:00:00Z", "0".repeat(64), 0))
            fail("wrote without an attribution")
        } catch (_: CatalogueException) {
        }
    }

    @Test
    fun pwnCountBoundaryIsInclusive() {
        val max = 100_000_000_000L
        fun withCount(n: String) = example.replace("\t120000\t", "\t$n\t")
        assertEquals(max, CatalogueFile.read(withCount(max.toString()).toByteArray()).rows[0].pwnCount)
        refused(withCount((max + 1).toString()), "pwn_count")
        refused(withCount("1000000000000"), "pwn_count")
        // The writer agrees: a row at the limit is written and read back, one above is refused.
        val meta = CatalogueMeta("a", "2026-10-05T23:00:00Z", "0".repeat(64), 0)
        val ok = BreachRow("A", "A", "", "2020-01-01", "2020-01-02", max, "", emptyList())
        assertEquals(max, CatalogueFile.read(CatalogueFile.write(listOf(ok), meta)).rows.single().pwnCount)
        try {
            CatalogueFile.write(listOf(ok.copy(pwnCount = max + 1)), meta)
            fail("wrote above the limit")
        } catch (_: CatalogueException) {
        }
    }

    @Test
    fun hugeNumbersAreRefusedWithTheContractException() {
        val big = "9999999999999999999" // 19 digits, above Long.MAX_VALUE
        refused(example.replace("skipped\t0", "skipped\t$big"), "skipped")
        refused(example.replace("end\t3", "end\t$big"), "end")
        refused(example.replace("count\t3", "count\t$big"), "count")
        refused(example.replace("skipped\t0", "skipped\t${"9".repeat(40)}"), "skipped")
        refused(example.replace("skipped\t0", "skipped\t2147483648"), "skipped")
    }

    @Test
    fun theAttributionTunnelsWritesFitsAndIsFrozen() {
        assertTrue(Catalogue.ATTRIBUTION.length in 1..Catalogue.MAX_ATTRIBUTION)
        assertEquals(
            "Breach data from Have I Been Pwned by Troy Hunt (haveibeenpwned.com), CC BY 4.0. " +
                "Reduced by Tunnels to names, titles, domains, dates, counts, flags and data classes.",
            Catalogue.ATTRIBUTION,
        )
    }

    @Test
    fun writerSortsAndRoundTrips() {
        val rows = CatalogueFile.read(Section9Example.bytes).rows
        val bytes = CatalogueFile.write(rows.reversed(), CatalogueFile.read(Section9Example.bytes).meta)
        assertTrue(Section9Example.bytes.contentEquals(bytes))
    }

    @Test
    fun writerRefusesWhatTheReaderWould() {
        val meta = CatalogueMeta("a", "2026-10-05T23:00:00Z", "0".repeat(64), 0)
        val ok = BreachRow("A", "A", "", "2020-01-01", "2020-01-02", 1, "", emptyList())
        for (bad in listOf(
            ok.copy(name = "bad name"), ok.copy(title = ""), ok.copy(title = "x".repeat(121)), ok.copy(domain = "Upper.example"),
            ok.copy(breachDate = "2026-10-07"), ok.copy(flags = "FV"), ok.copy(pwnCount = -1), ok.copy(pwnCount = Catalogue.MAX_PWN_COUNT + 1),
            ok.copy(title = "a\tb"),
        )) {
            try {
                CatalogueFile.write(listOf(bad), meta)
                fail("wrote $bad")
            } catch (_: CatalogueException) {
            }
        }
        try {
            CatalogueFile.write(listOf(ok, ok), meta)
            fail("wrote a duplicate")
        } catch (_: CatalogueException) {
        }
        CatalogueFile.write(listOf(ok), meta)
        assertEquals(1, CatalogueFile.read(CatalogueFile.write(listOf(ok), meta)).rows.size)
    }
}
