package io.github.stronghorse44.tunnels.attestation

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiliconRulesTest {
    private val t = SiliconKeys.TUNNEL_ID
    private val graphene10 = "3F7415EA26F5DF5B14EA6D153256071A7A1AF9CE7B0970B7311CC463C7EA02C7"
    private val strange = "A".repeat(64)

    private fun device(
        state: String = "SelfSigned",
        locked: Boolean = true,
        keyHash: String = graphene10,
        keyName: String = "GrapheneOS on Pixel 10",
        patchAge: Long = 10,
        patch: Int = 202609,
        chain: String = "true",
        strongbox: Boolean = true,
        brand: String = "google",
        error: String? = null,
    ): List<Observation> = buildList {
        fun add(k: String, v: String) = add(Observation(t, SiliconKeys.SUBJECT, k, v))
        add(SiliconKeys.DEVICE_BRAND, brand)
        add(SiliconKeys.DEVICE_MODEL, "Pixel 10")
        if (error != null) {
            add(SiliconKeys.ATTESTATION_ERROR, error)
            return@buildList
        }
        add(SiliconKeys.BOOT_STATE, state)
        add(SiliconKeys.BOOT_LOCKED, locked.toString())
        add(SiliconKeys.BOOT_KEY_HASH, keyHash)
        add(SiliconKeys.BOOT_KEY_NAME, keyName)
        add(SiliconKeys.OS_PATCH, patch.toString())
        add(SiliconKeys.OS_PATCH_AGE_DAYS, patchAge.toString())
        add(SiliconKeys.CHAIN_VERIFIED, chain)
        add(SiliconKeys.STRONGBOX, strongbox.toString())
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return SiliconRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun healthyGrapheneDeviceHasNoFindings() {
        assertEquals(emptyList<FindingDraft>(), evaluate(device()))
        assertEquals(emptyList<FindingDraft>(), evaluate(device(state = "Verified", keyHash = "757C626A2A91FE852536546048D7CA3F50DF6C745C026DB9FF89CC4703C59481", keyName = "Stock Android on Pixel 10")))
    }

    @Test
    fun bootUnlocked() {
        val unlocked = evaluate(device(locked = false, state = "Unverified", keyHash = "0".repeat(64), keyName = "unknown")).of(SiliconRules.BOOT_UNLOCKED).single()
        assertEquals(Severity.CRITICAL, unlocked.severity)
        assertTrue(unlocked.evidence.startsWith("The bootloader is unlocked and verified boot reports Unverified."))
        assertTrue(!unlocked.sticky)
        // Unlocked already explains the unknown key; no second finding for it.
        assertTrue(evaluate(device(locked = false, keyHash = "0".repeat(64), keyName = "unknown")).of(SiliconRules.BOOT_KEY_UNKNOWN).isEmpty())

        val failed = evaluate(device(state = "Failed")).of(SiliconRules.BOOT_UNLOCKED).single()
        assertTrue(failed.evidence.contains("Failed"))
        val odd = evaluate(device(state = "state 9")).of(SiliconRules.BOOT_UNLOCKED).single()
        assertTrue(odd.evidence.contains("state 9"))
        // SelfSigned on a locked device is GrapheneOS's normal state.
        assertTrue(evaluate(device(state = "SelfSigned")).of(SiliconRules.BOOT_UNLOCKED).isEmpty())
        // No attestation at all: nothing to say about the bootloader.
        assertTrue(evaluate(device(error = "x")).of(SiliconRules.BOOT_UNLOCKED).isEmpty())
    }

    @Test
    fun bootKeyUnknownShowsTheFullHash() {
        val d = evaluate(device(keyHash = strange, keyName = "unknown")).of(SiliconRules.BOOT_KEY_UNKNOWN).single()
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.evidence.contains(strange))
        assertEquals(strange, SiliconKeys.keyHashIn(d.evidence))
        assertTrue(d.evidence.contains("grapheneos.org"))
        val empty = evaluate(device(keyHash = "0".repeat(64), keyName = "unknown")).of(SiliconRules.BOOT_KEY_UNKNOWN).single()
        assertTrue(empty.evidence.contains("empty"))
        assertTrue(evaluate(device()).of(SiliconRules.BOOT_KEY_UNKNOWN).isEmpty())
    }

    @Test
    fun patchOld() {
        assertTrue(evaluate(device(patchAge = 60)).of(SiliconRules.PATCH_OLD).isEmpty())
        val d = evaluate(device(patchAge = 153, patch = 202605)).of(SiliconRules.PATCH_OLD).single()
        assertEquals(Severity.WARN, d.severity)
        assertEquals("The security patch level the hardware vouches for is 2026-05, 5 months old. Install the pending system update; if none is offered, this OS is no longer receiving fixes.", d.evidence)
    }

    @Test
    fun chainUnverified() {
        assertTrue(evaluate(device(chain = "true")).of(SiliconRules.CHAIN_UNVERIFIED).isEmpty())
        val failed = evaluate(device(chain = "false")).of(SiliconRules.CHAIN_UNVERIFIED).single()
        assertEquals(Severity.INFO, failed.severity)
        assertTrue(failed.evidence.contains("does not end at a Google attestation root"))
        val unverified = evaluate(device(chain = "unverified: no certificate chain")).of(SiliconRules.CHAIN_UNVERIFIED).single()
        assertTrue(unverified.evidence.contains("(no certificate chain)"))
    }

    @Test
    fun noStrongBoxOnlyForPixels() {
        val d = evaluate(device(strongbox = false)).of(SiliconRules.NO_STRONGBOX).single()
        assertEquals(Severity.INFO, d.severity)
        assertTrue(evaluate(device(strongbox = false, brand = "samsung")).of(SiliconRules.NO_STRONGBOX).isEmpty())
        assertTrue(evaluate(device(strongbox = true)).of(SiliconRules.NO_STRONGBOX).isEmpty())
    }

    @Test
    fun attestationUnavailable() {
        val d = evaluate(device(error = "ProviderException: Failed to generate key pair")).of(SiliconRules.ATTESTATION_UNAVAILABLE).single()
        assertEquals(Severity.INFO, d.severity)
        assertTrue(d.evidence.contains("Failed to generate key pair"))
        assertEquals(1, evaluate(device(error = "x")).size)
    }

    @Test
    fun attestationChangedIsStickyAndNeverOnFirstScan() {
        val before = device()
        val afterKey = device(keyHash = strange, keyName = "unknown")
        assertTrue(evaluate(afterKey).of(SiliconRules.ATTESTATION_CHANGED).isEmpty())
        val drafts = evaluate(afterKey, before)
        val changed = drafts.of(SiliconRules.ATTESTATION_CHANGED).single()
        assertEquals(Severity.CRITICAL, changed.severity)
        assertTrue(changed.sticky)
        assertTrue(changed.evidence.startsWith("The verified boot key changed from 3F7415EA26F5… to AAAAAAAAAAAA…"))
        assertEquals(1, drafts.of(SiliconRules.BOOT_KEY_UNKNOWN).size)

        val unlockedNow = evaluate(device(locked = false, state = "Unverified"), before)
        val kinds = unlockedNow.map { it.kind }
        assertTrue(kinds.contains(SiliconRules.BOOT_UNLOCKED))
        // Both boot:locked and boot:state changed; the engine keeps one draft per (subject, kind) so two drafts here are fine.
        assertEquals(2, unlockedNow.of(SiliconRules.ATTESTATION_CHANGED).size)
        assertTrue(unlockedNow.of(SiliconRules.ATTESTATION_CHANGED).any { it.evidence.contains("unlocked since the last scan") })

        // Patch level moving forward is not an attestation change.
        assertTrue(evaluate(device(patch = 202610, patchAge = 1), before).isEmpty())
    }

    @Test
    fun helpers() {
        assertEquals("3F74 15EA 26F5", SiliconKeys.groupHex("3F7415EA26F5"))
        assertEquals(null, SiliconKeys.keyHashIn("nothing here"))
        assertEquals(7, SiliconRules.all.size)
        assertTrue(SiliconKeys.all.size >= 24)
    }
}
