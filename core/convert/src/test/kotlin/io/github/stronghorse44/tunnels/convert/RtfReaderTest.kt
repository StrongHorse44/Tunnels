package io.github.stronghorse44.tunnels.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RtfReaderTest {
    private fun read(rtf: String) = RtfReader.read(rtf.toByteArray(Charsets.ISO_8859_1))

    @Test fun plainParagraphs() {
        val doc = read("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\\f0\\fs24 Hello world.\\par Second line.\\par}")
        assertEquals(listOf("Hello world.", "Second line."), doc.paragraphs.map { it.text })
    }

    @Test fun boldItalicUnderlineAndSize() {
        val p = read("{\\rtf1 Normal {\\b bold} \\i italic\\i0  {\\ul under}{\\fs48 big}\\par}").paragraphs.single()
        assertEquals("Normal bold italic underbig", p.text)
        assertTrue(p.runs.first { it.text == "bold" }.bold)
        assertFalse(p.runs.first { it.text.startsWith("Normal") }.bold)
        assertTrue(p.runs.first { it.text.contains("italic") }.italic)
        assertTrue(p.runs.first { it.text == "under" }.underline)
        assertEquals(24f, p.runs.first { it.text == "big" }.sizePt)
        assertNull(p.runs.first { it.text == "under" }.sizePt)
    }

    @Test fun groupsRestoreFormatting() {
        val p = read("{\\rtf1 {\\b on {\\i both} on}off\\par}").paragraphs.single()
        assertTrue(p.runs.first { it.text == "both" }.italic && p.runs.first { it.text == "both" }.bold)
        assertFalse(p.runs.last().bold)
        assertEquals("off", p.runs.last().text)
    }

    @Test fun skipsTablesOfFontsColorsStylesInfoAndPictures() {
        val rtf = "{\\rtf1{\\fonttbl{\\f0 Arial;}}{\\colortbl;\\red0\\green0\\blue0;}{\\stylesheet{\\s0 Normal;}}" +
            "{\\info{\\title Secret title}{\\author Someone}}{\\*\\generator Writer;}{\\pict\\pngblip 89504e47}" +
            "{\\header Page header\\par}Body\\par}"
        assertEquals(listOf("Body"), read(rtf).paragraphs.map { it.text })
    }

    @Test fun binaryDataIsSkippedByLength() {
        val rtf = "{\\rtf1 {\\pict\\bin5 {{{{{}A\\par}"
        assertEquals(listOf("A"), read(rtf).paragraphs.map { it.text })
    }

    @Test fun hexEscapesUseTheCodePage() {
        assertEquals("café — €", read("{\\rtf1\\ansi\\ansicpg1252 caf\\'e9 \\'97 \\'80\\par}").paragraphs.single().text)
        assertEquals("При", read("{\\rtf1\\ansi\\ansicpg1251 \\'cf\\'f0\\'e8\\par}").paragraphs.single().text)
    }

    @Test fun fontCharsetOverridesTheDocumentCodePage() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Arial;}{\\f1\\fcharset204 Arial Cyr;}}\\f1 \\'cf\\'f0\\'e8 \\f0 \\'e9\\par}"
        assertEquals("При é", read(rtf).paragraphs.single().text)
    }

    @Test fun multiByteCodePages() {
        // "日本" in Shift-JIS is 93 fa 96 7b.
        assertEquals("日本", read("{\\rtf1\\ansi\\ansicpg932 \\'93\\'fa\\'96\\'7b\\par}").paragraphs.single().text)
    }

    @Test fun unicodeEscapesSkipTheirFallback() {
        val p = read("{\\rtf1\\uc1 \\u8364?\\u-3913?x {\\uc2\\u233\\'65\\'27 y}\\par}").paragraphs.single()
        assertEquals("€x é y", p.text)
    }

    @Test fun surrogatePairsFromTwoUnicodeEscapes() {
        assertEquals("😀", read("{\\rtf1\\uc0\\u-10179\\u-8704\\par}").paragraphs.single().text)
    }

    @Test fun specialCharactersAndEscapes() {
        val text = read("{\\rtf1 a\\tab b\\line c \\emdash\\endash\\lquote x\\rquote\\ldblquote y\\rdblquote \\{\\}\\\\\\~z\\par}").paragraphs.single().text
        assertEquals("a\tb\nc —–‘x’“y”{}\\ z", text)
    }

    @Test fun alignmentAndPardReset() {
        val doc = read("{\\rtf1\\pard\\qc Centre\\par Still centre\\par\\pard Left\\par\\qr Right\\par\\qj Just\\par}")
        assertEquals(listOf(Align.CENTER, Align.CENTER, Align.START, Align.END, Align.JUSTIFY), doc.paragraphs.map { it.align })
    }

    @Test fun pageBreak() {
        val doc = read("{\\rtf1 One\\page Two\\par}")
        assertEquals(3, doc.blocks.size)
        assertTrue(doc.blocks[1] is PageBreak)
        assertEquals(2, doc.sections().size)
    }

    @Test fun listTextBecomesTheBullet() {
        val rtf = "{\\rtf1{\\fonttbl{\\f0 Arial;}{\\f1\\fcharset2 Symbol;}}" +
            "\\pard\\ls1\\ilvl0{\\listtext\\f1 \\'b7\\tab}First\\par" +
            "{\\listtext\\f0 2.\\tab}Second\\par\\pard After\\par}"
        val ps = read(rtf).paragraphs
        assertEquals(listOf("First", "Second", "After"), ps.map { it.text })
        assertEquals(listOf("•", "2.", null), ps.map { it.bullet })
    }

    @Test fun headingFromOutlineLevel() {
        val ps = read("{\\rtf1\\pard\\outlinelevel0\\b Title\\par\\pard Body\\par}").paragraphs
        assertEquals(listOf(1, 0), ps.map { it.heading })
    }

    @Test fun fieldKeepsResultDropsInstruction() {
        val rtf = "{\\rtf1 See {\\field{\\*\\fldinst{HYPERLINK \"https://example.org\"}}{\\fldrslt{the site}}}.\\par}"
        assertEquals("See the site.", read(rtf).paragraphs.single().text)
    }

    @Test fun tableRowsBecomeTabSeparatedLines() {
        val rtf = "{\\rtf1\\trowd\\cellx1000\\cellx2000\\pard\\intbl A\\cell B\\cell\\row\\trowd\\pard\\intbl C\\cell D\\cell\\row}"
        assertEquals(listOf("A\tB", "C\tD"), read(rtf).paragraphs.map { it.text })
    }

    @Test fun keepsBlankParagraphsInsideButNotAtTheEnd() {
        assertEquals(listOf("A", "", "B"), read("{\\rtf1 A\\par\\par B\\par\\par\\par}").paragraphs.map { it.text })
    }

    @Test fun refusesNonRtf() {
        try {
            RtfReader.read("hello".toByteArray())
            fail()
        } catch (e: ConvertException) {
            assertTrue(e.error is ConvertError.Damaged)
        }
    }

    @Test fun refusesDeepNesting() {
        val rtf = "{\\rtf1 " + "{".repeat(5000) + "x" + "}".repeat(5000) + "}"
        try {
            read(rtf)
            fail()
        } catch (e: ConvertException) {
            assertTrue(e.error is ConvertError.LimitExceeded)
        }
    }

    @Test fun truncatedFileStillReads() {
        assertEquals("Cut", read("{\\rtf1 {\\b Cut").paragraphs.single().text)
    }

    @Test fun refusesTooManyCharacters() {
        val rtf = "{\\rtf1 " + "a".repeat(200) + "}"
        try {
            RtfReader.read(rtf.toByteArray(), ConvertLimits(maxChars = 100))
            fail()
        } catch (e: ConvertException) {
            assertTrue(e.error is ConvertError.LimitExceeded)
        }
    }
}
