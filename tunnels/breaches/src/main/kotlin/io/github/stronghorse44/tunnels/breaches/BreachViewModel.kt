package io.github.stronghorse44.tunnels.breaches

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.temporal.ChronoUnit

sealed interface BreachState {
    data object Idle : BreachState
    data object Fetching : BreachState

    /** The list is held in memory: [count] breaches, [skipped] entries Tunnels could not represent. */
    data class Held(val count: Int, val skipped: Int, val fetched: String, val attribution: String, val bytes: Int, val expiresAtMs: Long, val servesLeft: Int) : BreachState

    /** The list was held and is gone (ten minutes passed, or it was handed over three times). */
    data object Gone : BreachState

    data class Failed(val title: String, val detail: String) : BreachState
}

/**
 * Fetch, reduce, hold, hand over. The fetch starts from a tap and nothing runs in the background or on a schedule.
 * What is fetched is reduced to the catalogue file in memory ([BreachHolder.shared]) and never written anywhere;
 * the only thing stored is a one-line summary of the last fetch in the encrypted settings.
 */
class BreachViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<BreachState>(BreachState.Idle)
    val state: StateFlow<BreachState> = _state.asStateFlow()

    private val _networkAllowed = MutableStateFlow(networkAllowed())
    val networkAllowed: StateFlow<Boolean> = _networkAllowed.asStateFlow()

    private val _linx = MutableStateFlow(BreachShare.linxInstalled(app))
    val linxInstalled: StateFlow<Boolean> = _linx.asStateFlow()

    /** Set when the last hand-over failed, for the screen to say so. */
    private val _sendProblem = MutableStateFlow<String?>(null)
    val sendProblem: StateFlow<String?> = _sendProblem.asStateFlow()

    private var work: Job? = null
    @Volatile private var cancelled = false

    /** Re-read on resume: the Network toggle changes in Settings, Linx may be installed meanwhile, the list may have expired. */
    fun refresh() {
        _networkAllowed.value = networkAllowed()
        _linx.value = BreachShare.linxInstalled(app)
        val held = BreachHolder.shared.info()
        val s = _state.value
        if (s is BreachState.Held) {
            _state.value = if (held == null) BreachState.Gone else s.copy(expiresAtMs = held.expiresAtMs, servesLeft = held.servesLeft)
        }
    }

    /** GrapheneOS's Network toggle revokes INTERNET, which is what this reads. */
    private fun networkAllowed(): Boolean = app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun fetch() {
        if (work?.isActive == true) return
        refresh()
        cancelled = false
        _sendProblem.value = null
        work = viewModelScope.launch {
            _state.value = BreachState.Fetching
            _state.value = try {
                val fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val download = withContext(Dispatchers.IO) { BreachClient().fetch { cancelled } }
                val parsed = withContext(Dispatchers.Default) { HibpCatalogue.parse(download.bytes, fetchedAt) }
                val fetched = fetchedAt.toString()
                val file = withContext(Dispatchers.Default) {
                    CatalogueFile.write(parsed.rows, CatalogueMeta(Catalogue.ATTRIBUTION, fetched, download.sha256, parsed.skipped))
                }
                val token = BreachHolder.shared.put(file, ShareUri.displayName(fetched))
                val info = checkNotNull(BreachHolder.shared.describe(token))
                runCatching {
                    TunnelsStore.get(app).putSetting(LAST_FETCH_KEY, "$fetched;count=${parsed.rows.size}")
                }
                BreachState.Held(parsed.rows.size, parsed.skipped, fetched, Catalogue.ATTRIBUTION, info.size, info.expiresAtMs, info.servesLeft)
            } catch (e: BreachClient.Refused) {
                when (e.reason) {
                    BreachClient.Reason.REDIRECT -> BreachState.Failed("Redirect refused", BreachMessages.REDIRECT)
                    BreachClient.Reason.STATUS -> BreachState.Failed("The service said no", BreachMessages.forHttp(e.code))
                    BreachClient.Reason.CONTENT_TYPE -> BreachState.Failed("Unexpected answer", BreachMessages.WRONG_TYPE)
                    BreachClient.Reason.TOO_LARGE -> BreachState.Failed("Answer too large", BreachMessages.tooLarge(BreachClient.MAX_BYTES))
                }
            } catch (e: CatalogueException) {
                BreachState.Failed("Unexpected answer", "The list could not be used: ${e.message}")
            } catch (e: InterruptedIOException) {
                if (cancelled) BreachState.Idle else BreachState.Failed("No connection", BreachMessages.noConnection(networkAllowed()))
            } catch (e: IOException) {
                BreachState.Failed("No connection", BreachMessages.noConnection(networkAllowed()))
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    /** The send intent for the held list, or null when nothing is held any more. */
    fun sendIntent(): Intent? {
        refresh()
        val info = BreachHolder.shared.info() ?: return null
        _sendProblem.value = null
        return BreachShare.sendIntent(BreachShare.uri(app, info.token))
    }

    fun sendFailed() {
        _sendProblem.value = "Linx did not take the list. Open Linx once (update it if it is old) and send again."
    }

    /** After a hand-over the counters changed: show them. */
    fun sent() = refresh()

    fun forgetHeld() {
        BreachHolder.shared.clear()
        _state.value = BreachState.Idle
    }

    override fun onCleared() {
        cancelled = true
    }

    companion object {
        /** Encrypted settings row: `<time>;count=<n>`, a summary of the last fetch (rule 3). Not exported. */
        const val LAST_FETCH_KEY = "breaches.last_fetch"
    }
}
