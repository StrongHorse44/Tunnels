package io.github.stronghorse44.tunnels.breaches

import java.time.LocalDate

/** One breach as the catalogue file carries it (specs/B11-linx.md section 9.2). Dates are `yyyy-MM-dd` text. */
data class BreachRow(
    val name: String,
    val title: String,
    /** Empty, or a lower-case ASCII host name of two or more labels. */
    val domain: String,
    val breachDate: String,
    val addedDate: String,
    val pwnCount: Long,
    /** A subsequence of [Catalogue.FLAG_ORDER]. */
    val flags: String,
    val dataClasses: List<String>,
)

/** The header of the catalogue file. [fetched] is `yyyy-MM-ddTHH:mm:ssZ`; [sourceSha256] is 64 lower-case hex. */
data class CatalogueMeta(
    val attribution: String,
    val fetched: String,
    val sourceSha256: String,
    val skipped: Int,
    val source: String = Catalogue.SOURCE,
    val licence: String = Catalogue.LICENCE,
)

/** What a catalogue file holds: its header and its rows (sorted by name). */
data class CatalogueContent(val meta: CatalogueMeta, val rows: List<BreachRow>)

/** Why a catalogue (an HIBP answer or a file) was refused as a whole. The message names the rule. */
class CatalogueException(message: String) : IllegalArgumentException(message)

/** The fixed text and limits of specs/B11-linx.md section 9. Frozen contract: copy, never paraphrase. */
object Catalogue {
    const val FORMAT = "fieldwork-breaches"
    const val VERSION = "1"
    const val SOURCE = "hibp-v3-breaches"
    const val LICENCE = "CC BY 4.0"
    const val ATTRIBUTION =
        "Breach data from Have I Been Pwned by Troy Hunt (haveibeenpwned.com), CC BY 4.0. " +
            "Reduced by Tunnels to names, titles, domains, dates, counts, flags and data classes."
    const val MIME_TYPE = "application/vnd.fieldwork.breaches"

    /** Verified, fabricated, sensitive, retired, spam list, malware, stealer log. */
    const val FLAG_ORDER = "VFSRPML"

    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_LINE_BYTES = 4 * 1024
    const val MAX_ROWS = 20_000
    const val MAX_ATTRIBUTION = 400
    const val MAX_TITLE = 120
    const val MAX_CLASSES = 40
    const val MAX_CLASS_CHARS = 60
    const val MAX_PWN_COUNT = 100_000_000_000L
    val MIN_DATE: LocalDate = LocalDate.of(1990, 1, 1)

    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    val FETCHED = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
    val SHA256 = Regex("[0-9a-f]{64}")

    /** A real calendar date in `yyyy-MM-dd` form, or null. */
    fun date(text: String): LocalDate? =
        if (!DATE.matches(text)) null else try { LocalDate.parse(text) } catch (_: java.time.DateTimeException) { null }

    /** The dates a row may carry: 1990-01-01 up to the day after [fetched]'s date. */
    fun dateInRange(d: LocalDate, fetchedDate: LocalDate): Boolean = !d.isBefore(MIN_DATE) && !d.isAfter(fetchedDate.plusDays(1))

    /**
     * [text] if it is a valid domain for a row (ASCII, lower-case, labels of 1 to 63 of `[a-z0-9-]` neither starting nor
     * ending with `-`, at least two labels, at most 253 characters, section 7.5), else null.
     */
    fun cleanDomain(text: String): String? {
        if (text.isEmpty() || text.length > 253) return null
        val labels = text.split('.')
        if (labels.size < 2) return null
        for (l in labels) {
            if (l.isEmpty() || l.length > 63) return null
            if (l.first() == '-' || l.last() == '-') return null
            if (l.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }) return null
        }
        return text
    }
}
