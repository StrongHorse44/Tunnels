package io.github.stronghorse44.tunnels.pairing

import io.github.stronghorse44.tunnels.attestation.ChainVerification
import io.github.stronghorse44.tunnels.attestation.GoogleRoots
import io.github.stronghorse44.tunnels.attestation.KeyAttestation
import io.github.stronghorse44.tunnels.attestation.KnownBootKeys
import io.github.stronghorse44.tunnels.attestation.PatchLevel
import io.github.stronghorse44.tunnels.attestation.SecurityLevel
import io.github.stronghorse44.tunnels.attestation.VerifiedBootState
import io.github.stronghorse44.tunnels.attestation.toHex
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/** A phone this verifier paired with, kept in the encrypted store. Hashes and names only. */
data class Pin(
    /** Hex SHA-256 of [identityKey]: how audits find their pin. */
    val id: String,
    /** The paired phone's identity key (SubjectPublicKeyInfo), attested by its hardware at pairing. */
    val identityKey: ByteArray,
    val name: String,
    /** Hex verified boot key at pairing; an audit with another one means a different OS was installed. */
    val bootKey: String,
    val pairedAt: Instant,
    val lastAuditAt: Instant,
    /** Highest OS patch level (YYYYMM) seen; a lower one later is a downgrade. */
    val osPatch: Int?,
    val strongBox: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is Pin && id == other.id && name == other.name && bootKey == other.bootKey &&
        pairedAt == other.pairedAt && lastAuditAt == other.lastAuditAt && osPatch == other.osPatch && strongBox == other.strongBox &&
        identityKey.contentEquals(other.identityKey)

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Settings key of the pins list in the encrypted store. */
        const val KEY = "pairing.pins"

        fun idOf(identityKey: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(identityKey).toHex()

        /** One pin per line, fields separated by `|`; names lose that character. */
        fun encode(pins: List<Pin>): String = pins.joinToString("\n") { p ->
            listOf(
                p.id, Base64.getEncoder().encodeToString(p.identityKey), p.name.replace('|', '/').replace('\n', ' '), p.bootKey,
                p.pairedAt.toEpochMilli().toString(), p.lastAuditAt.toEpochMilli().toString(), p.osPatch?.toString().orEmpty(), p.strongBox.toString(),
            ).joinToString("|")
        }

        /** Damaged lines are skipped. */
        fun decode(text: String?): List<Pin> = text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('|')
            if (f.size != 8) return@mapNotNull null
            runCatching {
                val key = Base64.getDecoder().decode(f[1])
                if (idOf(key) != f[0]) return@mapNotNull null
                Pin(f[0], key, f[2], f[3], Instant.ofEpochMilli(f[4].toLong()), Instant.ofEpochMilli(f[5].toLong()), f[6].toIntOrNull(), f[7] == "true")
            }.getOrNull()
        }
    }
}

/** What the verifier shows: every check with its outcome, and the pin to store when the exchange succeeded. */
data class Verdict(val outcome: Outcome, val checks: List<Check>, val pin: Pin?) {
    enum class Outcome { PAIRED, VERIFIED, FAILED }
    enum class Status { PASS, WARN, FAIL }
    data class Check(val label: String, val status: Status, val detail: String)

    val failed: Boolean get() = outcome == Outcome.FAILED
}

/** The parts of a certificate chain the verifier reads. */
class ReadChain(val verification: ChainVerification, val leafKey: ByteArray?, val leafDer: ByteArray?, val attestation: KeyAttestation?)

/**
 * Judges a [PairingProtocol.Response] against the challenge this phone showed. Plain JVM: certificates, signatures
 * and roots go through java.security and the bundled Google roots, so the same code runs in tests and on the phone.
 */
object PairingVerifier {
    /** An OS patch older than this many days is a warning, as in Silicon. */
    const val MAX_PATCH_AGE_DAYS = 60L

    fun readChain(chain: List<ByteArray>): ReadChain {
        val certs = runCatching { chain.map(GoogleRoots::decode) }.getOrNull()
            ?: return ReadChain(ChainVerification.Unverified("unreadable certificate"), null, null, null)
        val leaf = certs.first()
        val attestation = leaf.getExtensionValue(KeyAttestation.OID)?.let { KeyAttestation.tryParse(it).getOrNull() }
        return ReadChain(GoogleRoots.verify(certs), leaf.publicKey.encoded, chain.first(), attestation)
    }

