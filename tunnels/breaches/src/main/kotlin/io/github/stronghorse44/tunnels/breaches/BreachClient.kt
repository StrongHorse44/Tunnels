package io.github.stronghorse44.tunnels.breaches

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Plain HTTPS to Have I Been Pwned, from the JDK and nothing else: one GET of the whole public breach list.
 * The request carries a `User-Agent` and no other header of ours (no key, no cookie, no account, address or
 * domain). A redirect of any kind is refused, only a 200 answer is read, its content type must be JSON, and no
 * more than [MAX_BYTES] are read. Modelled on the updater's GitHubClient. Blocking: call it off the main thread.
 */
class BreachClient(private val factory: ConnectionFactory = ConnectionFactory { (it.openConnection() as HttpURLConnection) }) {
    /** Opens the connection for [url]; the tests give it a fake. */
    fun interface ConnectionFactory {
        fun open(url: URL): HttpURLConnection
    }

    enum class Reason { REDIRECT, STATUS, CONTENT_TYPE, TOO_LARGE }

    /** The service answered, but not with what Tunnels accepts; nothing was kept. [code] is the HTTP status when there is one. */
    class Refused(val reason: Reason, val code: Int = 0) : IOException("refused: $reason $code")

    /** The answer exactly as received, and its SHA-256 (64 lower-case hex). */
    class Download(val bytes: ByteArray, val sha256: String)

    /** [cancelled] is polled between reads; a cancel throws [InterruptedIOException]. */
    fun fetch(cancelled: () -> Boolean = { false }): Download {
        val url = URL(BreachSource.URL)
        requireAllowed(url)
        val conn = factory.open(url).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", BreachSource.USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code in 300..399) throw Refused(Reason.REDIRECT, code)
            if (code != HttpURLConnection.HTTP_OK) throw Refused(Reason.STATUS, code)
            val type = conn.contentType?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
            if (!type.startsWith("application/json")) throw Refused(Reason.CONTENT_TYPE, code)
            if (conn.contentLengthLong > MAX_BYTES) throw Refused(Reason.TOO_LARGE, code)
            val digest = MessageDigest.getInstance("SHA-256")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (cancelled()) throw InterruptedIOException("Cancelled.")
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_BYTES) throw Refused(Reason.TOO_LARGE, code)
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
            return Download(out.toByteArray(), digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Before any socket: HTTPS to the one host on the default port, with no user info, or nothing. */
        fun requireAllowed(url: URL) {
            if (!BreachSource.isAllowed(url.protocol, url.host) || url.userInfo != null || (url.port != -1 && url.port != 443)) {
                throw IOException("Refused to connect to ${url.host}: not the breach list over HTTPS.")
            }
        }

        const val MAX_BYTES = 16L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
