package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class BreachClientTest {
    /** A connection that answers from memory; nothing here touches a socket. */
    private class Fake(
        url: URL,
        private val code: Int = 200,
        private val type: String? = "application/json; charset=utf-8",
        private val length: Long = -1,
        private val body: () -> InputStream = { ByteArrayInputStream("[]".toByteArray()) },
    ) : HttpURLConnection(url) {
        val headers = linkedMapOf<String, String>()
        var disconnected = false
        var bodyOpened = false

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun addRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getResponseCode(): Int = code
        override fun getContentType(): String? = type
        override fun getContentLengthLong(): Long = length
        override fun getInputStream(): InputStream {
            bodyOpened = true
            return body()
        }

        override fun getHeaderField(name: String?): String? = if (name == "Location") "https://evil.example/" else null
        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false
        override fun connect() {}
    }

    private class Factory(val make: (URL) -> Fake) : BreachClient.ConnectionFactory {
        val opened = mutableListOf<Fake>()
        val urls = mutableListOf<URL>()
        override fun open(url: URL): HttpURLConnection {
            urls += url
            return make(url).also { opened += it }
        }
    }

    private fun refusedWith(reason: BreachClient.Reason, factory: Factory, code: Int? = null) {
        try {
            BreachClient(factory).fetch()
            fail("accepted")
        } catch (e: BreachClient.Refused) {
            assertEquals(reason, e.reason)
            if (code != null) assertEquals(code, e.code)
        }
        assertEquals("one request, never a second hop", 1, factory.urls.size)
        assertTrue(factory.opened.single().disconnected)
    }

    @Test
    fun theAnswerIsReadAndHashed() {
        val body = """[{"Name":"A"}]""".toByteArray()
        val f = Factory { Fake(it, body = { ByteArrayInputStream(body) }, length = body.size.toLong()) }
        val d = BreachClient(f).fetch()
        assertArrayEquals(body, d.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }, d.sha256)
        assertEquals(listOf(URL("https://haveibeenpwned.com/api/v3/breaches")), f.urls)
        assertTrue(f.opened.single().disconnected)
    }

    @Test
    fun theRequestCarriesOnlyTheUserAgent() {
        val f = Factory { Fake(it) }
        BreachClient(f).fetch()
        val c = f.opened.single()
        assertEquals(mapOf("User-Agent" to "Tunnels-breaches"), c.headers)
        assertFalse(c.instanceFollowRedirects)
        assertFalse(c.useCaches)
        assertEquals(15_000, c.connectTimeout)
        assertEquals(30_000, c.readTimeout)
        assertNull(c.getURL().query)
        assertNull(c.getURL().userInfo)
    }

    @Test
    fun redirectRefused() {
        for (code in listOf(300, 301, 302, 303, 307, 308, 399)) {
            refusedWith(BreachClient.Reason.REDIRECT, Factory { Fake(it, code = code) }, code)
        }
    }

    @Test
    fun redirectBodyIsNeverRead() {
        val f = Factory { Fake(it, code = 302) }
        try {
            BreachClient(f).fetch()
        } catch (_: BreachClient.Refused) {
        }
        assertFalse(f.opened.single().bodyOpened)
    }

    @Test
    fun non200Refused() {
        for (code in listOf(100, 201, 202, 204, 206, 400, 401, 403, 404, 429, 500, 503)) {
            refusedWith(BreachClient.Reason.STATUS, Factory { Fake(it, code = code) }, code)
        }
    }

    @Test
    fun wrongContentTypeRefused() {
        for (type in listOf("text/html", "text/plain; charset=utf-8", "application/octet-stream", "application/xml", "", null, "json")) {
            val f = Factory { Fake(it, type = type) }
            refusedWith(BreachClient.Reason.CONTENT_TYPE, f)
            assertFalse("the body of a refused answer is not read", f.opened.single().bodyOpened)
        }
        for (type in listOf("application/json", "Application/JSON", " application/json; charset=utf-8")) {
            BreachClient(Factory { Fake(it, type = type) }).fetch()
        }
    }

    @Test
    fun oversizeRefused() {
        // Declared too large: refused before a byte is read.
        val declared = Factory { Fake(it, length = BreachClient.MAX_BYTES + 1) }
        refusedWith(BreachClient.Reason.TOO_LARGE, declared)
        assertFalse(declared.opened.single().bodyOpened)

        // Not declared (or lying): counted while reading, and stopped soon after the cap.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        refusedWith(BreachClient.Reason.TOO_LARGE, Factory { Fake(it, length = 10, body = { endless }) })
        assertTrue("stopped near the cap, read $served", served <= BreachClient.MAX_BYTES + 64 * 1024)

        // Exactly the cap is accepted.
        val exact = ByteArray(BreachClient.MAX_BYTES.toInt())
        assertEquals(exact.size, BreachClient(Factory { Fake(it, body = { ByteArrayInputStream(exact) }) }).fetch().bytes.size)
    }

    @Test
    fun cancelBetweenReadsStopsTheDownload() {
        var reads = 0
        val slow = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return len.coerceAtMost(10)
            }
        }
        val f = Factory { Fake(it, body = { slow }) }
        try {
            BreachClient(f).fetch(cancelled = { reads >= 3 })
            fail("not cancelled")
        } catch (_: InterruptedIOException) {
        }
        assertEquals(3, reads)
        assertTrue(f.opened.single().disconnected)
    }

    @Test
    fun aConnectionFailureIsAnIoExceptionAndStillDisconnects() {
        val f = Factory { Fake(it, body = { throw IOException("reset") }) }
        try {
            BreachClient(f).fetch()
            fail("accepted")
        } catch (_: IOException) {
        }
        assertTrue(f.opened.single().disconnected)
    }

    @Test
    fun onlyTheOneHostOverHttpsIsEverOpened() {
        for (bad in listOf(
            "http://haveibeenpwned.com/api/v3/breaches", "https://api.haveibeenpwned.com/api/v3/breaches",
            "https://haveibeenpwned.com.evil.example/", "https://haveibeenpwned.com:8443/api/v3/breaches",
            "https://user@haveibeenpwned.com/api/v3/breaches", "file:///etc/hosts", "ftp://haveibeenpwned.com/",
        )) {
            try {
                BreachClient.requireAllowed(URL(bad))
                fail("allowed $bad")
            } catch (_: IOException) {
            }
        }
        BreachClient.requireAllowed(URL(BreachSource.URL))
        BreachClient.requireAllowed(URL("https://haveibeenpwned.com:443/api/v3/breaches"))
    }
}
