package io.github.stronghorse44.tunnels.convert

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Reads RTF text and its basic formatting: bold, italic, underline, strike, font size, alignment, list
 * markers, outline-level headings, tabs, line and page breaks, table rows (cells joined by tabs).
 * Pictures, objects, headers, footers, footnotes, comments and field codes are skipped; a field's shown
 * result is kept. Code pages come from `\ansicpg` and each font's `\fcharset`.
 */
object RtfReader {
    fun read(bytes: ByteArray, limits: ConvertLimits = ConvertLimits.DEFAULT): Document {
        if (bytes.size > limits.maxInputBytes) throw ConvertException(ConvertError.LimitExceeded("The file is larger than ${limits.maxInputBytes / (1024 * 1024)} MB."))
        if (!String(bytes, 0, minOf(bytes.size, 16), Charsets.ISO_8859_1).trimStart().startsWith("{\\rtf")) {
            throw ConvertException(ConvertError.Damaged("This isn't an RTF file: it doesn't start with {\\rtf."))
        }
        return RtfParser(bytes, limits).parse()
    }
}

private class RtfParser(private val src: ByteArray, private val limits: ConvertLimits) {
    private enum class Dest { BODY, SKIP, LIST_TEXT, FONT_TABLE }

    private data class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var halfPoints: Int = 24,
        var uc: Int = 1,
        var font: Int = -1,
        var dest: Dest = Dest.BODY,
    )

    private var pos = 0
    private val stack = ArrayDeque<State>()
    private var st = State()
    private var groupStart = false

    private val blocks = mutableListOf<Block>()
    private val runs = RunBuilder()
    private var chars = 0

    private var align = Align.START
    private var heading = 0
    private var level = 0
    private var listActive = false
    private var listText: StringBuilder? = null
    private var pendingCellTab = false

    private var docCharset: Charset = Charset.forName("windows-1252")
    private val fontCharsets = HashMap<Int, Int>()
    private var tableFont = -1
    private val pendingBytes = ByteArrayOutputStream()
    private var skipChars = 0

    fun parse(): Document {
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            when (c) {
                '{'.code -> {
                    flushBytes()
                    pos++
                    skipChars = 0
                    stack.addLast(st.copy())
                    if (stack.size > limits.maxDepth) throw ConvertException(ConvertError.LimitExceeded("RTF groups are nested deeper than ${limits.maxDepth}."))
                    groupStart = true
                    continue
                }
                '}'.code -> {
                    flushBytes()
                    pos++
                    skipChars = 0
                    closeGroup()
                    if (stack.isEmpty()) break
                    continue
                }
                '\\'.code -> controlWord()
                '\r'.code, '\n'.code -> pos++
                else -> {
                    pos++
                    if (skipChars > 0) {
                        skipChars--
                    } else if (c < 0x80) {
                        flushBytes()
                        emit(c.toChar().toString())
                    } else {
                        pendingBytes.write(c)
                    }
                }
            }
            groupStart = false
        }
        flushBytes()
        if (!runs.isEmpty) endParagraph()
        while (blocks.lastOrNull().let { it is PageBreak || (it is Paragraph && it.text.isBlank()) }) blocks.removeAt(blocks.lastIndex)
        return Document(blocks.toList())
    }

    private fun closeGroup() {
        val closing = st
        st = stack.removeLastOrNull() ?: State()
        if (closing.dest == Dest.LIST_TEXT && st.dest != Dest.LIST_TEXT) {
            // The list marker group ends: keep what it showed as this paragraph's bullet.
            listText = StringBuilder(listMarker(listText?.toString().orEmpty()))
        }
    }

    private fun controlWord() {
        pos++ // backslash
        if (pos >= src.size) return
        val c = src[pos].toInt() and 0xFF
        if (!c.isLetter()) {
            pos++
            controlSymbol(c)
            return
        }
        val start = pos
        while (pos < src.size && (src[pos].toInt() and 0xFF).isLetter() && pos - start < 32) pos++
        val word = String(src, start, pos - start, Charsets.ISO_8859_1)
        var param: Int? = null
        if (pos < src.size && (src[pos] == '-'.code.toByte() || src[pos].isDigit())) {
            val ps = pos
            if (src[pos] == '-'.code.toByte()) pos++
            while (pos < src.size && src[pos].isDigit() && pos - ps < 11) pos++
            param = String(src, ps, pos - ps, Charsets.ISO_8859_1).toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        }
        if (pos < src.size && src[pos] == ' '.code.toByte()) pos++
        if (word != "u") flushBytes()
        val first = groupStart
        groupStart = false
        handleWord(word, param, first)
    }

    private fun controlSymbol(c: Int) {
        when (c.toChar()) {
            '\'' -> {
                val hex = if (pos + 2 <= src.size) String(src, pos, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                if (hex != null) pos += 2
                if (skipChars > 0) skipChars-- else if (hex != null) pendingBytes.write(hex)
                return
            }
            '*' -> { flushBytes(); st.dest = Dest.SKIP }
            '\\', '{', '}' -> { flushBytes(); emitOrSkip(c.toChar().toString()) }
            '~' -> { flushBytes(); emitOrSkip(" ") }
            '_' -> { flushBytes(); emitOrSkip("‑") }
            '-' -> flushBytes()
            '\n', '\r' -> { flushBytes(); if (st.dest == Dest.BODY) endParagraph() }
            else -> flushBytes()
        }
    }

    private fun handleWord(word: String, param: Int?, first: Boolean) {
        if (first) {
            when (word) {
                in skipped -> { st.dest = Dest.SKIP; return }
                "fonttbl" -> { st.dest = Dest.FONT_TABLE; return }
                "pntext", "listtext" -> {
                    if (st.dest == Dest.BODY) {
                        st.dest = Dest.LIST_TEXT
                        listText = StringBuilder()
                    }
                    return
                }
            }
        }
        if (word == "bin") {
            pos = (pos + (param ?: 0).coerceAtLeast(0)).coerceAtMost(src.size)
            return
        }
        if (word == "u") {
            val code = (param ?: 0).let { if (it < 0) it + 65536 else it }
            flushBytes()
            emitOrSkip(code.toChar().toString())
            skipChars = st.uc
            return
        }
        when (st.dest) {
            Dest.SKIP -> return
            Dest.FONT_TABLE -> {
                when (word) {
                    "f" -> tableFont = param ?: -1
                    "fcharset" -> if (tableFont >= 0 && param != null) fontCharsets[tableFont] = param
                }
                return
            }
            else -> Unit
        }
        when (word) {
            "ansicpg" -> param?.let { codePage(it) }?.let { docCharset = it }
            "uc" -> st.uc = (param ?: 1).coerceIn(0, 8)
            "f" -> st.font = param ?: -1
            "par", "sect" -> endParagraph()
            "page" -> { endParagraph(); blocks.add(PageBreak) }
            "line" -> emitOrSkip("\n")
            "tab" -> emitOrSkip("\t")
            "cell", "nestcell" -> pendingCellTab = true
            "row", "nestrow" -> { pendingCellTab = false; endParagraph() }
            "pard" -> {
                align = Align.START
                heading = 0
                level = 0
                listActive = false
            }
            "plain" -> {
                st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.halfPoints = 24
            }
            "b" -> st.bold = param != 0
            "i" -> st.italic = param != 0
            "ul", "uld", "uldb", "uldash", "uldashd", "uldashdd", "ulhwave", "ulth", "ulw", "ulwave", "ulthd", "ulthdash" -> st.underline = param != 0
            "ulnone" -> st.underline = false
            "strike", "striked" -> st.strike = param != 0
            "fs" -> st.halfPoints = (param ?: 24).coerceIn(2, 3276)
            "ql" -> align = Align.START
            "qc" -> align = Align.CENTER
            "qr" -> align = Align.END
            "qj", "qd" -> align = Align.JUSTIFY
            "outlinelevel" -> heading = ((param ?: -1) + 1).coerceIn(0, 6)
            "ls" -> listActive = (param ?: 0) > 0
            "ilvl" -> level = (param ?: 0).coerceIn(0, 8)
            "emdash" -> emitOrSkip("—")
            "endash" -> emitOrSkip("–")
            "bullet" -> emitOrSkip("•")
            "lquote" -> emitOrSkip("‘")
            "rquote" -> emitOrSkip("’")
            "ldblquote" -> emitOrSkip("“")
            "rdblquote" -> emitOrSkip("”")
            "emspace" -> emitOrSkip(" ")
            "enspace" -> emitOrSkip(" ")
            "qmspace" -> emitOrSkip(" ")
        }
    }

    /** A character produced by a control word counts against `\u`'s fallback skip like any other. */
    private fun emitOrSkip(s: String) {
        if (skipChars > 0) skipChars-- else emit(s)
    }

    private fun emit(s: String) {
        when (st.dest) {
            Dest.BODY -> {
                chars += s.length
                if (chars > limits.maxChars) throw ConvertException(ConvertError.LimitExceeded("The document has more than ${limits.maxChars} characters."))
                if (pendingCellTab) {
                    pendingCellTab = false
                    runs.append("\t", style())
                }
                runs.append(s, style())
            }
            Dest.LIST_TEXT -> listText?.append(s)
            Dest.SKIP, Dest.FONT_TABLE -> Unit
        }
    }

    private fun style() = Run(
        text = "",
        bold = st.bold,
        italic = st.italic,
        underline = st.underline,
        strike = st.strike,
        sizePt = if (st.halfPoints == 24) null else st.halfPoints / 2f,
    )

    private fun endParagraph() {
        if (st.dest != Dest.BODY && st.dest != Dest.LIST_TEXT) return
        flushBytes()
        pendingCellTab = false
        val marker = listText?.toString()?.takeIf { it.isNotBlank() } ?: if (listActive) "•" else null
        listText = null
        blocks.add(Paragraph(runs.take(), align, heading, marker, if (marker != null) level else 0))
        if (blocks.size > limits.maxChars) throw ConvertException(ConvertError.LimitExceeded("The document has too many paragraphs."))
    }

    private fun flushBytes() {
        if (pendingBytes.size() == 0) return
        val bytes = pendingBytes.toByteArray()
        pendingBytes.reset()
        val charsetId = fontCharsets[st.font]
        val text = when {
            charsetId == SYMBOL_CHARSET -> bytes.joinToString("") { symbolChar(it.toInt() and 0xFF) }
            charsetId != null && charsetId != 0 && charsetId != 1 -> String(bytes, fontCharsetMap[charsetId]?.let(::codePage) ?: docCharset)
            else -> String(bytes, docCharset)
        }
        emit(text)
    }

    private fun Byte.isDigit() = this in '0'.code.toByte()..'9'.code.toByte()

    private fun Int.isLetter() = this in 'a'.code..'z'.code || this in 'A'.code..'Z'.code

    companion object {
        private const val SYMBOL_CHARSET = 2

        private val skipped = setOf(
            "colortbl", "stylesheet", "info", "pict", "object", "objdata", "header", "headerl", "headerr", "headerf",
            "footer", "footerl", "footerr", "footerf", "footnote", "annotation", "fldinst", "themedata",
            "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl",
            "generator", "pn", "nonshppict", "shp", "shpinst", "bkmkstart", "bkmkend", "revtbl", "filetbl",
            "mmathPr", "userprops", "docvar", "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "ftncn", "aftncn",
            "template", "atnid", "atnauthor", "author", "operator", "title", "subject", "keywords", "comment",
            "doccomm", "company", "pgdsctbl", "protusertbl", "wgrffmtfilter", "listpicture", "background",
        )

        /** `\fcharset` number to Windows code page. */
        private val fontCharsetMap = mapOf(
            77 to 10000, 128 to 932, 129 to 949, 134 to 936, 136 to 950, 161 to 1253, 162 to 1254, 163 to 1258,
            177 to 1255, 178 to 1256, 186 to 1257, 204 to 1251, 222 to 874, 238 to 1250,
        )

        fun codePage(cp: Int): Charset? {
            if (cp == 65001) return Charsets.UTF_8
            val names = if (cp == 10000) listOf("x-MacRoman", "MacRoman") else listOf("windows-$cp", "cp$cp", "x-windows-$cp", "MS$cp")
            return names.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
        }

        /** The Symbol font's bullet and a few marks; the rest read as Windows-1252. */
        private fun symbolChar(b: Int): String = when (b) {
            0xB7 -> "•"
            0xA7 -> "▪"
            0xD8 -> "➢"
            else -> String(byteArrayOf(b.toByte()), Charset.forName("windows-1252"))
        }

        /** What a list marker group showed, made presentable: Word's Symbol bullets read as "·", "", "o" or "§". */
        fun listMarker(raw: String): String {
            val t = raw.replace('\t', ' ').trim()
            return when (t) {
                "·", "", "•", "" -> "•"
                "o" -> "◦"
                "§", "" -> "▪"
                else -> t
            }
        }
    }
}
