package io.github.stronghorse44.tunnels.silicon

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.attestation.SiliconRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Attests a real key on the emulator through the provider, as the engine would, and checks the observation schema. */
@RunWith(AndroidJUnit4::class)
class SiliconSmokeTest {
    private val hex = Regex("[0-9A-F]*")

    @Test
    fun scansTheDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = SiliconTunnels().create(context).single()
        assertEquals(SiliconKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())
        assertTrue(module.specialAccess.isEmpty())
        assertEquals(SiliconRules.all.size, module.rules.size)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.isNotEmpty())
        assertTrue(obs.all { it.tunnelId == SiliconKeys.TUNNEL_ID && it.subject == SiliconKeys.SUBJECT })
        val keys = obs.map { it.key }
        assertEquals("keys are unique: $keys", keys.size, keys.toSet().size)
        assertTrue("unknown keys: ${keys - SiliconKeys.all}", keys.all { it in SiliconKeys.all })
        assertTrue(obs.none { it.value.isEmpty() })
        assertTrue("attested key deleted", Attestor.keyAbsent())

        fun v(key: String) = SiliconKeys.value(obs, key)
        assertEquals(Build.MODEL, v(SiliconKeys.DEVICE_MODEL))
        assertEquals(Build.VERSION.RELEASE, v(SiliconKeys.OS_RELEASE))
        assertEquals(Build.BRAND.lowercase(), v(SiliconKeys.DEVICE_BRAND))
        assertNotNull(v(SiliconKeys.DEVICE_SECURITY_PATCH))

        val error = v(SiliconKeys.ATTESTATION_ERROR)
        if (error != null && v(SiliconKeys.CHAIN_LENGTH) == null) {
            // The keystore refused to attest at all (no provisioned keys on this image): the error is the whole story.
            assertTrue(error.isNotBlank())
            return@runBlocking
        }
        assertTrue(v(SiliconKeys.CHAIN_LENGTH)!!.toInt() >= 1)
        assertTrue(v(SiliconKeys.STRONGBOX) in setOf("true", "false"))
        val chain = v(SiliconKeys.CHAIN_VERIFIED)!!
        assertTrue(chain, chain == "true" || chain == "false" || chain.startsWith("unverified: "))
        if (chain == "true") assertNotNull(v(SiliconKeys.CHAIN_ROOT)) else assertEquals(null, v(SiliconKeys.CHAIN_ROOT))
        if (error != null) return@runBlocking // chain read, record unreadable: the error explains why

        assertNotNull(v(SiliconKeys.ATTESTATION_VERSION)!!.toInt())
        assertTrue(v(SiliconKeys.ATTESTATION_LEVEL) in setOf("Software", "TrustedEnvironment", "StrongBox") || v(SiliconKeys.ATTESTATION_LEVEL)!!.startsWith("level "))
        assertEquals("matched", v(SiliconKeys.ATTESTATION_CHALLENGE))
        v(SiliconKeys.BOOT_STATE)?.let { state ->
            assertTrue(state, state in setOf("Verified", "SelfSigned", "Unverified", "Failed") || state.startsWith("state "))
            assertTrue(v(SiliconKeys.BOOT_LOCKED) in setOf("true", "false"))
            assertTrue(hex.matches(v(SiliconKeys.BOOT_KEY_HASH)!!))
            assertNotNull(v(SiliconKeys.BOOT_KEY_NAME))
            assertTrue(v(SiliconKeys.BOOT_HASH)!!.let { it == "none" || (hex.matches(it) && it.length <= 16) })
        }
        v(SiliconKeys.OS_PATCH)?.let { patch ->
            assertTrue(patch, patch.toInt() in 190001..999912)
            assertNotNull(v(SiliconKeys.OS_PATCH_AGE_DAYS)!!.toLong())
        }
        v(SiliconKeys.VENDOR_PATCH)?.let { assertTrue(it, it.toInt() > 0) }
        v(SiliconKeys.BOOT_PATCH)?.let { assertTrue(it, it.toInt() > 0) }

        // A second scan describes the same hardware (patch age may tick over midnight, so compare the rest).
        val again = module.scan(ScanProgress.NONE)
        val stable = { list: List<io.github.stronghorse44.tunnels.model.Observation> ->
            list.filter { it.key != SiliconKeys.OS_PATCH_AGE_DAYS }.sortedBy { it.key }.map { it.key to it.value }
        }
        assertEquals(stable(obs), stable(again))
        assertTrue(Attestor.keyAbsent())
    }

    @Test
    fun everyFindingKindHasActions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = SiliconTunnels().create(context).single()
        val kinds = listOf(
            SiliconRules.BOOT_UNLOCKED, SiliconRules.BOOT_KEY_UNKNOWN, SiliconRules.PATCH_OLD, SiliconRules.CHAIN_UNVERIFIED,
            SiliconRules.ATTESTATION_CHANGED, SiliconRules.NO_STRONGBOX, SiliconRules.ATTESTATION_UNAVAILABLE,
        )
        for (kind in kinds) {
            val actions = module.actionsFor(FindingDraft(module.id, SiliconKeys.SUBJECT, kind, Severity.INFO, "x"))
            assertTrue(kind, actions.isNotEmpty())
            assertTrue(kind, actions.any { it is FindingAction.OpenSettings && it.action == SiliconTunnel.ACTION_SYSTEM_UPDATE })
            assertTrue(kind, actions.any { it is FindingAction.OpenSettings && it.action == android.provider.Settings.ACTION_SECURITY_SETTINGS })
        }
        val hash = "3F7415EA26F5DF5B14EA6D153256071A7A1AF9CE7B0970B7311CC463C7EA02C7"
        val withHash = module.actionsFor(FindingDraft(module.id, SiliconKeys.SUBJECT, SiliconRules.BOOT_KEY_UNKNOWN, Severity.WARN, "Its fingerprint is $hash."))
        val copy = withHash.first() as FindingAction.Perform
        assertEquals("Copy key hash", copy.label)
        // Writing the clipboard needs a focused window on some images; the result text is what we check when it works.
        runCatching { copy.run() }.onSuccess { assertEquals("Copied", it) }
        val without = module.actionsFor(FindingDraft(module.id, SiliconKeys.SUBJECT, SiliconRules.BOOT_KEY_UNKNOWN, Severity.WARN, "no hash here"))
        assertTrue(without.none { it is FindingAction.Perform })
    }
}
