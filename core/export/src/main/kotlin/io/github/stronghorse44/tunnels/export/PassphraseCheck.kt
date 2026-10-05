package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import io.github.stronghorse44.tunnels.export.fwx.FwxKdf

/** The passphrase rules for a new export (container spec, section 3.1), asked before anything is picked or written. */
object PassphraseCheck {
    /** Why [passphrase] cannot protect a new export, or null when it can. The codec applies the same rule when it writes. */
    fun problem(passphrase: CharArray): String? = try {
        FwxKdf.passphraseBytes(passphrase, forWriter = true).fill(0)
        null
    } catch (e: FwxException) {
        if (e.code == FwxError.BAD_PASSPHRASE) e.detail else throw e
    }
}
