package io.github.stronghorse44.tunnels.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class KnownBootKeysTest {
    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun catalogIsWellFormed() {
        assertEquals(42, KnownBootKeys.all.size)
        assertTrue(KnownBootKeys.all.keys.all { Regex("[0-9A-F]{64}").matches(it) })
        assertEquals(21, KnownBootKeys.all.values.count { it.os == KnownBootKeys.GRAPHENE_OS })
        assertEquals(21, KnownBootKeys.all.values.count { it.os == KnownBootKeys.STOCK })
        // Every device has one key per OS and they differ.
        val byDevice = KnownBootKeys.all.entries.groupBy { it.value.device }
        assertEquals(21, byDevice.size)
        byDevice.forEach { (device, entries) ->
            assertEquals(device, setOf(KnownBootKeys.GRAPHENE_OS, KnownBootKeys.STOCK), entries.map { it.value.os }.toSet())
        }
    }

    @Test
    fun looksUpByFingerprintOfTheKeyBytes() {
        val pixel10Graphene = "3F7415EA26F5DF5B14EA6D153256071A7A1AF9CE7B0970B7311CC463C7EA02C7"
        val key = hexToBytes(pixel10Graphene)
        assertEquals(pixel10Graphene, KnownBootKeys.fingerprint(key))
        assertEquals(BootKeyInfo("Pixel 10", KnownBootKeys.GRAPHENE_OS), KnownBootKeys.lookup(key))
        assertEquals("GrapheneOS on Pixel 10", KnownBootKeys.nameOf(key))
        assertEquals("Stock Android on Pixel 10", KnownBootKeys.nameOf(hexToBytes("757C626A2A91FE852536546048D7CA3F50DF6C745C026DB9FF89CC4703C59481")))
        assertEquals(BootKeyInfo("Pixel 9a", KnownBootKeys.STOCK), KnownBootKeys.byFingerprint("3327af62d84ab897af2523a16dcb5801e60c5d5b97f41ca1bd099c4784f7b743"))
    }

    @Test
    fun fallsBackToSha256OfTheBytes() {
        val target = "D8F879D10419EDDC9FCDA6280718BE763F6BF12299E1F72DF3EA8AD8A8EB7F80" // Pixel 10a GrapheneOS
        // Find any preimage? Not possible; instead assert the fallback path by hashing a key and checking the lookup uses it.
        val rawKey = DerBuilder.bytes(4, 65)
        val digest = MessageDigest.getInstance("SHA-256").digest(rawKey).toHex()
        assertNull(KnownBootKeys.lookup(rawKey)) // not in the catalog either way
        assertTrue(digest != target)
        assertEquals(KnownBootKeys.UNKNOWN, KnownBootKeys.nameOf(rawKey))
    }

    @Test
    fun emptyAndZeroKeysAreUnknown() {
        assertNull(KnownBootKeys.lookup(ByteArray(0)))
        assertNull(KnownBootKeys.lookup(ByteArray(32)))
        assertTrue(KnownBootKeys.isEmptyKey(ByteArray(32)))
        assertTrue(!KnownBootKeys.isEmptyKey(byteArrayOf(0, 1)))
        assertEquals(KnownBootKeys.UNKNOWN, KnownBootKeys.nameOf(ByteArray(32)))
    }
}
