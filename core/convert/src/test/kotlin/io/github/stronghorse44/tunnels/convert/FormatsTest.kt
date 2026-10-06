package io.github.stronghorse44.tunnels.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    private fun b(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }
    private fun s(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test fun magicBytesWinOverNames() {
        assertEquals(InputFormat.RTF, Formats.detect(s("{\\rtf1\\ansi"), "letter.txt"))
        assertEquals(InputFormat.PDF, Formats.detect(s("%PDF-1.7\n"), "x.bin"))
        assertEquals(InputFormat.PNG, Formats.detect(b(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 26, 10), "x"))
        assertEquals(InputFormat.JPEG, Formats.detect(b(0xFF, 0xD8, 0xFF, 0xE0), "x.png"))
        assertEquals(InputFormat.WEBP, Formats.detect(s("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "), "x"))
        assertEquals(InputFormat.HEIC, Formats.detect(s("\u0000\u0000\u0000\u0018ftypheic"), "x"))
    }

    @Test fun zipsByContentAndName() {
        val odt = s("PK\u0003\u0004" + "\u0000".repeat(26) + "mimetypeapplication/vnd.oasis.opendocument.text")
        assertEquals(InputFormat.ODT, Formats.detect(odt, "x.zip"))
        assertEquals(InputFormat.DOCX, Formats.detect(s("PK\u0003\u0004 [Content_Types].xml"), "Report.DOCX"))
        assertEquals(InputFormat.DOCX, Formats.detect(s("PK\u0003\u0004 word/document.xml"), "download"))
        assertEquals(InputFormat.UNKNOWN, Formats.detect(s("PK\u0003\u0004 photos/"), "photos.zip"))
    }

    @Test fun legacyWordIsNamedButRefused() {
        val ole = b(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        assertEquals(InputFormat.DOC, Formats.detect(ole, "old.doc"))
        assertEquals(InputFormat.UNKNOWN, Formats.detect(ole, "sheet.xls"))
        assertTrue(Conversions.targets(InputFormat.DOC).isEmpty())
        assertNotNull(Conversions.refusal(InputFormat.DOC))
    }

    @Test fun textByExtensionAndContent() {
        assertEquals(InputFormat.TXT, Formats.detect(s("hello\nworld"), "notes.txt"))
        assertEquals(InputFormat.TXT, Formats.detect(s("# Title"), "README"))
        assertEquals(InputFormat.RTF, Formats.detect(s("garbage"), "x.rtf"))
        assertEquals(InputFormat.UNKNOWN, Formats.detect(b(0, 1, 2, 3, 4), "data.txt"))
        assertEquals(InputFormat.UNKNOWN, Formats.detect(s("hello"), "song.mp3"))
        assertTrue(Formats.looksLikeText(b(0xFF, 0xFE, 'a'.code, 0)))
        assertFalse(Formats.looksLikeText(b(1, 2, 3, 4, 5, 6)))
    }

    @Test fun conversionTable() {
        assertEquals(listOf(OutputFormat.PDF, OutputFormat.TXT), Conversions.targets(InputFormat.RTF))
        assertEquals(listOf(OutputFormat.PDF, OutputFormat.TXT), Conversions.targets(InputFormat.DOCX))
        assertEquals(listOf(OutputFormat.PNG, OutputFormat.JPEG), Conversions.targets(InputFormat.PDF))
        assertTrue(OutputFormat.PDF in Conversions.targets(InputFormat.JPEG))
        assertFalse(OutputFormat.JPEG in Conversions.targets(InputFormat.JPEG))
        InputFormat.entries.forEach { f ->
            if (Conversions.targets(f).isEmpty()) assertNotNull(f.name, Conversions.refusal(f)) else assertNull(Conversions.refusal(f))
        }
        assertTrue(Conversions.writesFolder(InputFormat.PDF, OutputFormat.PNG))
        assertFalse(Conversions.writesFolder(InputFormat.PNG, OutputFormat.JPEG))
    }

    @Test fun names() {
        assertEquals("Report.final.pdf", Conversions.outputName("Report.final.rtf", OutputFormat.PDF))
        assertEquals("converted.pdf", Conversions.outputName(".rtf", OutputFormat.PDF))
        assertEquals("README.pdf", Conversions.outputName("README", OutputFormat.PDF))
        assertEquals("Scan-p007.png", Conversions.pageName("Scan.pdf", 7, 12, OutputFormat.PNG))
        assertEquals("Book-p0042.jpg", Conversions.pageName("Book.pdf", 42, 1200, OutputFormat.JPEG))
    }
}
