package io.github.stronghorse44.tunnels.snapshots

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.export.BundleFormat
import io.github.stronghorse44.tunnels.export.BundleFormatException
import io.github.stronghorse44.tunnels.export.EncryptedFile
import io.github.stronghorse44.tunnels.export.NotASealedFile
import io.github.stronghorse44.tunnels.export.WrongPasswordOrCorrupt
import io.github.stronghorse44.tunnels.runtime.AppLock
import io.github.stronghorse44.tunnels.runtime.ScanResult
import io.github.stronghorse44.tunnels.runtime.ScanState
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsDao
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** One snapshot in the history list. */
data class SnapshotRow(
    val id: Long,
    val takenAt: Long,
    val pinned: Boolean,
    val tunnelIds: List<String>,
    val observations: Int,
)

/** Two selected snapshots, older ([fromId]) to newer ([toId]), and what changed between them. */
data class DiffView(
    val fromId: Long,
    val toId: Long,
    val fromAt: Long,
    val toAt: Long,
    val report: DiffReport,
)

data class SnapshotsState(
    /** False until the store has answered once. */
    val ready: Boolean = false,
    val tunnelCount: Int = 0,
    val snapshots: List<SnapshotRow> = emptyList(),
    val scan: ScanState = ScanState(),
    val lastResult: ScanResult? = null,
    /** Up to two snapshot ids, in tap order. */
    val selected: List<Long> = emptyList(),
    val diff: DiffView? = null,
    /** "Exporting…" / "Importing…" while a transfer runs. */
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** A destination was picked but the password did not survive a process restart: ask again. */
    val exportNeedsPassword: Boolean = false,
    /** A file was picked for import and waits for its password. */
    val importPending: Boolean = false,
    val importError: String? = null,
    val appLockEnabled: Boolean = false,
    val canLock: Boolean = false,
    val keyLevel: String = "",
)

/**
 * Store work runs on Dispatchers.IO. Selection and in-flight transfer targets live in [saved] so the screen
 * survives being recreated behind the system file picker; passwords never do.
 */
class SnapshotsViewModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        SnapshotsState(
            selected = saved.get<LongArray>(KEY_SELECTED)?.toList().orEmpty(),
            exportNeedsPassword = saved.get<String>(KEY_EXPORT_URI) != null,
            importPending = saved.get<String>(KEY_IMPORT_URI) != null,
            appLockEnabled = AppLock.isEnabled(app),
            canLock = AppLock.canLock(app),
        ),
    )
    val state: StateFlow<SnapshotsState> = _state.asStateFlow()

    /** Bumped after a scan or import so observation counts are re-read (Room only watches the snapshots table). */
    private val refresh = MutableStateFlow(0)
    private var runtime: TunnelsRuntime? = null
    private var diffJob: Job? = null
    private var exportPassword: CharArray? = null

    init {
        viewModelScope.launch {
            val rt = runCatching { TunnelsRuntime.get(app) }.getOrElse { e ->
                _state.update { it.copy(ready = true, error = "Couldn't open the store: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            runtime = rt
            val keyLevel = withContext(Dispatchers.IO) { runCatching { TunnelsStore.keySecurityLevel() }.getOrDefault("?") }
            _state.update { it.copy(tunnelCount = rt.registry.modules.size, keyLevel = keyLevel) }
            launch { rt.engine.state.collect { s -> _state.update { it.copy(scan = s) } } }
            launch {
                combine(rt.store.dao.snapshotsFlow(), refresh) { rows, _ -> rows }.collect { rows ->
                    val detailed = withContext(Dispatchers.IO) { rows.map { describe(rt.store.dao, it) } }
                    val ids = detailed.map { it.id }.toSet()
                    _state.update { it.copy(ready = true, snapshots = detailed, selected = it.selected.filter { id -> id in ids }) }
                    saveSelection()
                    refreshDiff()
                }
            }
        }
    }

    private suspend fun describe(dao: TunnelsDao, row: SnapshotEntity) = SnapshotRow(
        id = row.id,
        takenAt = row.takenAt,
        pinned = row.pinned,
        tunnelIds = dao.tunnelsIn(row.id).sorted(),
        observations = dao.observations(row.id).size,
    )

    // Take snapshot

    fun takeSnapshot() {
        val rt = runtime ?: return
        if (_state.value.scan.running) return
        viewModelScope.launch {
            _state.update { it.copy(error = null, message = null) }
            val result = runCatching { rt.engine.scan(null) }.getOrElse { e ->
                _state.update { it.copy(error = "Snapshot failed: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            _state.update { it.copy(lastResult = result) }
            refresh.update { it + 1 }
        }
    }

    // History

    fun setPinned(id: Long, pinned: Boolean) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { rt.store.dao.setPinned(id, pinned) } }
    }

    fun toggleSelect(id: Long) {
        _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else (s.selected + id).takeLast(2)) }
        saveSelection()
        refreshDiff()
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptyList(), diff = null) }
        saveSelection()
        diffJob?.cancel()
    }

    private fun saveSelection() {
        saved[KEY_SELECTED] = _state.value.selected.toLongArray()
    }

    private fun refreshDiff() {
        val rt = runtime ?: return
        val selected = _state.value.selected
        diffJob?.cancel()
        if (selected.size < 2) {
            _state.update { it.copy(diff = null) }
            return
        }
        diffJob = viewModelScope.launch(Dispatchers.IO) {
            val dao = rt.store.dao
            val a = dao.snapshot(selected[0])
            val b = dao.snapshot(selected[1])
            if (a == null || b == null) {
                _state.update { it.copy(diff = null) }
                return@launch
            }
            val (from, to) = if (a.takenAt < b.takenAt || (a.takenAt == b.takenAt && a.id < b.id)) a to b else b to a
            val before = dao.observations(from.id).map(ObservationEntity::toModel)
            val after = dao.observations(to.id).map(ObservationEntity::toModel)
            val report = DiffGroups.build(DiffEngine.diff(before, after))
            _state.update { it.copy(diff = DiffView(from.id, to.id, from.takenAt, to.takenAt, report)) }
        }
    }

    // Export: password first, then the destination from CreateDocument.

    /** The screen calls this with the confirmed password, then launches the file picker. */
    fun stageExport(password: CharArray) {
        exportPassword?.fill('\u0000')
        exportPassword = password
        _state.update { it.copy(exportNeedsPassword = false) }
    }

    fun cancelExport() {
        exportPassword?.fill('\u0000')
        exportPassword = null
        saved.remove<String>(KEY_EXPORT_URI)
        _state.update { it.copy(exportNeedsPassword = false) }
    }

    /** The picked destination. Without a staged password (process was recreated) the screen asks again. */
    fun exportTo(uri: Uri) {
        val password = exportPassword
        if (password == null) {
            saved[KEY_EXPORT_URI] = uri.toString()
            _state.update { it.copy(exportNeedsPassword = true) }
            return
        }
        exportPassword = null
        runExport(uri, password)
    }

    /** Password re-entered for a destination picked before the process was recreated. */
    fun exportWithPassword(password: CharArray) {
        val uri = saved.get<String>(KEY_EXPORT_URI)?.let(Uri::parse)
        saved.remove<String>(KEY_EXPORT_URI)
        _state.update { it.copy(exportNeedsPassword = false) }
        if (uri == null) {
            password.fill('\u0000')
            return
        }
        runExport(uri, password)
    }

    private fun runExport(uri: Uri, password: CharArray) {
        val rt = runtime ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Exporting…", error = null, message = null) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = StoreBundles.read(rt.store.dao)
                    val plain = BundleFormat.write(bundle).toByteArray(Charsets.UTF_8)
                    val sealed = try {
                        EncryptedFile.seal(plain, password)
                    } finally {
                        plain.fill(0)
                    }
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("The destination couldn't be opened.")
                    out.use { it.write(sealed) }
                    Triple(bundle.snapshots.size, bundle.observations.size, sealed.size)
                }
            }
            password.fill('\u0000')
            outcome.onSuccess { (snapshots, observations, bytes) ->
                _state.update {
                    it.copy(busy = null, message = "Exported $snapshots snapshots and $observations observations, encrypted (${formatBytes(bytes.toLong())}).")
                }
            }.onFailure { e ->
                // Never leave a half-written file behind.
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                _state.update { it.copy(busy = null, error = "Export failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // Import: the file from OpenDocument first, then the password.

    fun pickImport(uri: Uri) {
        saved[KEY_IMPORT_URI] = uri.toString()
        _state.update { it.copy(importPending = true, importError = null) }
    }

    fun cancelImport() {
        saved.remove<String>(KEY_IMPORT_URI)
        _state.update { it.copy(importPending = false, importError = null) }
    }

    fun importWithPassword(password: CharArray) {
        val rt = runtime ?: return
        val uri = saved.get<String>(KEY_IMPORT_URI)?.let(Uri::parse) ?: run {
            password.fill('\u0000')
            cancelImport()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Importing…", error = null, message = null, importError = null) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val sealed = readCapped(uri)
                    val plain = EncryptedFile.open(sealed, password)
                    val bundle = try {
                        BundleFormat.parse(String(plain, Charsets.UTF_8))
                    } finally {
                        plain.fill(0)
                    }
                    StoreBundles.write(rt.store.dao, bundle)
                }
            }
            password.fill('\u0000')
            outcome.onSuccess { r ->
                saved.remove<String>(KEY_IMPORT_URI)
                _state.update {
                    it.copy(
                        busy = null,
                        importPending = false,
                        message = "Imported ${r.snapshots} snapshots with ${r.observations} observations. They're pinned, so retention keeps them.",
                    )
                }
                refresh.update { it + 1 }
            }.onFailure { e ->
                when {
                    e is WrongPasswordOrCorrupt && e !is NotASealedFile ->
                        // Keep the file selected so the user can retry the password.
                        _state.update { it.copy(busy = null, importError = e.message) }
                    else -> {
                        saved.remove<String>(KEY_IMPORT_URI)
                        val why = when (e) {
                            is NotASealedFile -> e.message
                            is BundleFormatException -> "The file decrypted but isn't a snapshot bundle: ${e.message}"
                            else -> e.message ?: e.javaClass.simpleName
                        }
                        _state.update { it.copy(busy = null, importPending = false, error = "Import failed: $why") }
                    }
                }
            }
        }
    }

    private fun readCapped(uri: Uri): ByteArray {
        val input = app.contentResolver.openInputStream(uri) ?: error("The file couldn't be opened.")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_IMPORT_BYTES) error("The file is larger than ${formatBytes(MAX_IMPORT_BYTES)}; that is not a Tunnels export.")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    // Settings

    fun setAppLock(enabled: Boolean) {
        if (enabled && !AppLock.canLock(app)) {
            refreshLock()
            return
        }
        AppLock.setEnabled(app, enabled)
        _state.update { it.copy(appLockEnabled = enabled) }
    }

    fun refreshLock() {
        _state.update { it.copy(appLockEnabled = AppLock.isEnabled(app), canLock = AppLock.canLock(app)) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    override fun onCleared() {
        exportPassword?.fill('\u0000')
        exportPassword = null
    }

    private companion object {
        const val KEY_SELECTED = "selected"
        const val KEY_EXPORT_URI = "export_uri"
        const val KEY_IMPORT_URI = "import_uri"
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    }
}
