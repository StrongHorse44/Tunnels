package io.github.stronghorse44.tunnels.convert

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Lays a [Document] out on A4 pages with the platform's text engine and writes it as a PDF. Fonts are the
 * phone's own (embedded by [PdfDocument]), so any script the phone can show comes out right.
 */
internal object DocumentPdf {
    const val MAX_PAGES = 5_000

    private val width = PageGeometry.A4_WIDTH - 2 * PageGeometry.TEXT_MARGIN
    private val height = PageGeometry.A4_HEIGHT - 2 * PageGeometry.TEXT_MARGIN

    /** Writes the PDF and returns its page count. */
    fun write(doc: Document, out: OutputStream, isCancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = if (doc.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
            textSize = if (doc.monospace) 10f else 12f
        }
        val pdf = PdfDocument()
        try {
            var pageNumber = 0
            for (section in doc.sections()) {
                val text = spannable(section, doc.monospace, paint.textSize)
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, if (doc.monospace) 1f else 1.15f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build()
                val bottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
                for (range in PageGeometry.paginate(bottoms, height)) {
                    if (isCancelled()) throw CancellationException("Cancelled")
                    if (++pageNumber > MAX_PAGES) throw ConvertException(ConvertError.LimitExceeded("The PDF would have more than $MAX_PAGES pages."))
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(PageGeometry.A4_WIDTH, PageGeometry.A4_HEIGHT, pageNumber).create())
                    if (!range.isEmpty()) {
                        val top = layout.getLineTop(range.first)
                        val bottom = layout.getLineBottom(range.last)
                        page.canvas.apply {
                            save()
                            translate(PageGeometry.TEXT_MARGIN.toFloat(), (PageGeometry.TEXT_MARGIN - top).toFloat())
                            clipRect(0, top, width, bottom)
                            layout.draw(this)
                            restore()
                        }
                    }
                    pdf.finishPage(page)
                    progress(pageNumber)
                }
            }
            pdf.writeTo(out)
            return pageNumber
        } finally {
            pdf.close()
        }
    }

    private fun spannable(paragraphs: List<Paragraph>, monospace: Boolean, bodySize: Float): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        paragraphs.forEachIndexed { i, p ->
            val start = sb.length
            if (p.bullet != null) sb.append(p.bullet).append(' ')
            p.runs.forEach { r -> appendRun(sb, r, p.heading, monospace) }
            if (i < paragraphs.lastIndex) sb.append('\n')
            val end = sb.length
            if (end == start) return@forEachIndexed
            val flags = Spanned.SPAN_PARAGRAPH
            when (p.align) {
                Align.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, flags)
                Align.END -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end, flags)
                Align.START, Align.JUSTIFY -> Unit
            }
            if (p.bullet != null || p.level > 0) {
                val indent = (p.level * bodySize * 1.5f).roundToInt()
                val hang = if (p.bullet != null) (bodySize * 1.2f).roundToInt() else 0
                sb.setSpan(LeadingMarginSpan.Standard(indent, indent + hang), start, end, flags)
            }
            if (!monospace) {
                sb.setSpan(SpaceAfter(if (p.heading > 0) (bodySize * 0.6f).roundToInt() else (bodySize * 0.5f).roundToInt()), start, end, flags)
            }
        }
        return sb
    }

    private fun appendRun(sb: SpannableStringBuilder, r: Run, heading: Int, monospace: Boolean) {
        val start = sb.length
        sb.append(r.text)
        val end = sb.length
        if (end == start) return
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val bold = r.bold || heading > 0
        when {
            bold && r.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flags)
            bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
            r.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
        }
        if (r.underline) sb.setSpan(UnderlineSpan(), start, end, flags)
        if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, flags)
        if (!monospace && (heading > 0 || r.sizePt != null)) {
            sb.setSpan(AbsoluteSizeSpan(PageGeometry.fontSize(heading, r.sizePt).roundToInt().coerceIn(4, 96)), start, end, flags)
        }
    }

    /** Space below a paragraph's last line. */
    private class SpaceAfter(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (end == (text as Spanned).getSpanEnd(this)) {
                fm.descent += extra
                fm.bottom += extra
            }
        }
    }
}
