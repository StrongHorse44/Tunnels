package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant

class HibpCatalogueTest {
    private val fixture: ByteArray = javaClass.getResourceAsStream("/hibp-fixture.json")!!.use { it.readBytes() }
    private val fetched = Instant.parse("2026-10-05T23:00:00Z")

    private fun refused(bytes: ByteArray, fetched: Instant? = null) {
        try {
            HibpCatalogue.parse(bytes, fetched)
            fail("accepted")
        } catch (_: CatalogueException) {
        }
    }

    private fun refused(json: String) = refused(json.toByteArray())

    private fun one(extra: String): BreachRow {
        val json = """[{"Name":"One","Title":"One","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1$extra}]"""
        return HibpCatalogue.parse(json.toByteArray()).rows.single()
    }

    private fun titled(title: String): String = titledNamed("One", title)

    private fun titledNamed(name: String, title: String): String =
        HibpCatalogue.parse("""[{"Name":"$name","Title":"$title","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1}]""".toByteArray()).rows.single().title

    @Test
    fun representableRowsKept() {
        val p = HibpCatalogue.parse(fixture, fetched)
        assertEquals(listOf("AlphaShop", "BetaForum", "DeltaIdn", "EpsilonLongTitle", "GammaList"), p.rows.map { it.name })
        val alpha = p.rows.first()
        assertEquals(
            BreachRow("AlphaShop", "Alpha Shop", "alpha.example", "2021-03-01", "2021-06-10", 120000, "V", listOf("Email addresses", "Passwords")),
            alpha,
        )
        val beta = p.rows[1]
        assertEquals("VR", beta.flags)
        assertEquals("2020-01-02", beta.addedDate)
        assertEquals("P", p.rows.last().flags)
        assertEquals("", p.rows.last().domain)
        assertEquals("S", p.rows[3].flags)
    }

    @Test
    fun badRowsSkippedAndCounted() {
        val p = HibpCatalogue.parse(fixture)
        assertEquals(1, p.skipped) // ZetaBadDate
        assertFalse(p.rows.any { it.name == "ZetaBadDate" })
        val mixed = """[
            {"Name":"Ok","Title":"Ok","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Title":"No name","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Name":"NoDate","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Name":"bad name","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Name":"-lead","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Name":"Old","BreachDate":"1989-12-31","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1},
            {"Name":"NoAdded","BreachDate":"2020-01-01","PwnCount":1},
            {"Name":"NoCount","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z"},
            {"Name":"NegCount","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":-1},
            {"Name":"HugeCount","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":100000000001},
            {"Name":"FracCount","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1.5},
            {"Name":"BadFlag","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"IsVerified":"yes"},
            {"Name":"BadClasses","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"DataClasses":[1]},
            {"Name":"Ok","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":2},
            "not an object", 7, null
        ]"""
        val m = HibpCatalogue.parse(mixed.toByteArray())
        assertEquals(listOf("Ok"), m.rows.map { it.name })
        assertEquals(1L, m.rows.single().pwnCount) // the first of two duplicates wins
        assertEquals(16, m.skipped) // 12 bad objects, the duplicate and three non-objects, 
    }

    @Test
    fun datesAfterTheFetchAreSkipped() {
        val json = """[
            {"Name":"Now","BreachDate":"2026-10-06","AddedDate":"2026-10-05T01:00:00Z","PwnCount":1},
            {"Name":"Future","BreachDate":"2026-10-07","AddedDate":"2026-10-05T01:00:00Z","PwnCount":1},
            {"Name":"FutureAdded","BreachDate":"2026-10-01","AddedDate":"2026-10-07T00:00:00Z","PwnCount":1}
        ]"""
        val p = HibpCatalogue.parse(json.toByteArray(), fetched)
        assertEquals(listOf("Now"), p.rows.map { it.name })
        assertEquals(2, p.skipped)
        // And without a fetch time nothing is judged against it.
        assertEquals(3, HibpCatalogue.parse(json.toByteArray()).rows.size)
    }

    @Test
    fun addedDateIsTheUtcDay() {
        assertEquals("2020-01-02", one("").addedDate)
        val j = """[{"Name":"Tz","BreachDate":"2020-01-01","AddedDate":"2020-01-02T01:30:00+03:00","PwnCount":1}]"""
        assertEquals("2020-01-01", HibpCatalogue.parse(j.toByteArray()).rows.single().addedDate)
    }

