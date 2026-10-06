package io.github.stronghorse44.tunnels.convert

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Plain text in, plain text out. */
object TextFiles {
    /** UTF-8 (with or without BOM) or UTF-16 with a BOM; anything that isn't valid UTF-8 reads as Windows-1252. */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, charset("windows-1252"))
            }
        }
    }

    /** One paragraph per line, laid out in a fixed-width font; form feeds start a new page. */
    fun read(bytes: ByteArray, limits: ConvertLimits = ConvertLimits.DEFAULT): Document {
        if (bytes.size > limits.maxInputBytes) throw ConvertException(ConvertError.LimitExceeded("The file is larger than ${limits.maxInputBytes / (1024 * 1024)} MB."))
        val text = decode(bytes)
        if (text.length > limits.maxChars) throw ConvertException(ConvertError.LimitExceeded("The file has more than ${limits.maxChars} characters."))
        val blocks = mutableListOf<Block>()
        text.split("\r\n", "\n", "\r").forEach { line ->
            val pages = line.split('\u000C')
            pages.forEachIndexed { i, part ->
                if (i > 0) blocks.add(PageBreak)
                if (i == 0 || part.isNotEmpty()) blocks.add(Paragraph(if (part.isEmpty()) emptyList() else listOf(Run(part))))
            }
        }
        while (blocks.lastOrNull().let { it is Paragraph && it.runs.isEmpty() }) blocks.removeAt(blocks.lastIndex)
        return Document(blocks, monospace = true)
    }

    /** The document's text: bullets as "• ", nested items indented, tables as tab-separated lines, page breaks as form feeds. */
    fun write(doc: Document): String = buildString {
        doc.blocks.forEachIndexed { i, b ->
            when (b) {
                is Paragraph -> {
                    if (b.bullet != null) append("  ".repeat(b.level)).append(b.bullet).append(' ')
                    append(b.text)
                    append('\n')
                    if (b.heading > 0 && doc.blocks.getOrNull(i + 1) is Paragraph) append('\n')
                }
                PageBreak -> append('\u000C')
            }
        }
    }
}

/** Picks the reader for a document format. */
object Documents {
    fun read(format: InputFormat, bytes: ByteArray, limits: ConvertLimits = ConvertLimits.DEFAULT): Document = when (format) {
        InputFormat.RTF -> RtfReader.read(bytes, limits)
        InputFormat.DOCX -> DocxReader.read(bytes, limits)
        InputFormat.ODT -> OdtReader.read(bytes, limits)
        InputFormat.TXT -> TextFiles.read(bytes, limits)
        else -> throw ConvertException(ConvertError.Unsupported("${format.label} isn't a text document."))
    }
}
