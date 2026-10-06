package io.github.stronghorse44.tunnels.breaches

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The rows Tunnels could represent, sorted by name, and how many entries it could not. */
data class ParsedCatalogue(val rows: List<BreachRow>, val skipped: Int)

/**
 * HIBP's "all breaches" answer (a JSON array, specs/B11-linx.md section 9.1) reduced to the rows of the catalogue
 * file: name, title, domain, dates, count, flags and data classes. Descriptions, logos and every other field are
 * never read into a row. The whole answer is refused when it is not a bounded JSON array of at least one usable
 * entry; a single entry that cannot be represented is skipped and counted, never repaired by guessing.
 */
object HibpCatalogue {
    /**
     * [fetched], when given, is the moment of the download: an entry dated after the day after it is skipped (a phone
     * whose clock is behind must not produce a file the reader refuses as a whole).
     */
    fun parse(bytes: ByteArray, fetched: Instant? = null): ParsedCatalogue {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw CatalogueException("The answer is not valid UTF-8.")
        }
        val root = try {
            BreachJson.parse(text)
        } catch (e: BreachJson.JsonException) {
            throw CatalogueException("The answer could not be read: ${e.message}.")
        }
        if (root !is List<*>) throw CatalogueException("The answer is not a list of breaches.")
        if (root.isEmpty()) throw CatalogueException("The answer lists no breaches.")

        val fetchedDate = fetched?.atOffset(ZoneOffset.UTC)?.toLocalDate()
        val byName = HashMap<String, BreachRow>()
        var skipped = 0
        for (entry in root) {
            val row = (entry as? Map<*, *>)?.let { toRow(it, fetchedDate) }
            if (row == null || byName.putIfAbsent(row.name, row) != null) skipped++
        }
        if (byName.isEmpty()) throw CatalogueException("None of the ${root.size} entries could be used.")
        return ParsedCatalogue(byName.values.sortedBy { it.name }, skipped)
    }

    private fun toRow(o: Map<*, *>, fetchedDate: LocalDate?): BreachRow? {
        val name = (o["Name"] as? String)?.takeIf { Catalogue.NAME.matches(it) } ?: return null
        val breach = (o["BreachDate"] as? String)?.let(Catalogue::date) ?: return null
        val added = (o["AddedDate"] as? String)?.let(::utcDate) ?: return null
        if (breach.isBefore(Catalogue.MIN_DATE) || added.isBefore(Catalogue.MIN_DATE)) return null
        if (fetchedDate != null && (!Catalogue.dateInRange(breach, fetchedDate) || !Catalogue.dateInRange(added, fetchedDate))) return null
        val pwn = when (val n = o["PwnCount"]) {
            is Long -> n
            else -> return null
        }
        if (pwn < 0 || pwn > Catalogue.MAX_PWN_COUNT) return null

        val flags = StringBuilder()
        for ((letter, field) in FLAG_FIELDS) {
            when (val v = o[field]) {
                true -> flags.append(letter)
                false, null -> Unit
                else -> return null
            }
        }

        val title = when (val t = o["Title"]) {
            null -> name
            is String -> cleanText(t).ifEmpty { name }
            else -> return null
        }
        val domain = when (val d = o["Domain"]) {
            null -> ""
            is String -> Catalogue.cleanDomain(normaliseDomain(d)) ?: ""
            else -> return null
        }
        val classes = when (val c = o["DataClasses"]) {
            null -> emptyList()
            is List<*> -> c.map { item -> (item as? String)?.let(::cleanClass) ?: return null }.filter { it.isNotEmpty() }
            else -> return null
        }
        if (classes.size > Catalogue.MAX_CLASSES || classes.any { it.length > Catalogue.MAX_CLASS_CHARS }) return null

        val row = BreachRow(name, truncate(title), domain, breach.toString(), added.toString(), pwn, flags.toString(), classes)
        // A row longer than the reader's line limit cannot be written; counting it beats cutting it.
        return if (CatalogueFile.rowLine(row).toByteArray(Charsets.UTF_8).size > Catalogue.MAX_LINE_BYTES) null else row
    }

    private val FLAG_FIELDS = listOf(
        'V' to "IsVerified", 'F' to "IsFabricated", 'S' to "IsSensitive", 'R' to "IsRetired",
        'P' to "IsSpamList", 'M' to "IsMalware", 'L' to "IsStealerLog",
    )

    /** `AddedDate`'s day in UTC; a date-time with an offset, without one (read as UTC), or a bare date. */
    private fun utcDate(text: String): LocalDate? {
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
        } catch (_: DateTimeException) {
        }
        try {
            return LocalDateTime.parse(text).toLocalDate()
        } catch (_: DateTimeException) {
        }
        return Catalogue.date(text)
    }

    /** Lower-case, one leading `www.` and a trailing dot stripped (section 7.5). Non-ASCII stays and fails the check. */
    private fun normaliseDomain(text: String): String {
        var d = text.trim().lowercase(java.util.Locale.ROOT)
        if (d.startsWith("www.")) d = d.removePrefix("www.")
        return d.removeSuffix(".")
    }

    /** Control characters (C0, DEL, C1) become spaces, runs of spaces one, unpaired surrogates U+FFFD; trimmed. */
    internal fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x20 || c.code in 0x7f..0x9f -> sb.append(' ')
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    sb.append(c).append(text[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> sb.append('�')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /** A data class name: cleaned like a title, and `;` (the list separator) becomes `,`. */
    private fun cleanClass(text: String): String = cleanText(text).replace(';', ',')

    /** At most 120 characters: longer titles keep 117 and end in `...` (never cutting a surrogate pair). */
    internal fun truncate(title: String): String {
        if (title.length <= Catalogue.MAX_TITLE) return title
        var end = Catalogue.MAX_TITLE - 3
        if (Character.isHighSurrogate(title[end - 1])) end--
        return title.substring(0, end).trimEnd() + "..."
    }
}
