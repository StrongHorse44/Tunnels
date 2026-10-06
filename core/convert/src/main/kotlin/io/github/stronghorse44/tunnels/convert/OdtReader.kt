package io.github.stronghorse44.tunnels.convert

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads the body of an OpenDocument text (.odt) file: paragraphs, headings, list items, spans styled bold,
 * italic, underline, strike or sized by the file's automatic styles, alignment, tabs, spaces, line breaks and
 * table rows (cells joined by tabs). Notes, frames' pictures and tracked deletions are left out.
 */
object OdtReader {
    const val PART = "content.xml"

    fun read(bytes: ByteArray, limits: ConvertLimits = ConvertLimits.DEFAULT): Document {
        val xml = ZipParts.read(bytes, PART, limits, "OpenDocument file")
        val handler = OdtHandler(limits)
        ZipParts.parse(xml, handler, "OpenDocument file")
        return handler.document()
    }
}

private class OdtHandler(private val limits: ConvertLimits) : DefaultHandler() {
    private data class TextStyle(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val sizePt: Float? = null,
        val align: Align? = null,
    )

    private val styles = HashMap<String, TextStyle>()
    private var definingStyle: String? = null

    private val blocks = mutableListOf<Block>()
    private var runs: RunBuilder? = null
    private var paraStyle = TextStyle()
    private var heading = 0
    private var listDepth = 0
    private var inListItem = false
    private val spanStyles = ArrayDeque<TextStyle>()
    private var skipDepth = 0
    private var chars = 0

    private var tableDepth = 0
    private var rowRuns: RunBuilder? = null
    private var cellCount = 0
    private var cellHasText = false

    fun document(): Document {
        while (blocks.lastOrNull().let { it is PageBreak || (it is Paragraph && it.text.isBlank()) }) blocks.removeAt(blocks.lastIndex)
        return Document(blocks.toList())
    }

    private fun Attributes.get(nsSuffix: String, name: String): String? {
        for (i in 0 until length) if (getLocalName(i) == name && getURI(i).endsWith(nsSuffix)) return getValue(i)
        return null
    }

    override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
        if (skipDepth > 0) { skipDepth++; return }
        when {
            uri.endsWith(STYLE) && localName == "style" -> definingStyle = atts.get(STYLE, "name")
            uri.endsWith(STYLE) && (localName == "text-properties" || localName == "paragraph-properties") -> defineStyle(localName, atts)
            uri.endsWith(TABLE) -> when (localName) {
                "table" -> tableDepth++
                "table-row" -> if (tableDepth == 1) { rowRuns = RunBuilder(); cellCount = 0 }
                "table-cell", "covered-table-cell" -> if (tableDepth == 1) {
                    if (cellCount++ > 0) rowRuns?.append("\t", Run(""))
                    cellHasText = false
                }
            }
            uri.endsWith(TEXT) -> when (localName) {
                "p", "h" -> {
                    runs = RunBuilder()
                    paraStyle = styles[atts.get(TEXT, "style-name")] ?: TextStyle()
                    heading = if (localName == "h") (atts.get(TEXT, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6) else 0
                }
                "list" -> listDepth++
                "list-item" -> inListItem = true
                "span", "a" -> spanStyles.addLast(styles[atts.get(TEXT, "style-name")] ?: TextStyle())
                "tab" -> text("\t")
                "line-break" -> text("\n")
                "s" -> text(" ".repeat((atts.get(TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 1000)))
                "note", "tracked-changes", "deletion" -> skipDepth = 1
            }
            uri.endsWith(OFFICE) && localName == "annotation" -> skipDepth = 1
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (skipDepth > 0) { skipDepth--; return }
        when {
            uri.endsWith(STYLE) && localName == "style" -> definingStyle = null
            uri.endsWith(TABLE) -> when (localName) {
                "table" -> tableDepth--
                "table-row" -> if (tableDepth == 1) {
                    rowRuns?.take()?.let { blocks.add(Paragraph(it)) }
                    rowRuns = null
                }
            }
            uri.endsWith(TEXT) -> when (localName) {
                "p", "h" -> endParagraph()
                "list" -> listDepth--
                "span", "a" -> spanStyles.removeLastOrNull()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skipDepth == 0 && runs != null) {
            // Whitespace runs collapse to one space in ODF; spaces that matter are written as <text:s/>.
            text(String(ch, start, length).replace(Regex("[ \\t\\r\\n]+"), " "))
        }
    }

    private fun defineStyle(element: String, atts: Attributes) {
        val name = definingStyle ?: return
        val old = styles[name] ?: TextStyle()
        styles[name] = if (element == "text-properties") {
            old.copy(
                bold = atts.get(FO, "font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 } ?: old.bold,
                italic = atts.get(FO, "font-style")?.let { it == "italic" || it == "oblique" } ?: old.italic,
                underline = atts.get(STYLE, "text-underline-style")?.let { it != "none" } ?: old.underline,
                strike = atts.get(STYLE, "text-line-through-style")?.let { it != "none" } ?: old.strike,
                sizePt = atts.get(FO, "font-size")?.removeSuffix("pt")?.toFloatOrNull()?.coerceIn(1f, 1638f) ?: old.sizePt,
            )
        } else {
            old.copy(
                align = when (atts.get(FO, "text-align")) {
                    "center" -> Align.CENTER
                    "end", "right" -> Align.END
                    "justify" -> Align.JUSTIFY
                    null -> old.align
                    else -> Align.START
                },
            )
        }
    }

    private fun style(): Run {
        var s = paraStyle
        spanStyles.forEach { o ->
            s = TextStyle(o.bold ?: s.bold, o.italic ?: s.italic, o.underline ?: s.underline, o.strike ?: s.strike, o.sizePt ?: s.sizePt)
        }
        return Run("", s.bold == true, s.italic == true, s.underline == true, s.strike == true, s.sizePt?.takeIf { it != 12f })
    }

    private fun text(s: String) {
        val r = runs ?: return
        chars += s.length
        if (chars > limits.maxChars) throw ConvertException(ConvertError.LimitExceeded("The document has more than ${limits.maxChars} characters."))
        r.append(s, style())
    }

    private fun endParagraph() {
        val runs = this.runs?.take() ?: return
        this.runs = null
        val row = rowRuns
        if (tableDepth > 0 && row != null) {
            if (cellHasText && runs.isNotEmpty()) row.append(" ", Run(""))
            runs.forEach { row.append(it.text, it.copy(text = "")) }
            if (runs.isNotEmpty()) cellHasText = true
            return
        }
        val bullet = if (inListItem && listDepth > 0) "•" else null
        inListItem = false
        blocks.add(Paragraph(trimRuns(runs), paraStyle.align ?: Align.START, heading, bullet, if (bullet != null) listDepth - 1 else 0))
    }

    /** ODF paragraphs often start or end with a collapsed space from the XML's indentation. */
    private fun trimRuns(runs: List<Run>): List<Run> {
        if (runs.isEmpty()) return runs
        val out = runs.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart(' '))
        out[out.lastIndex] = out.last().copy(text = out.last().text.trimEnd(' '))
        return out.filter { it.text.isNotEmpty() }
    }

    companion object {
        const val TEXT = ":text:1.0"
        const val STYLE = ":style:1.0"
        const val TABLE = ":table:1.0"
        const val OFFICE = ":office:1.0"
        const val FO = ":xsl-fo-compatible:1.0"
    }
}
