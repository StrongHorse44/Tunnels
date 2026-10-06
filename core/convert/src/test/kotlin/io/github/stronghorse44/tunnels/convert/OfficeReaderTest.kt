package io.github.stronghorse44.tunnels.convert

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfficeReaderTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            parts.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun docx(body: String) = zip(
        "[Content_Types].xml" to "<Types/>",
        DocxReader.PART to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
              xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><w:body>$body</w:body></w:document>""",
    )

    private fun expectError(block: () -> Unit): ConvertError {
        try {
            block()
        } catch (e: ConvertException) {
            return e.error
        }
        fail("expected ConvertException")
        throw AssertionError()
    }

    @Test fun docxParagraphsAndRuns() {
        val doc = DocxReader.read(
            docx(
                """<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:rPr><w:b/><w:i w:val="0"/><w:sz w:val="32"/></w:rPr><w:t>bold</w:t></w:r></w:p>
                <w:p><w:pPr><w:jc w:val="center"/><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t xml:space="preserve">centred</w:t></w:r></w:p>""",
            ),
        )
        val (a, b) = doc.paragraphs
        assertEquals("Hello bold", a.text)
        val bold = a.runs.last()
        assertTrue(bold.bold)
        assertFalse(bold.italic)
        assertEquals(16f, bold.sizePt)
        assertFalse(a.runs.first().bold)
        assertEquals(Align.CENTER, b.align)
        assertFalse("paragraph mark properties are not run properties", b.runs.single().bold)
    }

    @Test fun docxHeadingsListsTabsBreaks() {
        val doc = DocxReader.read(
            docx(
                """<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Head</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="3"/></w:numPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>Item</w:t><w:tab/><w:t>x</w:t><w:br/><w:t>y</w:t></w:r></w:p>
                <w:p><w:r><w:t>Before</w:t><w:br w:type="page"/><w:t>After</w:t></w:r></w:p>""",
            ),
        )
        val ps = doc.paragraphs
        assertEquals(2, ps[0].heading)
        assertEquals("Item\tx\ny", ps[1].text)
        assertEquals("•", ps[1].bullet)
        assertEquals(1, ps[1].level)
        assertEquals(listOf("Before", "After"), ps.drop(2).map { it.text })
        assertEquals(2, doc.sections().size)
    }

    @Test fun docxSkipsDeletionsFallbacksAndFieldCodes() {
        val doc = DocxReader.read(
            docx(
                """<w:p><w:r><w:t>keep</w:t></w:r><w:del><w:r><w:delText>gone</w:delText></w:r></w:del>
                <w:r><w:instrText> HYPERLINK "x" </w:instrText></w:r>
                <w:r><mc:AlternateContent><mc:Choice><w:t>!</w:t></mc:Choice><mc:Fallback><w:t>dup</w:t></mc:Fallback></mc:AlternateContent></w:r></w:p>""",
            ),
        )
        assertEquals("keep!", doc.paragraphs.single().text)
    }

    @Test fun docxTableRows() {
        val doc = DocxReader.read(
            docx(
                """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p/></w:tc><w:tc><w:p><w:r><w:t>D</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>after</w:t></w:r></w:p>""",
            ),
        )
        assertEquals(listOf("A A2\tB", "\tD", "after"), doc.paragraphs.map { it.text })
        assertTrue(doc.paragraphs[0].runs.first { it.text == "B" }.bold)
    }

    @Test fun docxRefusesDoctype() {
        val bomb = zip(DocxReader.PART to """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol">]><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">&lol;</w:document>""")
        assertTrue(expectError { DocxReader.read(bomb) } is ConvertError.Unsupported)
    }

    @Test fun docxRefusesDoctypeAfterALongProlog() {
        val padded = zip(DocxReader.PART to "<?xml version=\"1.0\"?><!--${" ".repeat(10_000)}--><!doctype x [<!ENTITY a \"b\">]><x>&a;</x>")
        assertTrue(expectError { DocxReader.read(padded) } is ConvertError.Unsupported)
    }

    @Test fun docxRefusesUtf16Xml() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(DocxReader.PART))
            z.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<x/>".toByteArray(Charsets.UTF_16LE))
            z.closeEntry()
        }
        assertTrue(expectError { DocxReader.read(out.toByteArray()) } is ConvertError.Unsupported)
    }

    @Test fun docxMissingBodyAndNotAZip() {
        assertTrue(expectError { DocxReader.read(zip("other.xml" to "<a/>")) } is ConvertError.Damaged)
        assertTrue(expectError { DocxReader.read("not a zip".toByteArray()) } is ConvertError.Damaged)
    }

    @Test fun docxRefusesOversizedXml() {
        val big = docx("<w:p><w:r><w:t>${"a".repeat(5000)}</w:t></w:r></w:p>")
        assertTrue(expectError { DocxReader.read(big, ConvertLimits(maxXmlBytes = 1000)) } is ConvertError.LimitExceeded)
    }

    @Test fun headingStyleIds() {
        assertEquals(1, DocxReader.headingLevel("Heading1"))
        assertEquals(3, DocxReader.headingLevel("heading 3"))
        assertEquals(1, DocxReader.headingLevel("Title"))
        assertEquals(0, DocxReader.headingLevel("Normal"))
        assertEquals(0, DocxReader.headingLevel("Heading10"))
    }

    private fun odt(styles: String, body: String) = zip(
        "mimetype" to "application/vnd.oasis.opendocument.text",
        OdtReader.PART to """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
              xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
              xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
              xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
              xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
              <office:automatic-styles>$styles</office:automatic-styles>
              <office:body><office:text>$body</office:text></office:body></office:document-content>""",
    )

    @Test fun odtStylesHeadingsListsAndSpaces() {
        val doc = OdtReader.read(
            odt(
                """<style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
                <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold" fo:font-size="18pt"/></style:style>""",
                """<text:h text:outline-level="2">Chapter</text:h>
                <text:p text:style-name="P1">Plain <text:span text:style-name="T1">strong</text:span> a<text:s text:c="3"/>b<text:tab/>c<text:line-break/>d</text:p>
                <text:list><text:list-item><text:p>One</text:p></text:list-item><text:list-item><text:list><text:list-item><text:p>Nested</text:p></text:list-item></text:list></text:list-item></text:list>
                <text:p>Note<text:note><text:note-body><text:p>footnote</text:p></text:note-body></text:note> end</text:p>""",
            ),
        )
        val ps = doc.paragraphs
        assertEquals(2, ps[0].heading)
        assertEquals("Chapter", ps[0].text)
        assertEquals("Plain strong a   b\tc\nd", ps[1].text)
        assertEquals(Align.CENTER, ps[1].align)
        val strong = ps[1].runs.first { it.text == "strong" }
        assertTrue(strong.bold)
        assertEquals(18f, strong.sizePt)
        assertEquals(listOf("One", "Nested"), ps.subList(2, 4).map { it.text })
        assertEquals(listOf(0, 1), ps.subList(2, 4).map { it.level })
        assertEquals("•", ps[2].bullet)
        assertEquals("Note end", ps[4].text)
        assertNull(ps[4].bullet)
    }

    @Test fun odtTables() {
        val doc = OdtReader.read(
            odt(
                "",
                """<table:table><table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell><text:p>B</text:p></table:table-cell></table:table-row></table:table>""",
            ),
        )
        assertEquals(listOf("A\tB"), doc.paragraphs.map { it.text })
    }

    @Test fun documentsDispatch() {
        assertEquals("x", Documents.read(InputFormat.RTF, "{\\rtf1 x}".toByteArray()).paragraphs.single().text)
        assertTrue(expectError { Documents.read(InputFormat.PDF, ByteArray(0)) } is ConvertError.Unsupported)
    }
}
