package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

/**
 * The B11c finding (2026-10-06): Linx's reader refused 707 of 3,000 fuzzed Tunnels outputs, every one on a domain
 * that spec 7.5 forbids (a numeric last label, a residual `www.`). This test runs random HIBP-shaped inputs through
 * `HibpCatalogue.parse` and `CatalogueFile.write`, reads the output back, and checks every domain against a copy of
 * Linx's own rule: a stored domain must be a fixed point of its `normalise`.
 */
class DomainFuzzTest {
    /** Linx `DomainNames.normalise` (core/src/main/kotlin/io/github/stronghorse44/linx/auth/DomainNames.kt), copied. */
    private object Linx {
        private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

        fun normalise(input: String): String? {
            var s = input.trim()
            if (s.contains("://")) return null // a URL never reaches a catalogue row; Tunnels' reader refuses `:` and `/`
            if (s.any { it.code > 127 }) return null
            s = s.lowercase(Locale.ROOT)
            if (s.endsWith(".")) s = s.dropLast(1)
            if (s.startsWith("www.")) s = s.removePrefix("www.")
            if (s.isEmpty() || s.length > 253) return null
            val labels = s.split('.')
            if (labels.size < 2) return null
            if (!labels.all { label.matches(it) }) return null
            if (labels.last().all { it in '0'..'9' }) return null
            return s
        }

        fun isNormalised(domain: String): Boolean = normalise(domain) == domain
    }

    private val fetched = Instant.parse("2026-10-05T23:00:00Z")
    private val meta = CatalogueMeta(Catalogue.ATTRIBUTION, "2026-10-05T23:00:00Z", "0".repeat(64), 0)

    private val pieces = listOf(
        "example", "x", "a-b", "-a", "a-", "123", "0", "www", "WWW", "Www", "xn--bcher-kva", "a".repeat(63), "a".repeat(64),
        "bücher", "Kayak", "", " ", "a b", "a_b", "1.2.3.4", "com", "co.uk", "forum.beta", "a:443", "a/b",
    )

    private fun randomDomain(r: Random): String {
        val n = r.nextInt(0, 6)
        val sb = StringBuilder()
        repeat(r.nextInt(0, 3)) { sb.append(listOf("www.", "WWW.", "http://", " ")[r.nextInt(4)]) }
        repeat(n) { i ->
            if (i > 0) sb.append(if (r.nextInt(12) == 0) ".." else ".")
            sb.append(pieces[r.nextInt(pieces.size)])
        }
        repeat(r.nextInt(0, 3)) { sb.append(listOf(".", " ", "/path", ":8080")[r.nextInt(4)]) }
        return if (r.nextInt(10) == 0) sb.toString().uppercase(Locale.ROOT) else sb.toString()
    }

    private fun entry(r: Random, i: Int): String {
        val domain = randomDomain(r).replace("\\", "\\\\").replace("\"", "\\\"")
        val title = listOf("Shop", "A\tB", "Long ".repeat(30), "", "é")[r.nextInt(5)].replace("\t", "\\t")
        // Only shapes a row survives: this fuzz is about domains, and a refused input must mean a domain skipped a row.
        val classes = List(r.nextInt(0, 4)) { listOf("Passwords", "Email addresses", "a;b", "x".repeat(60))[r.nextInt(4)] }
        return """{"Name":"N$i","Title":"$title","Domain":"$domain","BreachDate":"2021-03-01","AddedDate":"2021-06-10T09:58:36Z","PwnCount":${r.nextLong(0, 200_000)},""" +
            """"DataClasses":[${classes.joinToString(",") { "\"$it\"" }}],"IsVerified":${r.nextBoolean()},"IsRetired":${r.nextBoolean()}}"""
    }

    @Test
    fun everyFuzzedOutputReadsBackAndEveryDomainIsNormalisedForLinx() {
        val r = Random(20261006)
        var rows = 0
        var nonEmpty = 0
        repeat(3_000) { round ->
            val json = "[" + List(r.nextInt(1, 6)) { entry(r, it) }.joinToString(",") + "]"
            val parsed = try {
                HibpCatalogue.parse(json.toByteArray(), fetched)
            } catch (e: CatalogueException) {
                fail("round $round: the input was refused as a whole: ${e.message}\n$json")
                return
            }
            assertEquals("round $round: a domain must never skip a row\n$json", 0, parsed.skipped)
            val bytes = try {
                CatalogueFile.write(parsed.rows, meta.copy(skipped = parsed.skipped))
            } catch (e: CatalogueException) {
                fail("round $round: the writer refused its own rows: ${e.message}\n$json")
                return
            }
            val back = CatalogueFile.read(bytes)
            assertEquals(parsed.rows, back.rows)
            for (row in back.rows) {
                rows++
                if (row.domain.isEmpty()) continue
                nonEmpty++
                assertTrue("round $round: Linx would refuse domain `${row.domain}`", Linx.isNormalised(row.domain))
                assertTrue("round $round: `${row.domain}` fails Tunnels' own rule", Catalogue.cleanDomain(row.domain) == row.domain)
            }
        }
        assertTrue("the fuzz produced rows ($rows)", rows > 3_000)
        assertTrue("some domains survived ($nonEmpty)", nonEmpty > 200)
    }

    @Test
    fun tunnelsRuleEqualsLinxRuleOnRandomStrings() {
        // Whatever Tunnels' writer keeps, Linx's isNormalised accepts; whatever Linx accepts as normalised, Tunnels keeps unchanged.
        val r = Random(7)
        repeat(20_000) {
            val d = randomDomain(r)
            val kept = Catalogue.normaliseDomain(d)
            if (kept != null) assertTrue("Linx refuses `$kept` (from `$d`)", Linx.isNormalised(kept))
            if (Linx.isNormalised(d)) assertEquals("Tunnels changes `$d`", d, Catalogue.normaliseDomain(d))
        }
    }
}