    @Test
    fun nonAsciiDomainEmptied() {
        val p = HibpCatalogue.parse(fixture)
        assertEquals("", p.rows.first { it.name == "DeltaIdn" }.domain)
        assertEquals("", one(",\"Domain\":\"example\"").domain) // one label
        assertEquals("", one(",\"Domain\":\"a b.example\"").domain)
        assertEquals("", one(",\"Domain\":\"-a.example\"").domain)
        assertEquals("", one(",\"Domain\":\"" + "a".repeat(64) + ".example\"").domain)
        assertEquals("", one(",\"Domain\":\"https://x.example/\"").domain)
        assertEquals("x.example", one(",\"Domain\":\"X.Example\"").domain)
        assertEquals("x.example", one(",\"Domain\":\"www.x.example.\"").domain)
        assertEquals("xn--bcher-kva.example", one(",\"Domain\":\"xn--bcher-kva.example\"").domain)
    }

    @Test
    fun titleTruncated() {
        val long = HibpCatalogue.parse(fixture).rows.first { it.name == "EpsilonLongTitle" }.title
        assertEquals(Catalogue.MAX_TITLE, long.length)
        assertTrue(long.endsWith("..."))
        assertTrue(long.startsWith("An extremely long title"))
        assertEquals("x".repeat(120), titled("x".repeat(120)))
        assertEquals("x".repeat(117) + "...", titled("x".repeat(121)))
        // A surrogate pair is never cut in half.
        val emoji = "😀".repeat(100)
        val cut = HibpCatalogue.truncate(emoji)
        assertTrue(cut.length <= 120)
        assertTrue(Character.isLowSurrogate(cut[cut.length - 4]))
        assertTrue(cut.endsWith("..."))
    }

    @Test
    fun titleAndClassesNeverCarryControlCharacters() {
        val r = HibpCatalogue.parse(
            """[{"Name":"One","Title":"A\tB\nC\u0085D\u007fE  F","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"DataClasses":["x\ty"," "]}]""".toByteArray(),
        ).rows.single()
        assertEquals("A B C D E F", r.title)
        assertEquals(listOf("x y"), r.dataClasses)
        assertEquals("Nm", titledNamed("Nm", "")) // an empty title falls back to the name
    }

    @Test
    fun semicolonReplaced() {
        val r = HibpCatalogue.parse(fixture).rows.first { it.name == "EpsilonLongTitle" }
        assertEquals(listOf("Email addresses,Passwords", "IP addresses"), r.dataClasses)
        assertFalse(r.dataClasses.any { ';' in it })
    }

    @Test
    fun tooManyOrTooLongDataClassesSkipTheRow() {
        val many = List(41) { "\"c$it\"" }.joinToString(",")
        val p = HibpCatalogue.parse(
            """[{"Name":"A","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"DataClasses":[$many]},
                {"Name":"B","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"DataClasses":["${"y".repeat(61)}"]},
                {"Name":"C","BreachDate":"2020-01-01","AddedDate":"2020-01-02T00:00:00Z","PwnCount":1,"DataClasses":["${"y".repeat(60)}"]}]""".toByteArray(),
        )
        assertEquals(listOf("C"), p.rows.map { it.name })
        assertEquals(2, p.skipped)
    }

    @Test
    fun descriptionAndLogoDropped() {
        val p = HibpCatalogue.parse(fixture, fetched)
        val file = String(CatalogueFile.write(p.rows, CatalogueMeta(Catalogue.ATTRIBUTION, "2026-10-05T23:00:00Z", "0".repeat(64), p.skipped)), Charsets.UTF_8)
        for (gone in listOf("<a href", "was breached", "AlphaShop.png", "logos", "Description", "LogoPath", "SomethingNew", "Subscribed", "ModifiedDate")) {
            assertFalse(gone, file.contains(gone))
        }
    }

