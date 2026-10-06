package io.github.stronghorse44.tunnels.breaches

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The `fieldwork-breaches 1` file of specs/B11-linx.md section 9.2: written here, read by Linx (B11c). This reader
 * exists so that the writer is tested against the contract, and it is as strict as the contract: any violation
 * refuses the whole file with [CatalogueException]; there is no partial result.
 *
 * Every line ends in LF, including the last. A file whose last line lacks that LF is also read (the contract does not
 * say), but never written.
 */
object CatalogueFile {
    private const val TAB = '\t'
    private const val COLUMNS = "columns\tname\ttitle\tdomain\tbreach_date\tadded_date\tpwn_count\tflags\tdata_classes"

    /** One row line, without its LF: `b` and the eight fields, tab separated. */
    fun rowLine(r: BreachRow): String = listOf(
        "b", r.name, r.title, r.domain, r.breachDate, r.addedDate, r.pwnCount.toString(), r.flags, r.dataClasses.joinToString(";"),
    ).joinToString(TAB.toString())

    /**
     * The file for [rows] (sorted by name here) under [meta]. Refuses, with [CatalogueException], anything the
     * reader would refuse: the result is read back before it is returned.
     */
    fun write(rows: List<BreachRow>, meta: CatalogueMeta): ByteArray {
        val sorted = rows.sortedBy { it.name }
        val sb = StringBuilder()
        fun line(vararg parts: String) = sb.append(parts.joinToString(TAB.toString())).append('\n')
        line(Catalogue.FORMAT, Catalogue.VERSION)
        line("source", meta.source)
        line("licence", meta.licence)
        line("attribution", meta.attribution)
        line("fetched", meta.fetched)
        line("source-sha256", meta.sourceSha256)
        line("skipped", meta.skipped.toString())
        line("count", sorted.size.toString())
        sb.append(COLUMNS).append('\n')
        for (r in sorted) sb.append(rowLine(r)).append('\n')
        line("end", sorted.size.toString())
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        if (read(bytes).rows != sorted) throw CatalogueException("A row would not read back as written (a `;` in a data class?).")
        return bytes
    }

    fun read(bytes: ByteArray): CatalogueContent {
        if (bytes.size > Catalogue.MAX_FILE_BYTES) throw CatalogueException("The file is larger than ${Catalogue.MAX_FILE_BYTES} bytes.")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw CatalogueException("The file is not valid UTF-8.")
        }
        if (text.startsWith('﻿')) throw CatalogueException("The file starts with a byte order mark.")
        var no = 0
        for (ch in text) {
            if (ch == '\r') throw CatalogueException("The file contains a carriage return.")
            if (ch != '\n' && ch != TAB && (ch.code < 0x20 || ch.code in 0x7f..0x9f)) throw CatalogueException("The file contains a control character.")
        }
        val lines = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        for (l in lines) {
            no++
            if (l.toByteArray(Charsets.UTF_8).size > Catalogue.MAX_LINE_BYTES) throw CatalogueException("Line $no is longer than ${Catalogue.MAX_LINE_BYTES} bytes.")
        }

        var at = 0
        fun next(): String = lines.getOrNull(at++) ?: throw CatalogueException("The file ends early: there is no `end` line.")
        fun header(key: String): String {
            val l = next()
            val parts = l.split(TAB)
            if (parts.size != 2 || parts[0] != key) throw CatalogueException("Line $at must be `$key` and its value.")
            return parts[1]
        }

        val format = header(Catalogue.FORMAT)
        if (format != Catalogue.VERSION) throw CatalogueException("Unsupported format version `$format`.")
        val source = header("source")
        if (source != Catalogue.SOURCE) throw CatalogueException("Unknown source `$source`.")
        val licence = header("licence")
        if (licence != Catalogue.LICENCE) throw CatalogueException("Unknown licence `$licence`.")
        val attribution = header("attribution")
        if (attribution.isEmpty() || attribution.length > Catalogue.MAX_ATTRIBUTION) {
            throw CatalogueException("The attribution must be 1 to ${Catalogue.MAX_ATTRIBUTION} characters.")
        }
        val fetched = header("fetched")
        val fetchedDate = fetchedDate(fetched) ?: throw CatalogueException("`fetched` is not a date and time like 2026-10-05T23:00:00Z.")
        val sha = header("source-sha256")
        if (!Catalogue.SHA256.matches(sha)) throw CatalogueException("`source-sha256` must be 64 lower-case hex characters.")
        val skipped = number(header("skipped"), Int.MAX_VALUE.toLong(), "skipped").toInt()
        val count = number(header("count"), Catalogue.MAX_ROWS.toLong(), "count").toInt()
        if (next() != COLUMNS) throw CatalogueException("Line $at must be the columns line.")

