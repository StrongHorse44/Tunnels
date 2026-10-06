package io.github.stronghorse44.tunnels.convert

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads the body of a Word (.docx) file: paragraphs, runs with bold, italic, underline, strike and size,
 * alignment, heading and title styles, list items, tabs, line and page breaks, and table rows (cells joined
 * by tabs). Text in text boxes is kept once. Deleted tracked changes, pictures, headers, footers and
 * comments are left out.
 */
object DocxReader {
    const val PART = "word/document.xml"

    fun read(bytes: ByteArray, limits: ConvertLimits = ConvertLimits.DEFAULT): Document {
        val xml = ZipParts.read(bytes, PART, limits, "Word file")
        val handler = DocxHandler(limits)
        ZipParts.parse(xml, handler, "Word file")
        return handler.document()
    }

    /** "Heading2" -> 2, "Title" -> 1; style ids are not localised in most files, names may be ("heading 2"). */
    fun headingLevel(styleId: String): Int {
        val s = styleId.lowercase().replace(" ", "")
        if (s == "title") return 1
        if (s == "subtitle") return 2
        val m = Regex("^heading([1-6])$").find(s) ?: return 0
        return m.groupValues[1].toInt()
    }
}

private class DocxHandler(private val limits: ConvertLimits) : DefaultHandler() {
    private class Para {
        val runs = RunBuilder()
        var align = Align.START
        var heading = 0
        var listed = false
        var level = 0
    }

    private val blocks = mutableListOf<Block>()
    private val paras = ArrayDeque<Para>()
    private var bold = false
    private var italic = false
    private var underline = false
    private var strike = false
    private var halfPoints: Int? = null
    private var inRunProps = false
    private var inParaProps = false
    private var inText = false
    private var skipDepth = 0
    private var chars = 0

    /** Table rows collect their cells' paragraphs; nested tables flatten into the outer cell. */
    private var tableDepth = 0
    private var rowRuns: RunBuilder? = null
    private var cellCount = 0
    private var cellHasText = false
    private var rowAlign = Align.START

    fun document(): Document {
        while (blocks.lastOrNull().let { it is PageBreak || (it is Paragraph && it.text.isBlank()) }) blocks.removeAt(blocks.lastIndex)
        return Document(blocks.toList())
    }

    private fun isW(uri: String) = uri.endsWith("/wordprocessingml/2006/main") || uri.endsWith("/wordprocessingml/main")

    private fun Attributes.w(name: String): String? {
        for (i in 0 until length) if (getLocalName(i) == name && isW(getURI(i))) return getValue(i)
        return null
    }

    /** `<w:b/>` is on; `<w:b w:val="0"/>` or "false" is off. */
    private fun Attributes.flag(): Boolean = w("val")?.lowercase() !in setOf("0", "false", "off", "none")

    override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
        if (skipDepth > 0) { skipDepth++; return }
        if (uri.endsWith("/markup-compatibility/2006") && localName == "Fallback") { skipDepth = 1; return }
        if (!isW(uri)) return
        when (localName) {
            "del", "delText", "instrText", "footnoteReference", "endnoteReference", "commentReference" -> skipDepth = 1
            "tbl" -> tableDepth++
            "tr" -> if (tableDepth == 1) { rowRuns = RunBuilder(); cellCount = 0; rowAlign = Align.START }
            "tc" -> if (tableDepth == 1) {
                if (cellCount++ > 0) rowRuns?.append("\t", Run(""))
                cellHasText = false
            }
            "p" -> paras.addLast(Para())
            "pPr" -> inParaProps = true
            "rPr" -> if (!inParaProps) inRunProps = true
            "jc" -> if (inParaProps) paras.lastOrNull()?.align = when (atts.w("val")) {
                "center" -> Align.CENTER
                "right", "end" -> Align.END
                "both", "distribute" -> Align.JUSTIFY
                else -> Align.START
            }
            "pStyle" -> if (inParaProps) paras.lastOrNull()?.heading = DocxReader.headingLevel(atts.w("val").orEmpty())
            "outlineLvl" -> if (inParaProps) atts.w("val")?.toIntOrNull()?.let { if (it in 0..5) paras.lastOrNull()?.heading = it + 1 }
            "numPr" -> if (inParaProps) paras.lastOrNull()?.listed = true
            "ilvl" -> if (inParaProps) paras.lastOrNull()?.level = atts.w("val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            "r" -> { bold = false; italic = false; underline = false; strike = false; halfPoints = null }
            "b" -> if (inRunProps) bold = atts.flag()
            "i" -> if (inRunProps) italic = atts.flag()
            "u" -> if (inRunProps) underline = atts.flag()
            "strike", "dstrike" -> if (inRunProps) strike = atts.flag()
            "sz" -> if (inRunProps) halfPoints = atts.w("val")?.toIntOrNull()?.coerceIn(2, 3276)
            "t" -> inText = true
            "tab" -> if (!inParaProps) text("\t")
            "br" -> when (atts.w("type")) {
                "page" -> pageBreak()
                "column" -> text("\n")
                else -> text("\n")
            }
            "cr" -> text("\n")
            "noBreakHyphen" -> text("‑")
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (skipDepth > 0) { skipDepth--; return }
        if (!isW(uri)) return
        when (localName) {
            "t" -> inText = false
            "pPr" -> inParaProps = false
            "rPr" -> inRunProps = false
            "p" -> endParagraph()
            "tr" -> if (tableDepth == 1) {
                rowRuns?.take()?.let { blocks.add(Paragraph(it, rowAlign)) }
                rowRuns = null
            }
            "tbl" -> tableDepth--
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skipDepth == 0 && inText) text(String(ch, start, length))
    }

    private fun style() = Run("", bold, italic, underline, strike, halfPoints?.takeIf { it != 24 }?.div(2f))

    private fun text(s: String) {
        val p = paras.lastOrNull() ?: return
        chars += s.length
        if (chars > limits.maxChars) throw ConvertException(ConvertError.LimitExceeded("The document has more than ${limits.maxChars} characters."))
        p.runs.append(s, style())
    }

    private fun endParagraph() {
        val p = paras.removeLastOrNull() ?: return
        val runs = p.runs.take()
        val row = rowRuns
        if (tableDepth > 0 && row != null) {
            // Paragraphs inside one cell are joined with a space.
            if (cellHasText && runs.isNotEmpty()) row.append(" ", Run(""))
            runs.forEach { row.append(it.text, it.copy(text = "")) }
            if (runs.isNotEmpty()) cellHasText = true
            if (p.align != Align.START) rowAlign = p.align
            return
        }
        blocks.add(Paragraph(runs, p.align, p.heading, if (p.listed) "•" else null, if (p.listed) p.level else 0))
    }

    private fun pageBreak() {
        // A page break ends the paragraph so far, starts a new page, then the paragraph carries on.
        val p = paras.lastOrNull() ?: return
        if (tableDepth > 0) return
        val runs = p.runs.take()
        if (runs.isNotEmpty()) blocks.add(Paragraph(runs, p.align, p.heading, if (p.listed) "•" else null, p.level))
        blocks.add(PageBreak)
    }
}
