package io.github.stronghorse44.tunnels.updater

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.install.InstallFailure
import io.github.stronghorse44.tunnels.installer.ApkInspector
import io.github.stronghorse44.tunnels.installer.InstallEvents
import io.github.stronghorse44.tunnels.installer.SessionInstaller
import io.github.stronghorse44.tunnels.updates.Channel
import io.github.stronghorse44.tunnels.updates.MiniJson
import io.github.stronghorse44.tunnels.updates.TokenFormat
import io.github.stronghorse44.tunnels.updates.Update
import io.github.stronghorse44.tunnels.updates.UpdateCheck
import io.github.stronghorse44.tunnels.updates.UpdateErrors
import io.github.stronghorse44.tunnels.updates.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Nothing newer; [latest] names the newest build of the channel when GitHub listed one. */
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val update: Update) : UpdateState
    data class Downloading(val update: Update, val done: Long, val total: Long) : UpdateState
    data class Verifying(val update: Update) : UpdateState
    /** Downloaded and verified: [checks] are the lines the screen shows before the install button. */
    data class Ready(val update: Update, val file: File, val checks: List<String>) : UpdateState
    data class Installing(val update: Update, val awaitingUser: Boolean) : UpdateState
    data class Done(val message: String) : UpdateState
    data class Failed(val title: String, val detail: String, val tokenProblem: Boolean = false) : UpdateState
}

/** This app as installed: what an update must be newer than and signed like. */
data class Installed(val packageName: String, val versionName: String, val versionCode: Long, val channel: Channel)

/**
 * Check, download, verify, install. Every network step starts from a tap; nothing runs in the background and
 * nothing is scheduled. The APK lands in the private staging area, is checked against the installed app
 * (package, version, signing key, published checksum) and goes to the system installer like any other APK.
 */
class UpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    val installed: Installed = run {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        Installed(app.packageName, info.versionName ?: "?", info.longVersionCode, Updates.channelOf(app.packageName))
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** "github_pat_…x9Qa" when a token is saved. */
    private val _tokenHint = MutableStateFlow<String?>(null)
    val tokenHint: StateFlow<String?> = _tokenHint.asStateFlow()

    private val _networkAllowed = MutableStateFlow(networkAllowed())
    val networkAllowed: StateFlow<Boolean> = _networkAllowed.asStateFlow()

    private val _canInstall = MutableStateFlow(app.packageManager.canRequestPackageInstalls())
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    /** The system's confirmation screen, waiting to be shown. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    private var token: String? = null
    private var work: Job? = null
    @Volatile private var cancelled = false

    init {
        viewModelScope.launch {
            token = runCatching { UpdateToken.read(app) }.getOrNull()
            _tokenHint.value = token?.let(TokenFormat::hint)
        }
    }

    /** Re-read on resume: the Network toggle and "install unknown apps" change in Settings. */
    fun refresh() {
        _networkAllowed.value = networkAllowed()
        _canInstall.value = app.packageManager.canRequestPackageInstalls()
    }

    /** GrapheneOS's Network toggle revokes INTERNET, which is what this reads. */
    private fun networkAllowed(): Boolean = app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /** Saves a pasted token; false when it is not one word of a plausible length. */
    fun saveToken(input: String): Boolean {
        val clean = TokenFormat.clean(input) ?: return false
        token = clean
        _tokenHint.value = TokenFormat.hint(clean)
        viewModelScope.launch { runCatching { UpdateToken.save(app, clean) } }
        return true
    }

    fun forgetToken() {
        token = null
        _tokenHint.value = null
        viewModelScope.launch { runCatching { UpdateToken.clear(app) } }
    }

    fun check() {
        if (work?.isActive == true) return
        refresh()
        work = viewModelScope.launch {
            _state.value = UpdateState.Checking
            val sent = token
            _state.value = try {
                val json = withContext(Dispatchers.IO) { GitHubClient(sent).releasesJson() }
                val releases = withContext(Dispatchers.Default) { Updates.parseReleases(json) }
                // A token that worked starts its 30 days again, unless another was saved or forgotten meanwhile.
                if (sent != null && sent == token) runCatching { UpdateToken.save(app, sent) }
                Updates.newest(releases, installed.channel, installed.versionCode)?.let { UpdateState.Available(it) }
                    ?: UpdateState.UpToDate(Updates.latest(releases, installed.channel)?.label)
            } catch (e: GitHubClient.HttpException) {
                UpdateState.Failed("GitHub said no", UpdateErrors.forHttp(e.code, sent != null), tokenProblem = e.code in setOf(401, 403, 404))
            } catch (e: MiniJson.JsonException) {
                UpdateState.Failed("Unexpected answer", "GitHub's release list could not be read: ${e.message}.")
            } catch (e: IOException) {
                UpdateState.Failed("No connection", UpdateErrors.forNoConnection(networkAllowed()))
            }
        }
    }