        val rows = ArrayList<BreachRow>(count)
        var previous: String? = null
        while (true) {
            val l = next()
            val f = l.split(TAB)
            if (f[0] == "end") {
                if (f.size != 2 || number(f[1], Catalogue.MAX_ROWS.toLong(), "end") != rows.size.toLong()) throw CatalogueException("`end` must repeat the number of rows (${rows.size}).")
                break
            }
            if (f[0] != "b" || f.size != 9) throw CatalogueException("Line $at must be a row (`b` and eight fields) or `end`.")
            if (rows.size >= Catalogue.MAX_ROWS) throw CatalogueException("More than ${Catalogue.MAX_ROWS} rows.")
            val row = row(f, fetchedDate, at)
            if (previous != null && row.name <= previous) throw CatalogueException("Line $at: names must be sorted and unique (`${row.name}` after `$previous`).")
            previous = row.name
            rows += row
        }
        if (at != lines.size) throw CatalogueException("There is data after the `end` line.")
        if (rows.size != count) throw CatalogueException("`count` says $count rows but the file has ${rows.size}.")
        return CatalogueContent(CatalogueMeta(attribution, fetched, sha, skipped, source, licence), rows)
    }

    private fun number(text: String, max: Long, what: String): Long {
        if (!Regex("0|[1-9][0-9]{0,18}").matches(text)) throw CatalogueException("`$what` must be a whole number.")
        val n = text.toLong()
        if (n > max) throw CatalogueException("`$what` is larger than $max.")
        return n
    }

    private fun fetchedDate(text: String): LocalDate? =
        if (!Catalogue.FETCHED.matches(text)) null else try { LocalDateTime.parse(text.removeSuffix("Z")).toLocalDate() } catch (_: DateTimeException) { null }

    private fun row(f: List<String>, fetchedDate: LocalDate, line: Int): BreachRow {
        fun bad(what: String): Nothing = throw CatalogueException("Line $line: $what.")
        val name = f[1]
        if (!Catalogue.NAME.matches(name)) bad("the name is not 1 to 64 letters, digits, dots, dashes or underscores")
        val title = f[2]
        if (title.isEmpty() || title.length > Catalogue.MAX_TITLE) bad("the title must be 1 to ${Catalogue.MAX_TITLE} characters")
        val domain = f[3]
        if (domain.isNotEmpty() && Catalogue.cleanDomain(domain) == null) bad("the domain is not a lower-case ASCII host name")
        val breach = Catalogue.date(f[4])?.takeIf { Catalogue.dateInRange(it, fetchedDate) } ?: bad("`breach_date` is not a date from 1990-01-01 to the day after `fetched`")
        val added = Catalogue.date(f[5])?.takeIf { Catalogue.dateInRange(it, fetchedDate) } ?: bad("`added_date` is not a date from 1990-01-01 to the day after `fetched`")
        if (!Regex("0|[1-9][0-9]{0,10}").matches(f[6]) || f[6].toLong() > Catalogue.MAX_PWN_COUNT) bad("`pwn_count` must be 0 to ${Catalogue.MAX_PWN_COUNT}")
        val flags = f[7]
        var from = 0
        for (c in flags) {
            val i = Catalogue.FLAG_ORDER.indexOf(c, from)
            if (i < 0) bad("`flags` must be letters of ${Catalogue.FLAG_ORDER} in that order, none twice")
            from = i + 1
        }
        val classes = if (f[8].isEmpty()) emptyList() else f[8].split(';')
        if (classes.size > Catalogue.MAX_CLASSES) bad("more than ${Catalogue.MAX_CLASSES} data classes")
        if (classes.any { it.isEmpty() || it.length > Catalogue.MAX_CLASS_CHARS }) bad("each data class must be 1 to ${Catalogue.MAX_CLASS_CHARS} characters")
        return BreachRow(name, title, domain, breach.toString(), added.toString(), f[6].toLong(), flags, classes)
    }
}
