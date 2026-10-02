package io.github.stronghorse44.tunnels.silicon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.attestation.ChainVerification
import io.github.stronghorse44.tunnels.attestation.DerException
import io.github.stronghorse44.tunnels.attestation.GoogleRoots
import io.github.stronghorse44.tunnels.attestation.KeyAttestation
import io.github.stronghorse44.tunnels.attestation.KnownBootKeys
import io.github.stronghorse44.tunnels.attestation.PatchLevel
import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.attestation.SiliconRules
import io.github.stronghorse44.tunnels.attestation.toHex
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Silicon: what the hardware says about this phone. A fresh attested key is generated, its certificate's
 * attestation record is parsed for the verified boot state, bootloader lock, boot key and patch levels, the
 * chain is checked against Google's attestation roots, and the key is deleted. Everything is a summary; the
 * challenge and the certificates themselves are never stored.
 */
class SiliconTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = SiliconKeys.TUNNEL_ID

    /** Key attestation needs no runtime permission. */
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = SiliconRules.all

    /** The patch age grows by a day every day: on its own it is no reason for a background check to store a snapshot. */
    override val volatileKeys: Set<String> = setOf(SiliconKeys.OS_PATCH_AGE_DAYS)

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val obs = ArrayList<Observation>(32)
        fun add(key: String, value: String) = obs.add(Observation(id, SiliconKeys.SUBJECT, key, value))

        progress.report(0, STEPS, "device")
        add(SiliconKeys.DEVICE_BRAND, Build.BRAND.orEmpty().lowercase().ifEmpty { "unknown" })
        add(SiliconKeys.DEVICE_MODEL, Build.MODEL.orEmpty().ifEmpty { "unknown" })
        add(SiliconKeys.DEVICE_SECURITY_PATCH, Build.VERSION.SECURITY_PATCH.orEmpty().ifEmpty { "unknown" })
        add(SiliconKeys.OS_RELEASE, Build.VERSION.RELEASE.orEmpty().ifEmpty { "unknown" })

        progress.report(1, STEPS, "generating attested key")
        val result = try {
            Attestor.attest()
        } catch (e: Exception) {
            add(SiliconKeys.ATTESTATION_ERROR, describe(e))
            progress.report(STEPS, STEPS, "done")
            return obs
        }

        progress.report(2, STEPS, "reading attestation record")
        add(SiliconKeys.STRONGBOX, result.strongBox.toString())
        add(SiliconKeys.CHAIN_LENGTH, result.chain.size.toString())
        try {
            val leaf = result.chain.first()
            val extension = leaf.getExtensionValue(KeyAttestation.OID)
                ?: throw DerException("the key's certificate carries no attestation record")
            val record = KeyAttestation.parse(extension)
            add(SiliconKeys.ATTESTATION_VERSION, record.attestationVersion.toString())
            add(SiliconKeys.ATTESTATION_LEVEL, record.attestationSecurityLevel?.label ?: "level ${record.attestationSecurityLevelCode}")
            add(SiliconKeys.KEYMASTER_VERSION, record.keymasterVersion.toString())
            add(SiliconKeys.KEYMASTER_LEVEL, record.keymasterSecurityLevel?.label ?: "level ${record.keymasterSecurityLevelCode}")
            add(SiliconKeys.ATTESTATION_CHALLENGE, if (record.attestationChallenge.contentEquals(result.challenge)) "matched" else "mismatch")

            val enforced = record.enforced()
            enforced.rootOfTrust?.let { rot ->
                add(SiliconKeys.BOOT_STATE, rot.verifiedBootState?.label ?: "state ${rot.verifiedBootStateCode}")
                add(SiliconKeys.BOOT_LOCKED, rot.deviceLocked.toString())
                add(SiliconKeys.BOOT_KEY_HASH, KnownBootKeys.fingerprint(rot.verifiedBootKey))
                add(SiliconKeys.BOOT_KEY_NAME, KnownBootKeys.nameOf(rot.verifiedBootKey))
                add(SiliconKeys.BOOT_HASH, rot.verifiedBootHash?.toHex()?.take(BOOT_HASH_CHARS)?.ifEmpty { "none" } ?: "none")
            }
            enforced.osVersion?.let { add(SiliconKeys.OS_VERSION, it.toString()) }
            enforced.osPatchLevel?.let { level ->
                add(SiliconKeys.OS_PATCH, level.toString())
                PatchLevel.ageDays(level, LocalDate.now())?.let { add(SiliconKeys.OS_PATCH_AGE_DAYS, it.toString()) }
            }
            enforced.vendorPatchLevel?.let { add(SiliconKeys.VENDOR_PATCH, it.toString()) }
            enforced.bootPatchLevel?.let { add(SiliconKeys.BOOT_PATCH, it.toString()) }
            enforced.attestationIdBrand?.takeIf { it.isNotBlank() }?.let { add(SiliconKeys.ATTESTED_BRAND, it) }
            enforced.attestationIdDevice?.takeIf { it.isNotBlank() }?.let { add(SiliconKeys.ATTESTED_DEVICE, it) }
            enforced.attestationIdProduct?.takeIf { it.isNotBlank() }?.let { add(SiliconKeys.ATTESTED_PRODUCT, it) }
        } catch (e: Exception) {
            add(SiliconKeys.ATTESTATION_ERROR, describe(e))
        }

        progress.report(3, STEPS, "checking certificate chain")
        val verification = try {
            GoogleRoots.verify(result.chain)
        } catch (e: Exception) {
            ChainVerification.Unverified(describe(e))
        }
        add(SiliconKeys.CHAIN_VERIFIED, verification.value)
        if (verification is ChainVerification.Verified) add(SiliconKeys.CHAIN_ROOT, verification.root.name)

        progress.report(STEPS, STEPS, "done")
        return obs
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val update = FindingAction.OpenSettings(ACTION_SYSTEM_UPDATE, "System update")
        val security = FindingAction.OpenSettings(Settings.ACTION_SECURITY_SETTINGS, "Security settings")
        return when (draft.kind) {
            SiliconRules.BOOT_KEY_UNKNOWN -> buildList<FindingAction> {
                SiliconKeys.keyHashIn(draft.evidence)?.let { hash -> add(FindingAction.Perform("Copy key hash") { copyToClipboard(hash) }) }
                add(security)
                add(update)
            }
            SiliconRules.PATCH_OLD, SiliconRules.NO_STRONGBOX -> listOf(update, security)
            else -> listOf(security, update)
        }
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        VerifiedBootCard(state)
    }

    private suspend fun copyToClipboard(hash: String): String = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@withContext "No clipboard available"
        clipboard.setPrimaryClip(ClipData.newPlainText("Verified boot key", hash))
        "Copied"
    }

    private fun describe(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_CHARS)
        return if (message == null) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
    }

    companion object {
        const val ACTION_SYSTEM_UPDATE = "android.settings.SYSTEM_UPDATE_SETTINGS"
        private const val STEPS = 4
        private const val BOOT_HASH_CHARS = 16
        private const val MAX_ERROR_CHARS = 160
    }
}
