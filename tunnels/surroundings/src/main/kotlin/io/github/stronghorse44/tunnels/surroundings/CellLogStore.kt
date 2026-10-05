package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import android.util.Log
import io.github.stronghorse44.tunnels.ble.CellJudgement
import io.github.stronghorse44.tunnels.ble.CellLog
import io.github.stronghorse44.tunnels.ble.CellLogText
import io.github.stronghorse44.tunnels.ble.CellLogbook
import io.github.stronghorse44.tunnels.ble.ServingCell
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The cell logbook's row in the encrypted settings table (CLAUDE.md rule 3, hashed-set exception). No row means the
 * logbook is off: it learns nothing until the user starts it. The row holds only keyed hashes, counts and ranks
 * ([CellLogbook]); a scan, "Normal here" and "Clear" each change it in one atomic transaction, so none loses another's
 * change. A row that cannot be read is never overwritten (only Clear replaces it).
 *
 * Nothing here logs a [ServingCell], a token, a place hash or the row: failures log the exception class name only.
 */
class CellLogStore(private val context: Context) {
    private val store: TunnelsStore get() = TunnelsStore.get(context)

    /** The row as the panel needs to show it: off, unreadable, or on with its place count. A change in the table updates it. */
    fun rowFlow(): Flow<CellLogText.Row> = flow { emitAll(store.settingFlow(KEY).map { CellLogText.row(it) }) }.flowOn(Dispatchers.IO)

    /** Whether the row exists. Throws if the store cannot be read: the caller decides what that means. */
    suspend fun isOn(): Boolean = withContext(Dispatchers.IO) { store.setting(KEY) != null }

    /**
     * Judges one scan against the row. Returns the `log:state` and the judgement (null when nothing was judged):
     * `off` (no row; nothing written), `unreadable` (row left as it is), `restarted` or `on` (judged and written), or
     * `failed` (the keystore or the write failed; when the judgement was already made it is still returned, so an
     * unfamiliar tower is never swallowed by a failed write).
     */
    suspend fun judge(block: List<String>, serving: List<ServingCell>, previousRank: Int): Pair<String, CellJudgement?> = withContext(Dispatchers.IO) {
        var state = SurroundingsKeys.LOG_FAILED
        var judgement: CellJudgement? = null
        try {
            val tokens = CellLog.tokensOf(serving) { SurroundingsKey.hmac(it) }
            if (tokens.isEmpty()) return@withContext SurroundingsKeys.LOG_NO_CELL_ID to null
            val keyId = SurroundingsKey.keyId()
            store.updateSetting(KEY) { raw ->
                if (raw == null) {
                    state = SurroundingsKeys.LOG_OFF
                    return@updateSetting null
                }
                val book = CellLogbook.decode(raw)
                if (book == null) {
                    state = SurroundingsKeys.LOG_UNREADABLE
                    return@updateSetting raw
                }
                val (next, j) = CellLog.observe(book, keyId, block, tokens, previousRank)
                val text = next.encode()
                // Never write a row this code cannot read back: it would be unreadable, and never overwritten.
                check(CellLogbook.decode(text) != null) { "row does not read back" }
                judgement = j
                state = if (j.restarted) SurroundingsKeys.LOG_RESTARTED else SurroundingsKeys.LOG_ON
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cell logbook failed: ${e.javaClass.simpleName}")
            // A judgement made before the write failed is still reported.
            return@withContext SurroundingsKeys.LOG_FAILED to judgement
        }
        state to judgement
    }

    /** Turns the logbook on: writes an empty book when there is no row. An existing row, readable or not, is left as it is. */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        try {
            val keyId = SurroundingsKey.keyId()
            store.updateSetting(KEY) { raw -> raw ?: CellLogbook(keyId).encode() }
            "The cell logbook is on. Scan to start learning."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cell logbook start failed: ${e.javaClass.simpleName}")
            "Could not start the cell logbook: ${e.javaClass.simpleName}"
        }
    }

    /** Deletes the row, which turns the logbook off. Works on an unreadable row too. */
    suspend fun clear(): String = withContext(Dispatchers.IO) {
        try {
            store.updateSetting(KEY) { null }
            "The cell logbook is cleared and off."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cell logbook clear failed: ${e.javaClass.simpleName}")
            "Could not clear the cell logbook: ${e.javaClass.simpleName}"
        }
    }

    /**
     * "Normal here": moves the held tower [towerId] into its place's sets and dismisses its finding. A tower no longer
     * held learns nothing (the finding is dismissed all the same).
     */
    suspend fun accept(towerId: String): String = withContext(Dispatchers.IO) {
        var message = MESSAGE_NOT_HELD
        try {
            store.updateSetting(KEY) { raw ->
                if (raw == null) {
                    message = "The cell logbook is off."
                    return@updateSetting null
                }
                val book = CellLogbook.decode(raw)
                if (book == null) {
                    message = CellLogText.UNREADABLE
                    return@updateSetting raw
                }
                val next = CellLog.accept(book, towerId)
                if (next == null) {
                    message = MESSAGE_NOT_HELD
                    return@updateSetting raw
                }
                val text = next.encode()
                check(CellLogbook.decode(text) != null) { "row does not read back" }
                message = "Remembered this tower here."
                text
            }
            if (message != CellLogText.UNREADABLE) {
                store.dao.dismissFinding(Finding.findingId(SurroundingsKeys.TUNNEL_ID, SurroundingsKeys.towerSubject(towerId), SurroundingsRules.UNFAMILIAR_TOWER))
            }
            message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cell logbook accept failed: ${e.javaClass.simpleName}")
            "Could not save that: ${e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TAG = "SurroundingsCellLog"

        /** The settings-table key of the row. Not exported: `BundleSettings.KEYS` does not list it. */
        const val KEY = "surroundings.cell_logbook"
        const val MESSAGE_NOT_HELD = "Nothing to remember: this tower is no longer waiting."
    }
}
