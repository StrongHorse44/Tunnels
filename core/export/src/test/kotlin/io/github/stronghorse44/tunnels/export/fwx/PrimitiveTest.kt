// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/PrimitiveTest.kt at a4e418d (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Section 9.1: PBKDF2 against RFC 7914 section 11, HKDF against RFC 5869 A.1 to A.3, and section 3.1 rules. */
class PrimitiveTest {
    @Test
    fun pbkdf2Rfc7914One() {
        val out = FwxKdf.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            T.hex(out),
        )
    }

    @Test
    fun pbkdf2Rfc7914Two() {
        val out = FwxKdf.pbkdf2("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            T.hex(out),
        )
    }

    private fun hkdf(ikm: String, salt: String, info: String, length: Int, prk: String, okm: String) {
        val p = FwxKdf.hkdfExtract(T.hex(salt), T.hex(ikm))
        assertEquals(prk, T.hex(p))
        assertEquals(okm, T.hex(FwxKdf.hkdfExpand(p, T.hex(info), length)))
    }

    @Test
    fun hkdfRfc5869A1() = hkdf(
        "0b".repeat(22), "000102030405060708090a0b0c", "f0f1f2f3f4f5f6f7f8f9", 42,
        "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
    )

    @Test
    fun hkdfRfc5869A2() = hkdf(
        range(0x00, 0x4f), range(0x60, 0xaf), range(0xb0, 0xff), 82,
        "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
        "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f1d87",
    )

    @Test
    fun hkdfRfc5869A3() = hkdf(
        "0b".repeat(22), "", "", 42,
        "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
        "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
    )

    private fun range(from: Int, to: Int) = T.hex(ByteArray(to - from + 1) { (from + it).toByte() })

    @Test
    fun passphraseIsNfcUtf8() {
        val expected = "4772c3bcc39f6520617573204bc3b66c6e20f09f9491"
        assertEquals(expected, T.hex(FwxKdf.passphraseBytes(T.V4_PASSPHRASE.toCharArray(), forWriter = true)))
        assertEquals(expected, T.hex(FwxKdf.passphraseBytes(T.V5_PASSPHRASE.toCharArray(), forWriter = true)))
        assertEquals(16, T.V4_PASSPHRASE.codePointCount(0, T.V4_PASSPHRASE.length))
        assertEquals(18, T.V5_PASSPHRASE.codePointCount(0, T.V5_PASSPHRASE.length))
    }

    /** NFC, not NFKC: compatibility characters keep their own bytes. */
    @Test
    fun passphraseIsNotNfkc() {
        // U+FB01 (fi ligature) and U+FF21 (full-width A) stay as typed under NFC; NFKC would make them "fi" and "A".
        assertEquals("efac81efbca1", T.hex(FwxKdf.passphraseBytes("\uFB01\uFF21".toCharArray(), forWriter = false)))
        // Not already NFC, so the normalising branch runs: e + U+0301 composes to U+00E9, the ligature stays.
        assertEquals("c3a9efac81", T.hex(FwxKdf.passphraseBytes("e\u0301\uFB01".toCharArray(), forWriter = false)))
    }

    private fun badPassphrase(p: CharArray, forWriter: Boolean) {
        try {
            FwxKdf.passphraseBytes(p, forWriter)
            fail("accepted")
        } catch (e: FwxException) {
            assertEquals(FwxError.BAD_PASSPHRASE, e.code)
        }
    }

    @Test
    fun passphraseRules() {
        badPassphrase(CharArray(0), forWriter = false)
        badPassphrase("abc\uD800defghijklmn".toCharArray(), forWriter = false)
        badPassphrase("abcdefghijklm\uDC00".toCharArray(), forWriter = false)
        badPassphrase("abcdefghijklm\uD800".toCharArray(), forWriter = false)
        badPassphrase(CharArray(1025) { 'a' }, forWriter = false)
        assertEquals(1024, FwxKdf.passphraseBytes(CharArray(1024) { 'a' }, false).size)
        // 342 three-byte characters are 1026 bytes.
        badPassphrase(CharArray(342) { '€' }, forWriter = false)
        // Writers need 12 code points after NFC; readers take any non-empty passphrase.
        badPassphrase("elevenchars".toCharArray(), forWriter = true)
        assertArrayEquals("elevenchars".toByteArray(), FwxKdf.passphraseBytes("elevenchars".toCharArray(), false))
        assertEquals(12, FwxKdf.passphraseBytes("twelve chars".toCharArray(), true).size)
        // 11 code points, 12 UTF-16 units: a non-BMP character counts once.
        badPassphrase("ten chars 🔑".toCharArray(), forWriter = true)
        // 12 code points before NFC, 11 after (e + combining acute composes).
        badPassphrase("abcdefghijé".toCharArray(), forWriter = true)
    }

    @Test
    fun constantTimeEquals() {
        assertEquals(true, FwxKdf.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertEquals(false, FwxKdf.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertEquals(false, FwxKdf.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}
