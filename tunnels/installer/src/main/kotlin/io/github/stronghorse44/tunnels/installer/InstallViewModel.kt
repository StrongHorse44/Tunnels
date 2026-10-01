package io.github.stronghorse44.tunnels.installer

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.install.FailureExplanation
import io.github.stronghorse44.tunnels.install.InstallFailure
import io.github.stronghorse44.tunnels.install.PreInstallChecks
import io.github.stronghorse44.tunnels.install.PreInstallReport
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.store.EventEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface InstallState {
    data object Idle : InstallState
    data class Working(val message: String) : InstallState
    data class Ready(val info: ApkInfo, val report: PreInstallReport, val pkg: StagedPackage) : InstallState
    data class Installing(val info: ApkInfo, val progress: Float, val awaitingUser: Boolean) : InstallState
    /** [failure] is null on success. */
    data class Done(val info: ApkInfo, val failure: FailureExplanation?, val rawMessage: String?) : InstallState
    data class Failed(val title: String, val detail: String) : InstallState
}

/**
 * Survives Android recreating the screen (low memory, or "Don't keep activities") while the user is in
 * Settings or the system install dialog: the staged file and in-flight session id live in [saved].
 */
class InstallViewModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val _canInstall = MutableStateFlow(app.packageManager.canRequestPackageInstalls())
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    /** System confirmation screen waiting to be shown. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    val history: StateFlow<List<EventEntity>> =
        flow { emitAll(TunnelsStore.get(app).events(TunnelCatalog.INSTALLER)) }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var staged: File? = null
    private var ownsStaged = false

    init {
        val path = saved.get<String>(KEY_PATH)
        val file = path?.let(::File)
        if (file != null && file.isFile && Staging.contains(app, file)) {
            staged = file
            ownsStaged = saved.get<Boolean>(KEY_OWNS) ?: false
            viewModelScope.launch {
                inspect(file)
                val sessionId = saved.get<Int>(KEY_SESSION)
                val ready = _state.value as? InstallState.Ready
                if (sessionId != null && ready != null) awaitSession(sessionId, ready)
            }
        }
    }

    private fun remember(file: File, owns: Boolean) {
        staged = file
        ownsStaged = owns
        saved[KEY_PATH] = file.absolutePath
        saved[KEY_OWNS] = owns
        saved.remove<Int>(KEY_SESSION)
    }

    fun refreshPermission() {
        _canInstall.value = app.packageManager.canRequestPackageInstalls()
        // Back from uninstalling the old copy: the comparison may have changed.
        if (_state.value is InstallState.Ready) staged?.let { file -> viewModelScope.launch { inspect(file) } }
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            discard()
            _state.value = InstallState.Working("Copying file…")
            val copied = runCatching { withContext(Dispatchers.IO) { Staging.copyIn(app, uri) } }.getOrElse {
                _state.value = InstallState.Failed("Can't open this file", it.message ?: "The file couldn't be read.")
                return@launch
            }
            remember(copied.file, owns = true)
            inspect(copied.file)
        }
    }

    /** A file another Tunnels screen already put in staging (e.g. an APK picked out of an archive). */
    fun openStaged(file: File) {
        viewModelScope.launch {
            discard()
            if (!file.isFile || !Staging.contains(app, file)) {
                _state.value = InstallState.Failed("File not found", "The staged file is gone. Open it again.")
                return@launch
            }
            remember(file, owns = false)
            inspect(file)
        }
    }

    private suspend fun inspect(file: File) {
        _state.value = InstallState.Working("Inspecting…")
        _state.value = runCatching {
            withContext(Dispatchers.IO) {
                val pkg = PackageStager.prepare(app, file)
                val info = ApkInspector.inspect(app, pkg.base, pkg.apks)
                InstallState.Ready(info, PreInstallChecks.evaluate(info.facts, info.installed, ApkInspector.device(app)), pkg)
            }
        }.getOrElse { InstallState.Failed("Can't read this package", it.message ?: "Unknown error") }
    }

    fun install() {
        val ready = _state.value as? InstallState.Ready ?: return
        viewModelScope.launch {
            _state.value = InstallState.Installing(ready.info, 0f, awaitingUser = false)
            val sessionId = try {
                SessionInstaller.install(app, ready.info.facts.packageName, ready.pkg.apks) { p ->
                    _state.value = InstallState.Installing(ready.info, p, awaitingUser = false)
                }
            } catch (e: Exception) {
                _state.value = InstallState.Done(ready.info, InstallFailure.explain(PackageInstaller.STATUS_FAILURE, e.message), e.message)
                return@launch
            }
            saved[KEY_SESSION] = sessionId
            awaitSession(sessionId, ready)
        }
    }

    /** Follows a committed session to its result. Updates are replayed, so none are missed before subscribing. */
    private suspend fun awaitSession(sessionId: Int, ready: InstallState.Ready) {
        _state.value = InstallState.Installing(ready.info, 1f, awaitingUser = false)
        val final = InstallEvents.updates.filter { it.sessionId == sessionId }.first { update ->
            if (update.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // After a recreate the dialog may already be on screen; don't open it twice.
                if (saved.get<Int>(KEY_CONFIRMED) != sessionId) _confirm.value = update.confirmIntent
                _state.value = InstallState.Installing(ready.info, 1f, awaitingUser = true)
                false
            } else true
        }
        saved.remove<Int>(KEY_SESSION)
        val success = final.status == PackageInstaller.STATUS_SUCCESS
        val failure = if (success) null else InstallFailure.explain(final.status, final.message)
        _state.value = InstallState.Done(ready.info, failure, final.message)
        record(ready.info, failure)
    }

    fun confirmShown() {
        _confirm.value = null
        saved.get<Int>(KEY_SESSION)?.let { saved[KEY_CONFIRMED] = it }
    }

    fun reset() {
        discard()
        _state.value = InstallState.Idle
    }

    private suspend fun record(info: ApkInfo, failure: FailureExplanation?) = withContext(Dispatchers.IO) {
        val version = "${info.facts.versionName ?: "?"} (${info.facts.versionCode})"
        runCatching {
            TunnelsStore.get(app).recordEvent(
                tunnelId = TunnelCatalog.INSTALLER,
                kind = if (failure == null) "INSTALLED" else "FAILED",
                subject = info.facts.packageName,
                summary = if (failure == null) "${info.label} $version" else "${info.label} $version: ${failure.title}",
            )
        }
    }

    /** Deletes our staged copy. Called when the screen is really closing, not when it's merely recreated. */
    fun discard() {
        if (ownsStaged) Staging.discard(app, staged)
        staged = null
        ownsStaged = false
        listOf(KEY_PATH, KEY_OWNS, KEY_SESSION, KEY_CONFIRMED).forEach { saved.remove<Any>(it) }
    }

    private companion object {
        const val KEY_PATH = "staged_path"
        const val KEY_OWNS = "owns_staged"
        const val KEY_SESSION = "session_id"
        const val KEY_CONFIRMED = "confirm_shown_for"
    }
}
