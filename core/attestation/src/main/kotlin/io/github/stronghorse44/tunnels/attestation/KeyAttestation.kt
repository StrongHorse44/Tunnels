package io.github.stronghorse44.tunnels.attestation

/** Where a value in the attestation record was enforced. */
enum class SecurityLevel(val code: Int, val label: String) {
    SOFTWARE(0, "Software"),
    TRUSTED_ENVIRONMENT(1, "TrustedEnvironment"),
    STRONG_BOX(2, "StrongBox"),
    ;

    companion object {
        fun of(code: Int): SecurityLevel? = entries.firstOrNull { it.code == code }
    }
}

/** The bootloader's verdict on the boot chain. */
enum class VerifiedBootState(val code: Int, val label: String) {
    VERIFIED(0, "Verified"),
    SELF_SIGNED(1, "SelfSigned"),
    UNVERIFIED(2, "Unverified"),
    FAILED(3, "Failed"),
    ;

    companion object {
        fun of(code: Int): VerifiedBootState? = entries.firstOrNull { it.code == code }
    }
}

/** `RootOfTrust` ([704]) of an AuthorizationList. */
data class RootOfTrust(
    /** On recent Pixels this is the 32-byte digest of the verified boot (AVB) public key, all zeros when unlocked. */
    val verifiedBootKey: ByteArray,
    val deviceLocked: Boolean,
    val verifiedBootState: VerifiedBootState?,
    /** Raw enum value, kept so an unknown state still shows up. */
    val verifiedBootStateCode: Int,
    /** Absent before attestation version 3. */
    val verifiedBootHash: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is RootOfTrust &&
        verifiedBootKey.contentEquals(other.verifiedBootKey) &&
        deviceLocked == other.deviceLocked &&
        verifiedBootStateCode == other.verifiedBootStateCode &&
        (verifiedBootHash?.contentEquals(other.verifiedBootHash ?: ByteArray(0)) ?: (other.verifiedBootHash == null))

    override fun hashCode(): Int = verifiedBootKey.contentHashCode() * 31 + verifiedBootStateCode
}

/** The subset of an AuthorizationList this app reads. Everything else is skipped. */
data class AuthorizationList(
    val rootOfTrust: RootOfTrust? = null,
    /** Six digits, e.g. 150000 for Android 15.0.0. */
    val osVersion: Int? = null,
    /** YYYYMM. */
    val osPatchLevel: Int? = null,
    /** YYYYMMDD. */
    val vendorPatchLevel: Int? = null,
    /** YYYYMMDD. */
    val bootPatchLevel: Int? = null,
    val attestationIdBrand: String? = null,
    val attestationIdDevice: String? = null,
    val attestationIdProduct: String? = null,
    /** Tag numbers that were present and skipped, for diagnostics. */
    val skippedTags: List<Int> = emptyList(),
) {
    val isEmpty: Boolean
        get() = rootOfTrust == null && osVersion == null && osPatchLevel == null && vendorPatchLevel == null &&
            bootPatchLevel == null && attestationIdBrand == null && attestationIdDevice == null && attestationIdProduct == null
}

