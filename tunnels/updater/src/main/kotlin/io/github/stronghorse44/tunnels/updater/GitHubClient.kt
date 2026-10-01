package io.github.stronghorse44.tunnels.updater

import io.github.stronghorse44.tunnels.updates.UpdateSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Plain HTTPS to GitHub, from the JDK and nothing else: the release list and one asset. Redirects are followed by
 * hand so every hop is checked against [UpdateSource.isAllowed], and the token only ever goes to the API host,
 * never to the download host GitHub redirects to. Blocking: call it off the main thread.
 */
class GitHubClient(private val token: String?) {
    /** GitHub answered something other than 200. */
    class HttpException(val code: Int) : IOException("HTTP $code")

    /** The project's releases as GitHub's JSON, at most [MAX_JSON_BYTES]. */
    fun releasesJson(): String {
        val conn = open(UpdateSource.RELEASES_URL, accept = "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            return conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams asset [assetId] into [target], at most [MAX_APK_BYTES], and returns its SHA-256 (lower-case hex).
     * [expectedSize] (from the release list) must match when known. [cancelled] is polled between reads.
     */
    fun download(assetId: Long, target: File, expectedSize: Long, onProgress: (done: Long, total: Long) -> Unit, cancelled: () -> Boolean): String {
        val conn = open(UpdateSource.assetUrl(assetId), accept = "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            if (total > MAX_APK_BYTES) throw IOException("The file is larger than $MAX_APK_BYTES bytes.")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("Cancelled.")
                        val r = input.read(buf)
                        if (r < 0) break
                        done += r
                        if (done > MAX_APK_BYTES) throw IOException("The file is larger than $MAX_APK_BYTES bytes.")
                        digest.update(buf, 0, r)
                        out.write(buf, 0, r)
                        onProgress(done, total)
                    }
                }
            }
            if (expectedSize > 0 && done != expectedSize) throw IOException("The download stopped after $done of $expectedSize bytes.")
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** A small text asset (a checksum file), at most [MAX_TEXT_BYTES]. */
    fun text(assetId: Long): String {
        val conn = open(UpdateSource.assetUrl(assetId), accept = "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            return conn.inputStream.use { readCapped(it, MAX_TEXT_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(start: String, accept: String): HttpURLConnection {
        var url = URL(start)
        repeat(MAX_REDIRECTS + 1) {
            requireAllowed(url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Tunnels-updater")
                if (UpdateSource.sendsToken(url.host)) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    token?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                url = URL(url, location ?: throw IOException("GitHub redirected without a target."))
                return@repeat
            }
            return conn
        }
        throw IOException("Too many redirects.")
    }

    private fun readCapped(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (out.size() + r > max) throw IOException("GitHub's answer is larger than expected.")
            out.write(buf, 0, r)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** Every hop, before any socket: GitHub over HTTPS, or nothing. */
        fun requireAllowed(url: URL) {
            if (!UpdateSource.isAllowed(url.protocol, url.host)) throw IOException("Refused to connect to ${url.host}: not GitHub over HTTPS.")
        }

        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        const val MAX_TEXT_BYTES = 4 * 1024
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