    fun download(update: Update) {
        if (work?.isActive == true) return
        cancelled = false
        work = viewModelScope.launch {
            val dir = Staging.newDir(app)
            val file = File(dir, update.apk.name)
            _state.value = UpdateState.Downloading(update, 0, update.apk.size)
            val sent = token
            try {
                val client = GitHubClient(sent)
                val sha = withContext(Dispatchers.IO) {
                    client.download(update.apk.id, file, update.apk.size, onProgress = { done, total ->
                        _state.value = UpdateState.Downloading(update, done, total)
                    }, cancelled = { cancelled })
                }
                val expected = update.checksum?.let { asset ->
                    withContext(Dispatchers.IO) { Updates.checksumFor(client.text(asset.id), update.apk.name) }
                        ?: throw IOException("The release's checksum file does not name ${update.apk.name}.")
                }
                _state.value = UpdateState.Verifying(update)
                _state.value = withContext(Dispatchers.IO) { verify(update, file, expected, sha) }
                if (_state.value !is UpdateState.Ready) Staging.discard(app, file)
            } catch (e: InterruptedIOException) {
                Staging.discard(app, file)
                _state.value = if (cancelled) UpdateState.Available(update) else UpdateState.Failed("Download failed", UpdateErrors.forNoConnection(networkAllowed()))
            } catch (e: GitHubClient.HttpException) {
                Staging.discard(app, file)
                _state.value = UpdateState.Failed("GitHub said no", UpdateErrors.forHttp(e.code, sent != null), tokenProblem = e.code in setOf(401, 403, 404))
            } catch (e: IOException) {
                Staging.discard(app, file)
                _state.value = UpdateState.Failed("Download failed", e.message ?: UpdateErrors.forNoConnection(networkAllowed()))
            }
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    private fun verify(update: Update, file: File, expected: String?, actual: String): UpdateState {
        val info = runCatching { ApkInspector.inspect(app, file, listOf(file)) }.getOrNull()
        val current = ApkInspector.installed(app, app.packageName)
        val verdict = UpdateCheck.verify(
            packageName = installed.packageName,
            installedVersionCode = installed.versionCode,
            installedSigners = current?.signerSha256.orEmpty(),
            apkPackage = info?.facts?.packageName,
            apkVersionCode = info?.facts?.versionCode ?: -1,
            apkSigners = info?.facts?.signerSha256.orEmpty(),
            apkHistory = info?.facts?.lineageSha256?.toList().orEmpty(),
            expectedSha256 = expected,
            actualSha256 = actual,
        )
        return when (verdict) {
            is UpdateCheck.Verdict.Refused -> UpdateState.Failed("Not installed", verdict.reason)
            UpdateCheck.Verdict.Ok -> UpdateState.Ready(
                update, file,
                listOfNotNull(
                    "Package: ${installed.packageName}",
                    "Version: ${info?.facts?.versionName ?: "?"} (${info?.facts?.versionCode}), installed ${installed.versionName} (${installed.versionCode})",
                    "Signed with the same key as the installed Tunnels",
                    if (expected != null) "Matches the SHA-256 published with the release" else "SHA-256 ${actual.take(16)}… (the release publishes no checksum)",
                ),
            )
        }
    }

    fun install(ready: UpdateState.Ready) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            _state.value = UpdateState.Installing(ready.update, awaitingUser = false)
            val sessionId = try {
                SessionInstaller.install(app, installed.packageName, listOf(ready.file)) { }
            } catch (e: Exception) {
                val failure = InstallFailure.explain(PackageInstaller.STATUS_FAILURE, e.message)
                _state.value = UpdateState.Failed(failure.title, failure.detail)
                return@launch
            } finally {
                // The session holds its own copy once written.
                Staging.discard(app, ready.file)
            }
            val final = InstallEvents.updates.filter { it.sessionId == sessionId }.first { update ->
                if (update.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    _confirm.value = update.confirmIntent
                    _state.value = UpdateState.Installing(ready.update, awaitingUser = true)
                    false
                } else true
            }
            // On success Android usually restarts Tunnels before this line runs; if not, say so.
            _state.value = if (final.status == PackageInstaller.STATUS_SUCCESS) {
                UpdateState.Done("Updated to ${ready.update.label}. Close Tunnels and open it again if it did not restart.")
            } else {
                val failure = InstallFailure.explain(final.status, final.message)
                UpdateState.Failed(failure.title, failure.detail)
            }
        }
    }

    fun confirmShown() {
        _confirm.value = null
    }

    fun reset() {
        (state.value as? UpdateState.Ready)?.let { Staging.discard(app, it.file) }
        _state.value = UpdateState.Idle
    }

    override fun onCleared() {
        cancelled = true
        (state.value as? UpdateState.Ready)?.let { Staging.discard(app, it.file) }
        super.onCleared()
    }
}