    @Test
    fun outputMatchesSection9Example() {
        val input = """[
          {"Name":"GammaList","Title":"Gamma spam list","Domain":"","BreachDate":"2017-08-28","AddedDate":"2017-08-30T11:00:00Z","ModifiedDate":"2017-08-30T11:00:00Z",
           "PwnCount":700000,"Description":"x","LogoPath":"","DataClasses":["Email addresses"],"IsVerified":false,"IsFabricated":false,"IsSensitive":false,"IsRetired":false,"IsSpamList":true,"IsMalware":false,"IsStealerLog":false},
          {"Name":"AlphaShop","Title":"Alpha Shop","Domain":"alpha.example","BreachDate":"2021-03-01","AddedDate":"2021-06-10T09:58:36Z","ModifiedDate":"2021-06-10T09:58:36Z",
           "PwnCount":120000,"Description":"<b>x</b>","LogoPath":"https://logos.example/a.png","DataClasses":["Email addresses","Passwords"],"IsVerified":true,"IsFabricated":false,"IsSensitive":false,"IsRetired":false,"IsSpamList":false,"IsMalware":false,"IsStealerLog":false},
          {"Name":"BetaForum","Title":"Beta Forum","Domain":"forum.beta.example","BreachDate":"2019-07-15","AddedDate":"2020-01-02T00:00:01Z","ModifiedDate":"2020-01-02T00:00:01Z",
           "PwnCount":5000,"Description":"x","LogoPath":"","DataClasses":["Email addresses","Usernames"],"IsVerified":true,"IsFabricated":false,"IsSensitive":false,"IsRetired":true,"IsSpamList":false,"IsMalware":false,"IsStealerLog":false}
        ]"""
        val p = HibpCatalogue.parse(input.toByteArray(), fetched)
        val bytes = CatalogueFile.write(
            p.rows,
            CatalogueMeta("Test attribution, CC BY 4.0.", "2026-10-05T23:00:00Z", "0".repeat(64), p.skipped),
        )
        assertEquals(Section9Example.text, String(bytes, Charsets.UTF_8))
        assertTrue(Section9Example.bytes.contentEquals(bytes))
    }

    @Test
    fun tooDeepOrTooManyRefused() {
        refused("[[[[[1]]]]]")
        refused("""[{"Name":"A","DataClasses":[[["nested"]]]}]""")
        refused("[" + List(BreachJson.MAX_ITEMS + 1) { "{}" }.joinToString(",") + "]")
        refused("[{" + List(BreachJson.MAX_FIELDS + 1) { "\"k$it\":0" }.joinToString(",") + "}]")
        refused("""[{"Name":"A","Description":"${"x".repeat(BreachJson.MAX_STRING_CHARS + 1)}"}]""")
    }

    @Test
    fun emptyArrayRefused() {
        refused("[]")
        refused(" [ ] ")
        refused("[{}, {\"Name\":\"x\"}]") // nothing usable at all
        refused("[1, 2, 3]")
    }

    @Test
    fun notAnArrayRefused() {
        refused("{}")
        refused("{\"Name\":\"A\"}")
        refused("\"text\"")
        refused("null")
        refused("")
        refused("<html>Rate limited</html>")
        refused(byteArrayOf(0xC3.toByte(), 0x28)) // not UTF-8
        refused(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[]".toByteArray())
    }

    @Test
    fun noStringLiteralLooksLikeAnotherHost() {
        // gate/gate.py's host test: any other host-shaped literal in first-party code would join the baseline's hosts.
        val tlds = "com|net|org|io|dev|app|co|me|info|biz|xyz|ai|gov|edu|mil|int|us|uk|eu|de|fr|ca|au|jp|nl|ch|ru|cn|" +
            "tv|cc|gg|ly|to|page|cloud|site|online|tech|link|social|onion|arpa|local|lan"
        val host = Regex("(?<![A-Za-z0-9._%+-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:$tlds))(?![A-Za-z0-9-]|\\.[A-Za-z0-9])")
        val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val roots = listOf(File("src/main/kotlin"), File("../../tunnels/breaches/src/main/kotlin")).filter { it.isDirectory }
        assertTrue("the core sources are found", roots.isNotEmpty())
        val seen = mutableSetOf<String>()
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            for (line in f.readLines()) {
                val t = line.trim()
                if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) continue
                for (m in literal.findAll(line)) for (h in host.findAll(m.groupValues[1])) seen += h.groupValues[1]
            }
        }
        assertEquals(setOf(BreachSource.HOST), seen)
    }
}
