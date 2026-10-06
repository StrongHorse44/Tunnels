package io.github.stronghorse44.tunnels.breaches

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Where the fetched catalogue file lives between the fetch and Linx reading it: in this process's memory, never on
 * disk (specs/B11-linx.md section 10.3). One entry at a time; a new fetch replaces the old one. It is dropped after
 * [LIFETIME_MS] or once it has been served [MAX_SERVES] times, whichever comes first, and its bytes are zeroed.
 * Reading it needs the token minted by [put]: 128 random bits, hex.
 *
 * "Sent 3 times" means served three times: a serve is the moment the bytes leave this process.
 */
class BreachHolder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val mintToken: () -> String = ::randomToken,
    /** Runs [action] once after the given delay (best effort); the default is a daemon timer thread. */
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
) {
    /** What the screen may know about the held file, never its bytes. */
    data class Info(val token: String, val displayName: String, val size: Int, val expiresAtMs: Long, val servesLeft: Int)

    private class Entry(val token: String, val displayName: String, val bytes: ByteArray, val expiresAtMs: Long, var servesLeft: Int)

    private var held: Entry? = null

    /** Holds [bytes] (the caller must not keep or change them) and returns the token that reads them. */
    @Synchronized
    fun put(bytes: ByteArray, displayName: String): String {
        drop()
        val token = mintToken()
        val expires = clock() + LIFETIME_MS
        held = Entry(token, displayName, bytes, expires, MAX_SERVES)
        schedule(LIFETIME_MS + 1_000) { expireIfDue() }
        return token
    }

    /** The held file's facts, or null when nothing is held (expired and exhausted entries are dropped here). */
    @Synchronized
    fun info(): Info? {
        expireIfDue()
        return held?.let { Info(it.token, it.displayName, it.bytes.size, it.expiresAtMs, it.servesLeft) }
    }

    /** Facts for the provider's `query`: the display name and size for [token], without counting a serve. */
    @Synchronized
    fun describe(token: String): Info? = info()?.takeIf { matches(token, it.token) }

    /**
     * A copy of the held bytes for [token], counting one serve; null for an unknown, expired or used-up token. The
     * copy is the caller's to write out; the held bytes are zeroed when the last serve is taken.
     */
    @Synchronized
    fun take(token: String): ByteArray? {
        expireIfDue()
        val e = held ?: return null
        if (!matches(token, e.token)) return null
        val copy = e.bytes.copyOf()
        if (--e.servesLeft <= 0) drop()
        return copy
    }

    /** Drops the entry when its time is up. */
    @Synchronized
    fun expireIfDue() {
        val e = held ?: return
        if (clock() >= e.expiresAtMs) drop()
    }

    @Synchronized
    fun clear() = drop()

    private fun drop() {
        held?.bytes?.fill(0)
        held = null
    }

    private fun matches(given: String, actual: String): Boolean =
        MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    companion object {
        const val LIFETIME_MS = 10 * 60 * 1000L
        const val MAX_SERVES = 3

        private val random = SecureRandom()

        /** 128 random bits as 32 lower-case hex characters. */
        fun randomToken(): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun isToken(text: String): Boolean = text.length == 32 && text.all { it in '0'..'9' || it in 'a'..'f' }

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "breach-holder").apply { isDaemon = true } }
        }

        /** The one holder of this process, which also drops an expired file without anyone asking. */
        val shared: BreachHolder by lazy {
            BreachHolder(schedule = { delay, action -> timer.schedule(action, delay, TimeUnit.MILLISECONDS) })
        }
    }
}