    fun signatureValid(spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    fun verify(
        response: PairingProtocol.Response,
        challenge: PairingProtocol.Challenge,
        pins: List<Pin>,
        now: Instant,
        reader: (List<ByteArray>) -> ReadChain = ::readChain,
        signatureCheck: (spki: ByteArray, data: ByteArray, signature: ByteArray) -> Boolean = ::signatureValid,
    ): Verdict {
        val checks = ArrayList<Verdict.Check>()
        fun pass(label: String, detail: String) = checks.add(Verdict.Check(label, Verdict.Status.PASS, detail))
        fun warn(label: String, detail: String) = checks.add(Verdict.Check(label, Verdict.Status.WARN, detail))
        fun fail(label: String, detail: String) = checks.add(Verdict.Check(label, Verdict.Status.FAIL, detail))
        fun failed() = Verdict(Verdict.Outcome.FAILED, checks, null)

        if (!response.verifierId.contentEquals(challenge.verifierId)) {
            fail("Exchange", "This code answers another phone's challenge. Scan the code this phone shows, then show the answer.")
            return failed()
        }

        val chain = reader(response.chain)
        when (val v = chain.verification) {
            is ChainVerification.Verified -> pass("Hardware attestation", "The certificate chain ends at ${v.root.name}.")
            is ChainVerification.Failed -> fail("Hardware attestation", "The certificate chain does not verify: ${v.reason}.")
            is ChainVerification.Unverified -> fail("Hardware attestation", "The certificate chain could not be checked: ${v.reason}.")
        }
        val att = chain.attestation
        if (att == null) {
            fail("Attestation record", "The key carries no readable attestation record.")
            return failed()
        }
        if (att.attestationChallenge.contentEquals(challenge.challenge)) {
            pass("Fresh", "The attestation answers the challenge this phone just showed, so it is not a replay.")
        } else {
            fail("Fresh", "The attestation was made for a different challenge: an old answer replayed, or a code for another exchange.")
        }
        when (att.attestationSecurityLevel) {
            SecurityLevel.STRONG_BOX -> pass("Security chip", "Attested by StrongBox, the separate security chip.")
            SecurityLevel.TRUSTED_ENVIRONMENT -> pass("Security chip", "Attested by the trusted environment of the main processor.")
            else -> fail("Security chip", "Attested in software only, which the OS itself could forge.")
        }

        val enforced = att.enforced()
        val root = enforced.rootOfTrust
        val bootKey = root?.verifiedBootKey?.toHex().orEmpty()
        val os = root?.verifiedBootKey?.let(KnownBootKeys::lookup)
        when {
            root == null -> fail("Boot", "The record has no root of trust.")
            !root.deviceLocked -> fail("Boot", "The bootloader is unlocked: anyone with the phone can replace its OS.")
            root.verifiedBootState == VerifiedBootState.VERIFIED || root.verifiedBootState == VerifiedBootState.SELF_SIGNED ->
                pass("Boot", "Bootloader locked, verified boot ${root.verifiedBootState?.label}.")
            else -> fail("Boot", "Verified boot reports ${root.verifiedBootState?.label ?: "state ${root.verifiedBootStateCode}"}.")
        }
        if (root != null && root.deviceLocked) {
            if (os != null) pass("Operating system", "${os.name}, by its published verified boot key.")
            else warn("Operating system", "Signed with a key this app does not recognise (${bootKey.take(16)}…). Compare it with the one GrapheneOS publishes for the device.")
        }
        val patch = enforced.osPatchLevel
        val age = patch?.let { PatchLevel.ageDays(it, LocalDate.ofInstant(now, ZoneOffset.UTC)) }
        when {
            patch == null || age == null -> warn("Security patch", "The record does not state the OS patch level.")
            age > MAX_PATCH_AGE_DAYS -> warn("Security patch", "${PatchLevel.format(patch)}, ${PatchLevel.describeAge(age)}: update the phone.")
            else -> pass("Security patch", PatchLevel.format(patch) + ".")
        }
        val name = os?.device ?: listOfNotNull(enforced.attestationIdBrand, enforced.attestationIdDevice).joinToString(" ").ifBlank { "Paired phone" }

        val leafKey = chain.leafKey
        val leafDer = chain.leafDer
        if (leafKey == null || leafDer == null) return failed()

        return when (response.kind) {
            PairingProtocol.Kind.PAIR -> {
                if (checks.any { it.status == Verdict.Status.FAIL }) return failed()
                val id = Pin.idOf(leafKey)
                val existing = pins.firstOrNull { it.id == id }
                val pin = Pin(id, leafKey, existing?.name ?: name, bootKey, existing?.pairedAt ?: now, now, patch, response.strongBox)
                pass("Paired", "This phone now remembers the other phone's hardware key; later checks prove it is the same phone.")
                Verdict(Verdict.Outcome.PAIRED, checks, pin)
            }
            PairingProtocol.Kind.AUDIT -> {
                val identity = response.identityKey
                val signature = response.signature
                val pin = identity?.let { key -> pins.firstOrNull { it.id == Pin.idOf(key) } }
                if (identity == null || signature == null || pin == null) {
                    fail("Same phone", "This verifier has no pairing with that phone. On the other phone, choose Pair again.")
                    return failed()
                }
                if (signatureCheck(identity, PairingProtocol.auditSignedData(challenge.challenge, leafDer), signature)) {
                    pass("Same phone", "Signed by the hardware key pinned when you paired it with this phone on ${day(pin.pairedAt)}.")
                } else {
                    fail("Same phone", "The answer is not signed by the hardware key pinned on ${day(pin.pairedAt)}: this is not the phone you paired, or its keys were reset.")
                }
                if (bootKey.isNotEmpty() && bootKey != pin.bootKey) {
                    fail("Same OS", "The verified boot key changed since pairing (${pin.bootKey.take(12)}… to ${bootKey.take(12)}…): a different operating system is installed.")
                } else {
                    pass("Same OS", "Same verified boot key as at pairing.")
                }
                if (patch != null && pin.osPatch != null && patch < pin.osPatch) {
                    fail("No downgrade", "The OS patch level went back from ${PatchLevel.format(pin.osPatch)} to ${PatchLevel.format(patch)} since the last check.")
                }
                if (checks.any { it.status == Verdict.Status.FAIL }) return failed()
                val updated = pin.copy(lastAuditAt = now, osPatch = maxOf(patch ?: 0, pin.osPatch ?: 0).takeIf { it > 0 }, strongBox = response.strongBox)
                Verdict(Verdict.Outcome.VERIFIED, checks, updated)
            }
        }
    }

    private fun day(at: Instant): String = LocalDate.ofInstant(at, ZoneOffset.UTC).toString()
}
