package io.github.stronghorse44.tunnels.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncryptedFileTest {
    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun roundTrip() {
        val plain = BundleFormat.write(
            SnapshotBundle(listOf(BundleSnapshot(1, 42, true, listOf("doors"))), listOf(BundleObservation(1, "doors", "s", "k", "v"))),
        ).toByteArray()
        val sealed = EncryptedFile.seal(plain, password)
        assertTrue(EncryptedFile.looksSealed(sealed))
        assertEquals("TSNAPE1", String(sealed, 0, 7, Charsets.US_ASCII))
        assertEquals(7 + 16 + 12 + plain.size + 16, sealed.size)
        assertFalse(sealed.copyOfRange(7 + 16 + 12, sealed.size).contentEquals(plain))
        assertArrayEquals(plain, EncryptedFile.open(sealed, password))
    }

    @Test
    fun emptyPayloadWorks() {
        val sealed = EncryptedFile.seal(ByteArray(0), password)
        assertEquals(7 + 16 + 12 + 16, sealed.size)
        assertArrayEquals(ByteArray(0), EncryptedFile.open(sealed, password))
    }

    @Test
    fun sealingTwiceGivesDifferentBytes() {
        val plain = "same".toByteArray()
        assertFalse(EncryptedFile.seal(plain, password).contentEquals(EncryptedFile.seal(plain, password)))
    }

    @Test
    fun wrongPasswordFails() {
        val sealed = EncryptedFile.seal("secret".toByteArray(), password)
        assertFails(sealed, "correct horse battery stapl".toCharArray())
        assertFails(sealed, CharArray(0))
    }

    @Test
    fun emptyPasswordCannotSeal() {
        try {
            EncryptedFile.seal("secret".toByteArray(), CharArray(0))
            fail("empty password accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun flippedBytesFail() {
        val sealed = EncryptedFile.seal("secret summary".toByteArray(), password)
        for (index in listOf(7, 7 + 15, 7 + 16, 7 + 16 + 11, 7 + 16 + 12, sealed.size - 17, sealed.size - 1)) {
            val tampered = sealed.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFails(tampered, password)
        }
        // Magic flipped: not even recognised as a sealed file.
        val wrongMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFalse(EncryptedFile.looksSealed(wrongMagic))
        try {
            EncryptedFile.open(wrongMagic, password)
            fail()
        } catch (_: NotASealedFile) {
        }
    }

    @Test
    fun truncatedFileFails() {
        val sealed = EncryptedFile.seal("secret".toByteArray(), password)
        assertFails(sealed.copyOf(sealed.size - 1), password)
        assertFails(sealed.copyOf(10), password)
        assertFails(ByteArray(0), password)
    }

    private fun assertFails(bytes: ByteArray, pw: CharArray) {
        try {
            EncryptedFile.open(bytes, pw)
            fail("expected WrongPasswordOrCorrupt")
        } catch (_: WrongPasswordOrCorrupt) {
        }
    }
}
