package io.github.stronghorse44.tunnels.attestation

import io.github.stronghorse44.tunnels.model.Observation

/** Observation key schema of the silicon tunnel. There is one subject, [SUBJECT]. */
object SiliconKeys {
    const val TUNNEL_ID = "silicon"
    const val SUBJECT = "device"

    /** Verified, SelfSigned, Unverified, Failed, or "state N" for an unknown code. */
    const val BOOT_STATE = "boot:state"
    /** "true" when the bootloader is locked. */
    const val BOOT_LOCKED = "boot:locked"
    /** Uppercase hex of verifiedBootKey (64 chars on Pixels). */
    const val BOOT_KEY_HASH = "boot:keyHash"
    /** "GrapheneOS on Pixel 10", "Stock Android on Pixel 9", or "unknown". */
    const val BOOT_KEY_NAME = "boot:keyName"
    /** First 16 hex chars of verifiedBootHash, or "none" when the record carries none. */
    const val BOOT_HASH = "boot:hash"
    /** Six-digit OS version from the record, e.g. 160000. */
    const val OS_VERSION = "os:version"
    /** YYYYMM. */
    const val OS_PATCH = "os:patch"
    /** Days between os:patch and the scan date. */
    const val OS_PATCH_AGE_DAYS = "os:patchAgeDays"
    /** YYYYMMDD. */
    const val VENDOR_PATCH = "vendor:patch"
    /** YYYYMMDD. */
    const val BOOT_PATCH = "boot:patch"
    /** Software, TrustedEnvironment or StrongBox. */
    const val ATTESTATION_LEVEL = "attestation:level"
    const val KEYMASTER_LEVEL = "keymaster:level"
    const val ATTESTATION_VERSION = "attestation:version"
    const val KEYMASTER_VERSION = "keymaster:version"
    /** "matched" when the record echoes the random challenge this scan sent, "mismatch" otherwise. */
    const val ATTESTATION_CHALLENGE = "attestation:challenge"
    const val CHAIN_LENGTH = "chain:length"
    /** "true", "false", or "unverified: reason". */
    const val CHAIN_VERIFIED = "chain:verified"
    /** Name of the bundled root the chain ended at; only present when chain:verified is true. */
    const val CHAIN_ROOT = "chain:root"
    /** "true" when the attested key lives in StrongBox. */
    const val STRONGBOX = "strongbox"
    /** Build.BRAND, lowercased. */
    const val DEVICE_BRAND = "device:brand"
    /** Build.MODEL. */
    const val DEVICE_MODEL = "device:model"
    /** Build.VERSION.SECURITY_PATCH as the OS reports it (YYYY-MM-DD). */
    const val DEVICE_SECURITY_PATCH = "device:securityPatch"
    /** Build.VERSION.RELEASE. */
    const val OS_RELEASE = "os:release"
    /** Present instead of the attestation keys when the attestation could not be produced or read. */
    const val ATTESTATION_ERROR = "attestation:error"
    /** Attestation id brand/device/product from the record, when the OS includes them. */
    const val ATTESTED_BRAND = "attested:brand"
    const val ATTESTED_DEVICE = "attested:device"
    const val ATTESTED_PRODUCT = "attested:product"

    const val PIXEL_BRAND = "google"

    /** Every key the tunnel may emit, for tests. */
    val all: Set<String> = setOf(
        BOOT_STATE, BOOT_LOCKED, BOOT_KEY_HASH, BOOT_KEY_NAME, BOOT_HASH, OS_VERSION, OS_PATCH, OS_PATCH_AGE_DAYS,
        VENDOR_PATCH, BOOT_PATCH, ATTESTATION_LEVEL, KEYMASTER_LEVEL, ATTESTATION_VERSION, KEYMASTER_VERSION,
        ATTESTATION_CHALLENGE, CHAIN_LENGTH, CHAIN_VERIFIED, CHAIN_ROOT, STRONGBOX, DEVICE_BRAND, DEVICE_MODEL, DEVICE_SECURITY_PATCH,
        OS_RELEASE, ATTESTATION_ERROR, ATTESTED_BRAND, ATTESTED_DEVICE, ATTESTED_PRODUCT,
    )

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    /** "3F74 15EA 26F5 DF5B …" style grouping for display. */
    fun groupHex(hex: String, group: Int = 4): String = hex.chunked(group).joinToString(" ")

    /** The first 64-hex-char token in a text, as written into BOOT_KEY_UNKNOWN evidence. */
    fun keyHashIn(text: String): String? = HEX64.find(text)?.value

    private val HEX64 = Regex("[0-9A-F]{64}")
}
