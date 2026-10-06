package io.github.stronghorse44.tunnels.convert

/** Paragraph alignment. Justified text is laid out ragged by the PDF writer (it has no per-paragraph justify). */
enum class Align { START, CENTER, END, JUSTIFY }

/** A stretch of text with one set of character properties. [sizePt] null means the body size. */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float? = null,
)

sealed interface Block

/**
 * One paragraph. [heading] is 0 for body text, 1..6 for headings. [bullet] is the marker drawn before a list
 * item ("•"), [level] its nesting depth from 0. Tabs and soft line breaks stay inside run text as '\t' and '\n'.
 */
data class Paragraph(
    val runs: List<Run>,
    val align: Align = Align.START,
    val heading: Int = 0,
    val bullet: String? = null,
    val level: Int = 0,
) : Block {
    val text: String get() = runs.joinToString("") { it.text }
}

data object PageBreak : Block

/** A document read from RTF, Word, ODT or plain text. [monospace] asks the writer for a fixed-width font. */
data class Document(val blocks: List<Block>, val monospace: Boolean = false) {
    val paragraphs: List<Paragraph> get() = blocks.filterIsInstance<Paragraph>()

    /** The blocks split at page breaks; never empty. */
    fun sections(): List<List<Paragraph>> {
        val out = mutableListOf(mutableListOf<Paragraph>())
        blocks.forEach { b ->
            when (b) {
                is Paragraph -> out.last().add(b)
                PageBreak -> out.add(mutableListOf())
            }
        }
        return out
    }
}

/** Builds runs, merging neighbours with equal properties. */
internal class RunBuilder {
    private val runs = mutableListOf<Run>()
    private val text = StringBuilder()
    private var props: Run? = null

    val isEmpty: Boolean get() = runs.isEmpty() && text.isEmpty()

    fun append(s: String, style: Run) {
        if (s.isEmpty()) return
        val key = style.copy(text = "")
        if (props != key) {
            flush()
            props = key
        }
        text.append(s)
    }

    private fun flush() {
        val p = props ?: return
        if (text.isNotEmpty()) runs.add(p.copy(text = text.toString()))
        text.setLength(0)
    }

    fun take(): List<Run> {
        flush()
        val out = runs.toList()
        runs.clear()
        props = null
        return out
    }
}
