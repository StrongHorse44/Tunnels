package io.github.stronghorse44.tunnels.convert

/** What a file is, decided from its first bytes first and its name second. */
enum class InputFormat(val label: String) {
    RTF("RTF"),
    DOCX("Word (.docx)"),
    ODT("OpenDocument text (.odt)"),
    TXT("Plain text"),
    PDF("PDF"),
    PNG("PNG image"),
    JPEG("JPEG image"),
    WEBP("WebP image"),
    HEIC("HEIC image"),

    /** Word 97–2003 binary (.doc). Recognised so the screen can say why it's refused. */
    DOC("Word 97–2003 (.doc)"),
    UNKNOWN("Unknown"),
    ;

    val isDocument: Boolean get() = this in setOf(RTF, DOCX, ODT, TXT)
    val isImage: Boolean get() = this in setOf(PNG, JPEG, WEBP, HEIC)
}

enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    TXT("Plain text", "txt", "text/plain"),
    PNG("PNG images", "png", "image/png"),
    JPEG("JPEG images", "jpg", "image/jpeg"),
}

/** Every conversion Tunnels offers, all done on the phone with no network. */
object Conversions {
    fun targets(input: InputFormat): List<OutputFormat> = when (input) {
        InputFormat.RTF, InputFormat.DOCX, InputFormat.ODT -> listOf(OutputFormat.PDF, OutputFormat.TXT)
        InputFormat.TXT -> listOf(OutputFormat.PDF)
        InputFormat.PDF -> listOf(OutputFormat.PNG, OutputFormat.JPEG)
        InputFormat.PNG -> listOf(OutputFormat.PDF, OutputFormat.JPEG)
        InputFormat.JPEG -> listOf(OutputFormat.PDF, OutputFormat.PNG)
        InputFormat.WEBP, InputFormat.HEIC -> listOf(OutputFormat.PDF, OutputFormat.PNG, OutputFormat.JPEG)
        InputFormat.DOC, InputFormat.UNKNOWN -> emptyList()
    }

    /** True when the conversion writes one image per page into a folder instead of one file. */
    fun writesFolder(input: InputFormat, output: OutputFormat): Boolean =
        input == InputFormat.PDF && (output == OutputFormat.PNG || output == OutputFormat.JPEG)

    /** Why [input] has no targets, for the screen. */
    fun refusal(input: InputFormat): String? = when (input) {
        InputFormat.DOC -> "Old Word files (.doc, Word 97–2003) aren't supported. Save it as .docx or .rtf in Word or LibreOffice, then convert that."
        InputFormat.UNKNOWN -> "Tunnels can convert RTF, Word (.docx), OpenDocument (.odt), plain text, PDF, PNG, JPEG, WebP and HEIC files."
        else -> null
    }

    /** "Report.final.rtf" + PDF -> "Report.final.pdf". */
    fun outputName(inputName: String, output: OutputFormat): String = "${baseName(inputName)}.${output.extension}"

    /** Page [page] (from 1) of [pages] as an image: "Report-p007.png", padded to the page count's width. */
    fun pageName(inputName: String, page: Int, pages: Int, output: OutputFormat): String {
        val width = maxOf(3, pages.toString().length)
        return "${baseName(inputName)}-p${page.toString().padStart(width, '0')}.${output.extension}"
    }

    fun baseName(name: String): String {
        val leaf = name.substringAfterLast('/')
        val dot = leaf.lastIndexOf('.')
        return (if (dot >= 0) leaf.substring(0, dot) else leaf).ifBlank { "converted" }
    }
}

object Formats {
    /** How many leading bytes [detect] wants. */
    const val HEAD = 4096

    fun detect(head: ByteArray, name: String): InputFormat {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            head.startsWith("{\\rtf") -> InputFormat.RTF
            head.startsWith("%PDF-") -> InputFormat.PDF
            head.startsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) -> InputFormat.PNG
            head.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> InputFormat.JPEG
            head.startsWith("RIFF") && head.startsWith("WEBP", 8) -> InputFormat.WEBP
            head.startsWith("ftyp", 4) && heicBrands.any { head.startsWith(it, 8) } -> InputFormat.HEIC
            head.startsWith(OLE) -> if (ext == "doc" || ext == "dot") InputFormat.DOC else InputFormat.UNKNOWN
            head.startsWith(ZIP) -> zipKind(head, ext)
            ext == "rtf" -> InputFormat.RTF
            ext in textExtensions && looksLikeText(head) -> InputFormat.TXT
            ext.isEmpty() && head.isNotEmpty() && looksLikeText(head) -> InputFormat.TXT
            else -> InputFormat.UNKNOWN
        }
    }

    private val ZIP = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val heicBrands = listOf("heic", "heix", "heim", "heis", "mif1", "msf1")
    private val textExtensions = setOf("txt", "text", "md", "markdown", "log", "csv", "tsv", "ini", "conf", "cfg", "json", "xml", "yaml", "yml")

    /** ODT stores its mimetype uncompressed as the first entry; Word files are known by name or by "word/" in the head. */
    private fun zipKind(head: ByteArray, ext: String): InputFormat {
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            ascii.contains("application/vnd.oasis.opendocument.text") -> InputFormat.ODT
            ext in setOf("docx", "docm", "dotx", "dotm") -> InputFormat.DOCX
            ext == "odt" -> InputFormat.ODT
            ascii.contains("word/") -> InputFormat.DOCX
            else -> InputFormat.UNKNOWN
        }
    }

    /** No NUL bytes outside a UTF-16 BOM'd file, and mostly printable. */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) || head.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) return true
        if (head.any { it == 0.toByte() }) return false
        val control = head.count { val b = it.toInt() and 0xFF; b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C }
        return control * 20 <= head.size
    }

    private fun ByteArray.startsWith(prefix: String, at: Int = 0): Boolean =
        startsWith(prefix.toByteArray(Charsets.ISO_8859_1), at)

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean {
        if (size < at + prefix.size) return false
        return prefix.indices.all { this[at + it] == prefix[it] }
    }
}
