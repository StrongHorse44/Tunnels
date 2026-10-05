package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException

/** What an import added to the phone. [skippedSnapshots] were already there (same moment) and left alone. */
data class ImportSummary(
    val snapshots: Int,
    val observations: Int,
    val skippedSnapshots: Int,
    val settings: Int,
    val newPairedPhones: Int,
    val newNetworks: Int,
)

/** The file was read and verified, but writing it to the phone failed. The store is unchanged: the write is one transaction. */
class ImportCommitFailed(cause: Throwable) : Exception(cause.message, cause)

/** What became of the picked document after a failed export. */
enum class ExportCleanup {
    /** This export wrote to it (or it was the picker's new, empty file), and it was deleted. */
    DELETED,

    /** It should have been deleted, but deleting it failed. */
    NOT_DELETED,

    /** An existing file that this export never opened for writing: left as it was. */
    UNTOUCHED,
}

/**
 * What the export and import screens say. File problems use the container spec's section 8 messages; every import
 * failure says nothing was changed, which holds because nothing is written before the bundle has verified and the
 * write itself is one transaction. Messages never hold key material, the passphrase or entry contents.
 */
object TransferMessages {
    private const val UNCHANGED = "Nothing was changed."

    /** The apps of the registry (container spec, section 7), for "This is a X export". */
    fun appName(id: String): String = when (id) {
        "tunnels" -> "Tunnels"
        "lumen" -> "Lumen"
        "southbound" -> "Southbound"
        "mardigras" -> "Mardi Gras"
        "pusher" -> "Pusher"
        "pusher-server" -> "Pusher server backup"
        "prikey" -> "Prikey"
        else -> "another app's"
    }

    private fun article(name: String) = if (name.startsWith("another")) name else "a $name"

    /** [legacy]: the file was an old TSNAPE1 export, whose wrong-password case cannot tell damage from a wrong password. */
    fun importFailure(e: Throwable, legacy: Boolean = false): String = when (e) {
        is FwxException -> when (e.code) {
            FwxError.NOT_AN_EXPORT -> "This file is not an encrypted export. $UNCHANGED"
            FwxError.LEGACY -> "This is an old Tunnels export; it could not be read. $UNCHANGED"
            FwxError.UNSUPPORTED_VERSION -> "This export was made by a newer format version. Update the app. $UNCHANGED"
            FwxError.MALFORMED_HEADER -> "This export's header is damaged. $UNCHANGED"
            FwxError.UNSUPPORTED_KDF -> "This export uses a key method this app does not support. $UNCHANGED"
            FwxError.KDF_PARAMS -> "This export's key settings are unsafe or invalid. It was not opened. $UNCHANGED"
            FwxError.WRONG_APP -> {
                val other = appName(e.otherAppId.orEmpty())
                "This is ${article(other)} export, not a Tunnels export. $UNCHANGED"
            }
            FwxError.SCHEMA_TOO_NEW -> "Made by a newer version of Tunnels. Update first. $UNCHANGED"
            FwxError.SCHEMA_TOO_OLD -> "Made by a version too old to import. $UNCHANGED"
            FwxError.BAD_PASSPHRASE -> "That passphrase can't be used: ${e.detail}. $UNCHANGED"
            FwxError.WRONG_PASSPHRASE ->
                if (legacy) "Wrong password, or the file is damaged. $UNCHANGED"
                else "Wrong passphrase, or the file's header was altered. $UNCHANGED"
            FwxError.DAMAGED -> "The export is damaged or incomplete. $UNCHANGED"
            FwxError.MALFORMED_PAYLOAD -> "The export's contents are invalid. $UNCHANGED"
            FwxError.TOO_LARGE -> "This export is larger than Tunnels accepts. $UNCHANGED"
        }
        is ImportCommitFailed -> "Import failed while saving: ${reason(e.cause)}. $UNCHANGED"
        else -> "Couldn't read the file: ${reason(e)}. $UNCHANGED"
    }

    /** True for the one failure after which the same file is worth trying again with another passphrase. */
    fun isWrongPassphrase(e: Throwable): Boolean = e is FwxException && e.code == FwxError.WRONG_PASSPHRASE

    /** The failure is [ExportFailure] or its cause. [cleanup] says what became of the picked document. */
    fun exportFailure(e: Throwable, cleanup: ExportCleanup): String {
        val cause = (e as? ExportFailure)?.cause ?: e
        val reason = when {
            cause is FwxException && cause.code == FwxError.BAD_PASSPHRASE -> cause.detail
            cause is FwxException && cause.code == FwxError.TOO_LARGE -> "there is more data than an export can hold"
            cause is FwxException && cause.code == FwxError.MALFORMED_PAYLOAD -> "Tunnels couldn't import this data back (${cause.detail})"
            cause is FwxException -> "the export couldn't be written"
            cause is ReadBackMismatch -> "the file didn't read back the same"
            else -> reason(cause)
        }
        return when (cleanup) {
            ExportCleanup.DELETED -> "Export failed: $reason. The partial file was deleted."
            ExportCleanup.NOT_DELETED -> "Export failed: $reason. The partial file couldn't be deleted: delete it in your file manager."
            ExportCleanup.UNTOUCHED -> "Export failed: $reason. The file you picked wasn't written to."
        }
    }

    fun exported(counts: BundleCounts, size: String): String =
        "Exported ${counts.snapshots} snapshots with ${counts.observations} observations, ${counts.settings} settings, " +
            "${counts.pairingPins} paired phones and ${counts.networks} confirmed networks. File size: $size. " +
            "Anyone with this file and its passphrase can read it."

    fun imported(r: ImportSummary): String = buildString {
        append("Imported ${r.snapshots} snapshots with ${r.observations} observations")
        if (r.skippedSnapshots > 0) append(" (${r.skippedSnapshots} already here, skipped)")
        append(", ${r.settings} settings, ${r.newPairedPhones} new paired phones and ${r.newNetworks} new confirmed networks.")
        if (r.snapshots > 0) append(" The snapshots are pinned, so retention keeps them.")
    }

    /** A failure's own short reason ("No space left on device"), or its kind when it has none. */
    private fun reason(e: Throwable?): String =
        e?.message?.takeIf { it.isNotBlank() }?.trimEnd('.') ?: e?.javaClass?.simpleName ?: "unknown error"
}
