package io.github.stronghorse44.tunnels.attestation

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the silicon tunnel. Pure functions over observations, unit-tested here. */
object SiliconRules {
    const val BOOT_UNLOCKED = "BOOT_UNLOCKED"
    const val BOOT_KEY_UNKNOWN = "BOOT_KEY_UNKNOWN"
    const val PATCH_OLD = "PATCH_OLD"
    const val CHAIN_UNVERIFIED = "CHAIN_UNVERIFIED"
    const val ATTESTATION_CHANGED = "ATTESTATION_CHANGED"
    const val NO_STRONGBOX = "NO_STRONGBOX"
    const val ATTESTATION_UNAVAILABLE = "ATTESTATION_UNAVAILABLE"

    /** An OS patch level older than this many days is reported. */
    const val MAX_PATCH_AGE_DAYS = 60

    /**
     * State CRITICAL: the bootloader is unlocked, or verified boot reports Unverified or Failed. SelfSigned is
     * what a locked device running GrapheneOS (or any OS signed with its own key) reports, so it is not an
     * unlock; [bootKeyUnknown] judges that case by whether the key is a published one.
     */
    val bootUnlocked: FindingRule = Rules.perSubject(BOOT_UNLOCKED, Severity.CRITICAL) { _, obs ->
        val locked = SiliconKeys.value(obs, SiliconKeys.BOOT_LOCKED)
        val state = SiliconKeys.value(obs, SiliconKeys.BOOT_STATE)
        if (locked == null && state == null) return@perSubject null
        when {
            locked == "false" -> "The bootloader is unlocked" +
                (if (state != null && state != VerifiedBootState.VERIFIED.label) " and verified boot reports $state" else "") +
                ". Anyone with the phone in hand can replace the operating system without your password. Lock it from the bootloader after making sure the installed OS is the one you want."
            state == VerifiedBootState.UNVERIFIED.label || state == VerifiedBootState.FAILED.label ->
                "Verified boot reports $state: the bootloader could not confirm the operating system it started. Check for a pending update and verify the installation."
            state != null && VerifiedBootState.entries.none { it.label == state } ->
                "Verified boot reports an unexpected state ($state)."
            else -> null
        }
    }

    /** State WARN: the device booted with a key that matches no published GrapheneOS or stock Pixel key. */
    val bootKeyUnknown: FindingRule = Rules.perSubject(BOOT_KEY_UNKNOWN, Severity.WARN) { _, obs ->
        val name = SiliconKeys.value(obs, SiliconKeys.BOOT_KEY_NAME) ?: return@perSubject null
        val hash = SiliconKeys.value(obs, SiliconKeys.BOOT_KEY_HASH) ?: return@perSubject null
        if (name != KnownBootKeys.UNKNOWN) return@perSubject null
        if (SiliconKeys.value(obs, SiliconKeys.BOOT_LOCKED) == "false") return@perSubject null // BOOT_UNLOCKED covers it
        if (hash.isEmpty() || hash.all { it == '0' }) {
            return@perSubject "The bootloader reported an empty verified boot key, so the operating system's signer cannot be identified."
        }
        "The operating system was signed with a key this app does not recognise. Its fingerprint is $hash. " +
            "Compare it with the value GrapheneOS publishes for your device (grapheneos.org/install/web#verifying-installation); if it differs, the installed OS is not what it claims to be."
    }

    /** State WARN: the hardware-attested OS patch level is older than [MAX_PATCH_AGE_DAYS]. */
    val patchOld: FindingRule = Rules.perSubject(PATCH_OLD, Severity.WARN) { _, obs ->
        val age = SiliconKeys.value(obs, SiliconKeys.OS_PATCH_AGE_DAYS)?.toLongOrNull() ?: return@perSubject null
        if (age <= MAX_PATCH_AGE_DAYS) return@perSubject null
        val level = SiliconKeys.value(obs, SiliconKeys.OS_PATCH)?.toIntOrNull()
        val shown = level?.let(PatchLevel::format) ?: "unknown"
        "The security patch level the hardware vouches for is $shown, ${PatchLevel.describeAge(age)}. Install the pending system update; if none is offered, this OS is no longer receiving fixes."
    }

    /** State INFO: the attestation chain could not be tied to a Google root. */
    val chainUnverified: FindingRule = Rules.perSubject(CHAIN_UNVERIFIED, Severity.INFO) { _, obs ->
        val verified = SiliconKeys.value(obs, SiliconKeys.CHAIN_VERIFIED) ?: return@perSubject null
        when {
            verified == "true" -> null
            verified == "false" -> "The attestation certificate chain does not end at a Google attestation root, so the hardware values above are not vouched for by Google. On an emulator or a device without provisioned attestation keys this is expected."
            else -> "The attestation certificate chain could not be checked (${verified.removePrefix("unverified: ")})."
        }
    }

    /** Sticky CRITICAL: the verified boot key, state or lock state is not what the last scan saw. */
    val attestationChanged: FindingRule = Rules.onChange(ATTESTATION_CHANGED, Severity.CRITICAL) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        when (c.key.key) {
            SiliconKeys.BOOT_KEY_HASH -> "The verified boot key changed from ${short(c.before.value)} to ${short(c.after.value)}. " +
                "This happens only when a different operating system is installed; if you did not do that, treat the phone as compromised."
            SiliconKeys.BOOT_STATE -> "Verified boot state changed from ${c.before.value} to ${c.after.value}."
            SiliconKeys.BOOT_LOCKED -> if (c.after.value == "true") "The bootloader is now locked (it was unlocked at the last scan)."
            else "The bootloader was unlocked since the last scan. Unlocking wipes the device, so if this phone kept its data, the report itself is suspect."
            else -> null
        }
    }

    /** State INFO: a Pixel whose attested key is not in StrongBox (the Titan security chip). */
    val noStrongBox: FindingRule = Rules.perSubject(NO_STRONGBOX, Severity.INFO) { _, obs ->
        val strongbox = SiliconKeys.value(obs, SiliconKeys.STRONGBOX) ?: return@perSubject null
        if (strongbox != "false") return@perSubject null
        if (SiliconKeys.value(obs, SiliconKeys.DEVICE_BRAND) != SiliconKeys.PIXEL_BRAND) return@perSubject null
        "This Pixel did not provide a StrongBox (Titan chip) key; the attestation came from the main processor's trusted environment instead. Pixels normally offer StrongBox, so check for a pending update."
    }

    /** State INFO: no attestation at all; the device may lack provisioned keys or the keystore refused. */
    val attestationUnavailable: FindingRule = Rules.perSubject(ATTESTATION_UNAVAILABLE, Severity.INFO) { _, obs ->
        val error = SiliconKeys.value(obs, SiliconKeys.ATTESTATION_ERROR) ?: return@perSubject null
        "Hardware attestation was not available: $error. Without it this tunnel cannot confirm the boot state or patch level the hardware sees."
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(
        bootUnlocked, attestationChanged, bootKeyUnknown, patchOld, chainUnverified, noStrongBox, attestationUnavailable,
    )

    private fun short(hex: String): String = if (hex.length <= 12) hex else hex.take(12) + "…"
}
