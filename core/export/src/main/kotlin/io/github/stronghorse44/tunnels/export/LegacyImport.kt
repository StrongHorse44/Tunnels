package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads an export from before the FWX container (TSNAPE1, container spec section 11). It carries snapshots only, so the
 * result has no settings, pins or networks and an import of it touches nothing else. The old reader and its limits
 * are unchanged: 310 000 PBKDF2 iterations, the whole file in memory, at most [MAX_FILE_BYTES]. Failures are
 * [FwxException]s like the new reader's so one set of messages serves both.
 */
object LegacyImport {
    /** A full 12-snapshot export of a 300-app phone is ~10-15 MB; anything past this is not a Tunnels export. */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    fun read(sealed: ByteArray, password: CharArray): TunnelsData {
        val plain = try {
            EncryptedFile.open(sealed, password)
        } catch (e: NotASealedFile) {
            throw FwxException(FwxError.NOT_AN_EXPORT, "not a TSNAPE1 file")
        } catch (e: WrongPasswordOrCorrupt) {
            throw FwxException(FwxError.WRONG_PASSPHRASE, "the legacy file did not decrypt")
        }
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val bundle = BundleFormat.parse(InputStreamReader(plain.inputStream(), decoder), ParseLimits.IMPORT)
            TunnelsBundle.checkDates(bundle, System.currentTimeMillis())
            return TunnelsData(snapshots = bundle)
        } catch (e: BundleFormatException) {
            throw FwxException(FwxError.MALFORMED_PAYLOAD, e.message ?: "invalid bundle")
        } catch (e: CharacterCodingException) {
            throw FwxException(FwxError.MALFORMED_PAYLOAD, "not valid UTF-8")
        } finally {
            plain.fill(0)
        }
    }
}
