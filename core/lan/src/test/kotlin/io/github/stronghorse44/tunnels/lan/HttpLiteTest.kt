package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpLiteTest {
    @Test
    fun parsesUrls() {
        assertEquals(HttpTarget("192.168.1.1", 49152, "/igd.xml"), HttpLite.parseUrl("http://192.168.1.1:49152/igd.xml"))
        assertEquals(HttpTarget("192.168.1.1", 80, "/"), HttpLite.parseUrl("http://192.168.1.1"))
        assertEquals(HttpTarget("192.168.1.1", 80, "/desc.xml"), HttpLite.parseUrl("HTTP://192.168.1.1/desc.xml#frag"))
        assertEquals(HttpTarget("fe80::1", 8080, "/x"), HttpLite.parseUrl("http://[fe80::1]:8080/x"))
        assertEquals(HttpTarget("fe80::1", 80, "/"), HttpLite.parseUrl("http://[fe80::1]"))
        assertNull(HttpLite.parseUrl("https://192.168.1.1/"))
        assertNull(HttpLite.parseUrl("ftp://x/"))
        assertNull(HttpLite.parseUrl("http://"))
        assertNull(HttpLite.parseUrl("http://user@host/"))
        assertNull(HttpLite.parseUrl("http://host:99999/"))
        assertNull(HttpLite.parseUrl("http://host:abc/"))
        assertNull(HttpLite.parseUrl("http://[fe80::1"))
    }

    @Test
    fun buildsAGetRequest() {
        val req = HttpLite.getRequest(HttpTarget("192.168.1.1", 49152, "/igd.xml"))
        assertTrue(req.startsWith("GET /igd.xml HTTP/1.0\r\n"))
        assertTrue(req.contains("Host: 192.168.1.1:49152\r\n"))
        assertTrue(req.contains("Connection: close\r\n"))
        assertTrue(req.endsWith("\r\n\r\n"))
        assertTrue(HttpLite.getRequest(HttpTarget("10.0.0.1", 80, "/")).contains("Host: 10.0.0.1\r\n"))
        assertTrue(HttpLite.getRequest(HttpTarget("fe80::1", 80, "/")).contains("Host: [fe80::1]\r\n"))
    }

    @Test
    fun parsesResponses() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 5\r\n\r\n<a/>\n"
        val r = HttpLite.parseResponse(raw)!!
        assertEquals(200, r.status)
        assertEquals("text/xml", r.headers["content-type"])
        assertEquals("<a/>\n", r.body)

        val lf = HttpLite.parseResponse("HTTP/1.0 404 Not Found\nServer: x\n\nnope")!!
        assertEquals(404, lf.status)
        assertEquals("nope", lf.body)

        assertNull(HttpLite.parseResponse("not http"))
        assertNull(HttpLite.parseResponse(""))
        val headless = HttpLite.parseResponse("HTTP/1.1 200 OK")!!
        assertEquals("", headless.body)

        val big = "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(HttpLite.MAX_BODY + 100)
        assertEquals(HttpLite.MAX_BODY, HttpLite.parseResponse(big)!!.body.length)
    }
}
