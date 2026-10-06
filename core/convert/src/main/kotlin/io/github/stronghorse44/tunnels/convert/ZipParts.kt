package io.github.stronghorse44.tunnels.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.helpers.DefaultHandler

/** Reads one named part out of an Office zip, refusing bombs, and parses XML without DTDs or external entities. */
internal object ZipParts {
    fun read(bytes: ByteArray, part: String, limits: ConvertLimits, what: String): ByteArray {
        if (bytes.size > limits.maxInputBytes) throw ConvertException(ConvertError.LimitExceeded("The file is larger than ${limits.maxInputBytes / (1024 * 1024)} MB."))
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.maxZipEntries) throw ConvertException(ConvertError.LimitExceeded("The $what has more than ${limits.maxZipEntries} parts."))
                    if (entry.name.trimStart('/') != part) continue
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limits.maxXmlBytes) throw ConvertException(ConvertError.LimitExceeded("The $what's text unpacks to more than ${limits.maxXmlBytes / (1024 * 1024)} MB."))
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw ConvertException(ConvertError.Damaged("The $what looks damaged: ${e.message ?: "bad zip data"}."))
        }
        throw ConvertException(ConvertError.Damaged("The $what has no $part. It may be password-protected or not a real $what."))
    }

    /**
     * Office parts never carry a DTD; one that does is refused before parsing, so there is no entity expansion
     * and no external entity (XXE). Markup can't hide in text: a literal "<!" in content is always escaped.
     */
    fun parse(xml: ByteArray, handler: DefaultHandler, what: String) {
        // The scan below reads bytes as ASCII, so only UTF-8 (what Office writes) is accepted.
        if (xml.take(4).any { it == 0.toByte() } || (xml.size >= 2 && (xml[0] == 0xFE.toByte() || xml[0] == 0xFF.toByte()))) {
            throw ConvertException(ConvertError.Unsupported("The $what's text isn't UTF-8."))
        }
        if (containsIgnoreCase(xml, "<!DOCTYPE") || containsIgnoreCase(xml, "<!ENTITY")) {
            throw ConvertException(ConvertError.Unsupported("The $what contains a DTD, which Office files never do. Refused for safety."))
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        try {
            factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
        } catch (e: ConvertException) {
            throw e
        } catch (e: Exception) {
            throw ConvertException(ConvertError.Damaged("The $what's text couldn't be read: ${e.message ?: e.javaClass.simpleName}."))
        }
    }

    private fun containsIgnoreCase(bytes: ByteArray, needle: String): Boolean {
        val n = needle.uppercase().toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - n.size) {
            for (j in n.indices) {
                val b = bytes[i + j].toInt()
                val up = if (b in 'a'.code..'z'.code) b - 32 else b
                if (up != n[j].toInt()) continue@outer
            }
            return true
        }
        return false
    }
}