/** The decoded key attestation extension of a leaf certificate. */
data class KeyAttestation(
    val attestationVersion: Int,
    val attestationSecurityLevel: SecurityLevel?,
    val attestationSecurityLevelCode: Int,
    /** KeyMint version on current devices; the field keeps its historical name. */
    val keymasterVersion: Int,
    val keymasterSecurityLevel: SecurityLevel?,
    val keymasterSecurityLevelCode: Int,
    val attestationChallenge: ByteArray,
    val softwareEnforced: AuthorizationList,
    val teeEnforced: AuthorizationList,
) {
    /** The root of trust the hardware vouched for, falling back to the software list for software-only attestation. */
    val rootOfTrust: RootOfTrust? get() = teeEnforced.rootOfTrust ?: softwareEnforced.rootOfTrust

    /** Hardware-enforced values first, software values only where the hardware list has none. */
    fun enforced(): AuthorizationList = AuthorizationList(
        rootOfTrust = rootOfTrust,
        osVersion = teeEnforced.osVersion ?: softwareEnforced.osVersion,
        osPatchLevel = teeEnforced.osPatchLevel ?: softwareEnforced.osPatchLevel,
        vendorPatchLevel = teeEnforced.vendorPatchLevel ?: softwareEnforced.vendorPatchLevel,
        bootPatchLevel = teeEnforced.bootPatchLevel ?: softwareEnforced.bootPatchLevel,
        attestationIdBrand = teeEnforced.attestationIdBrand ?: softwareEnforced.attestationIdBrand,
        attestationIdDevice = teeEnforced.attestationIdDevice ?: softwareEnforced.attestationIdDevice,
        attestationIdProduct = teeEnforced.attestationIdProduct ?: softwareEnforced.attestationIdProduct,
    )

    companion object {
        /** X.509 extension OID of the Android key attestation record. */
        const val OID = "1.3.6.1.4.1.11129.2.1.17"

        const val TAG_ROOT_OF_TRUST = 704
        const val TAG_OS_VERSION = 705
        const val TAG_OS_PATCH_LEVEL = 706
        const val TAG_ATTESTATION_ID_BRAND = 710
        const val TAG_ATTESTATION_ID_DEVICE = 711
        const val TAG_ATTESTATION_ID_PRODUCT = 712
        const val TAG_VENDOR_PATCH_LEVEL = 718
        const val TAG_BOOT_PATCH_LEVEL = 719

        /** Attestation id strings longer than this are cut; they are brand/device/product names, not data. */
        private const val MAX_ID_CHARS = 64

        /**
         * Parses the extension. Accepts either the raw `KeyDescription` SEQUENCE or, as
         * `X509Certificate.getExtensionValue` returns it, that SEQUENCE wrapped in an OCTET STRING.
         * Throws [DerException] with a readable message on truncated or malformed input.
         */
        fun parse(extensionValue: ByteArray): KeyAttestation {
            if (extensionValue.isEmpty()) throw DerException("attestation extension is empty")
            var top = Der.read(extensionValue)
            if (top.isOctetString) top = Der.read(extensionValue, top.contentStart, top.contentEnd)
            if (!top.isSequence) throw DerException("attestation extension does not start with a SEQUENCE (${top.describe()})")
            if (top.end != extensionValue.size && !Der.read(extensionValue).isOctetString) {
                throw DerException("${extensionValue.size - top.end} trailing bytes after the attestation record")
            }
            val fields = top.children()
            if (fields.size < 8) throw DerException("truncated: KeyDescription has ${fields.size} fields, expected at least 8")
            val attestationVersion = fields[0].asInt()
            val attestationLevel = fields[1].asInt()
            val keymasterVersion = fields[2].asInt()
            val keymasterLevel = fields[3].asInt()
            val challenge = fields[4].asOctetString()
            // fields[5] is uniqueId, only present for system apps and never recorded here.
            val software = parseAuthorizationList(fields[6], "softwareEnforced")
            val tee = parseAuthorizationList(fields[7], "teeEnforced")
            return KeyAttestation(
                attestationVersion = attestationVersion,
                attestationSecurityLevel = SecurityLevel.of(attestationLevel),
                attestationSecurityLevelCode = attestationLevel,
                keymasterVersion = keymasterVersion,
                keymasterSecurityLevel = SecurityLevel.of(keymasterLevel),
                keymasterSecurityLevelCode = keymasterLevel,
                attestationChallenge = challenge,
                softwareEnforced = software,
                teeEnforced = tee,
            )
        }

        /** [Result] form of [parse] for callers that prefer not to catch. */
        fun tryParse(extensionValue: ByteArray): Result<KeyAttestation> = runCatching { parse(extensionValue) }

        private fun parseAuthorizationList(value: DerValue, name: String): AuthorizationList {
            if (!value.isSequence) throw DerException("$name is not a SEQUENCE (${value.describe()})")
            var list = AuthorizationList()
            val skipped = ArrayList<Int>()
            for (entry in value.children()) {
                if (!entry.isContextSpecific) throw DerException("$name holds an untagged value (${entry.describe()})")
                when (entry.tagNumber) {
                    TAG_ROOT_OF_TRUST -> list = list.copy(rootOfTrust = parseRootOfTrust(entry.explicit()))
                    TAG_OS_VERSION -> list = list.copy(osVersion = entry.explicit().asInt())
                    TAG_OS_PATCH_LEVEL -> list = list.copy(osPatchLevel = entry.explicit().asInt())
                    TAG_VENDOR_PATCH_LEVEL -> list = list.copy(vendorPatchLevel = entry.explicit().asInt())
                    TAG_BOOT_PATCH_LEVEL -> list = list.copy(bootPatchLevel = entry.explicit().asInt())
                    TAG_ATTESTATION_ID_BRAND -> list = list.copy(attestationIdBrand = idString(entry.explicit()))
                    TAG_ATTESTATION_ID_DEVICE -> list = list.copy(attestationIdDevice = idString(entry.explicit()))
                    TAG_ATTESTATION_ID_PRODUCT -> list = list.copy(attestationIdProduct = idString(entry.explicit()))
                    else -> skipped += entry.tagNumber
                }
            }
            return list.copy(skippedTags = skipped)
        }

        private fun parseRootOfTrust(value: DerValue): RootOfTrust {
            if (!value.isSequence) throw DerException("rootOfTrust is not a SEQUENCE (${value.describe()})")
            val parts = value.children()
            if (parts.size < 3) throw DerException("truncated: rootOfTrust has ${parts.size} fields, expected at least 3")
            val stateCode = parts[2].asInt()
            return RootOfTrust(
                verifiedBootKey = parts[0].asOctetString(),
                deviceLocked = parts[1].asBoolean(),
                verifiedBootState = VerifiedBootState.of(stateCode),
                verifiedBootStateCode = stateCode,
                verifiedBootHash = parts.getOrNull(3)?.asOctetString(),
            )
        }

        private fun idString(value: DerValue): String {
            val bytes = value.asOctetString()
            val text = bytes.toString(Charsets.UTF_8).filter { it.code in 0x20..0x7E }
            return if (text.length > MAX_ID_CHARS) text.take(MAX_ID_CHARS) else text
        }
    }
}

/** Uppercase hex, as Auditor prints fingerprints. */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
