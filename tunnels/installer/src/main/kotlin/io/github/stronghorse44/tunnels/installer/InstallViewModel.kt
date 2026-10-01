package io.github.stronghorse44.tunnels.installer

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
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

class InstallViewModel(private val app: Application) : AndroidViewModel(app) {
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var staged: File? = null
    private var ownsStaged = false

    fun refreshPermission() {
        _canInstall.value = app.packageManager.canRequestPackageInstalls()
        // Back from uninstalling the old copy: the comparison may have changed.
        if (_state.value is InstallState.Ready) staged?.let { file -> viewModelScope.launch { inspect(file) } }
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            release()
            _state.value = InstallState.Working("Copying file…")
            val copied = runCatching { withContext(Dispatchers.IO) { Staging.copyIn(app, uri) } }.getOrElse {
                _state.value = InstallState.Failed("Can't open this file", it.message ?: "The file couldn't be read.")
                return@launch
            }
            staged = copied.file
            ownsStaged = true
            inspect(copied.file)
        }
    }

    /** A file another Tunnels screen already put in staging (e.g. an APK picked out of an archive). */
    fun openStaged(file: File) {
        viewModelScope.launch {
            release()
            if (!file.isFile || !Staging.contains(app, file)) {
                _state.value = InstallState.Failed("File not found", "The staged file is gone. Open it again.")
                return@launch
            }
            staged = file
            ownsStaged = false
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
            // Updates are replayed, so nothing is lost between commit and this subscription.
            val final = InstallEvents.updates.filter { it.sessionId == sessionId }.first { update ->
                if (update.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    _confirm.value = update.confirmIntent
                    _state.value = InstallState.Installing(ready.info, 1f, awaitingUser = true)
                    false
                } else true
            }
            val success = final.status == PackageInstaller.STATUS_SUCCESS
            val failure = if (success) null else InstallFailure.explain(final.status, final.message)
            _state.value = InstallState.Done(ready.info, failure, final.message)
            record(ready.info, failure)
        }
    }

    fun confirmShown() {
        _confirm.value = null
    }

    fun reset() {
        release()
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

    private fun release() {
        if (ownsStaged) Staging.discard(app, staged)
        staged = null
        ownsStaged = false
    }

    override fun onCleared() = release()
}
