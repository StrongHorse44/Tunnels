package io.github.stronghorse44.tunnels.snapshots

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.common.formatBytes
import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.export.Inspected
import io.github.stronghorse44.tunnels.export.TransferMessages
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

/** The header of a picked import file. Nothing in it is verified until the passphrase has opened the file. */
data class ImportInfo(val legacy: Boolean, val schema: Long?, val createdMs: Long?)

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
    /** A file was picked for import and waits for its passphrase. */
    val importPending: Boolean = false,
    /** What the picked file says about itself, before any passphrase: unverified. Null until it has been read. */
    val importInfo: ImportInfo? = null,
    val importError: String? = null,
    val appLockEnabled: Boolean = false,
    val canLock: Boolean = false,
    /** Why the lock cannot even be checked (a build problem), as opposed to the device simply having no screen lock. */
    val lockUnavailable: String? = null,
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
        ).withLockStatus(),
    )
    val state: StateFlow<SnapshotsState> = _state.asStateFlow()

    /** What a snapshot holds. Immutable once its scan or import has finished writing, so read once per id. */
    private data class SnapshotFacts(val tunnelIds: List<String>, val observations: Int)

    /** Bumped after a scan or import so the rows written by it are read again (Room only watches the snapshots table). */
    private val refresh = MutableStateFlow(0)
    private var runtime: TunnelsRuntime? = null

    /** Facts per snapshot id; only the history collector touches these. */
    private val facts = HashMap<Long, SnapshotFacts>()

    /** Ids described while a scan or import was still writing: their facts were not cached and are read again. */
    private val unsettled = HashSet<Long>()
    private var importing = false

    private var diffJob: Job? = null

    /** The pair [diffJob] is computing, so a repeat request for the same pair does not restart it. */
    private var diffPair: Set<Long>? = null
    private var exportPassword: CharArray? = null

    init {
        viewModelScope.launch {
            val rt = runCatching { TunnelsRuntime.get(app) }.getOrElse { e ->
                _state.update { it.copy(ready = true, error = "Couldn't open the store: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            runtime = rt
            // The screen was recreated behind the file picker: read the header of the picked file again for the dialog.
            saved.get<String>(KEY_IMPORT_URI)?.let { inspectPicked(Uri.parse(it)) }
            val keyLevel = withContext(Dispatchers.IO) { runCatching { TunnelsStore.keySecurityLevel() }.getOrDefault("?") }
            _state.update { it.copy(tunnelCount = rt.registry.modules.size, keyLevel = keyLevel) }
            launch {
                var wasRunning = false
                rt.engine.state.collect { s ->
                    _state.update { it.copy(scan = s) }
                    // A scan started from another screen also writes snapshots this screen shows.
                    if (wasRunning && !s.running) refresh.update { it + 1 }
                    wasRunning = s.running
                }
            }
            launch {
                combine(rt.store.dao.snapshotsFlow(), refresh) { rows, _ -> rows }.collect { rows -> onSnapshots(rt, rows) }
            }
        }
    }

    /**
     * Turns store rows into [SnapshotRow]s, reading each snapshot's facts from the observations table only once:
     * on screen open, after a scan or import (new ids only) and after retention drops rows (none). A pin toggle
     * re-emits the rows and costs nothing here. Rows seen while a scan or import is still writing are not cached,
     * because their observations may be incomplete; the refresh after it finishes reads them again.
     */
    private suspend fun onSnapshots(rt: TunnelsRuntime, rows: List<SnapshotEntity>) {
        val settled = !rt.engine.state.value.running && !importing
        val ids = rows.mapTo(HashSet()) { it.id }
        facts.keys.retainAll(ids)
        unsettled.retainAll(ids)
        val nowSettled = HashSet<Long>()
        val detailed = withContext(Dispatchers.IO) {
            rows.map { row ->
                val f = facts[row.id] ?: describe(rt.store.dao, row.id).also {
                    if (settled) {
                        facts[row.id] = it
                        if (unsettled.remove(row.id)) nowSettled += row.id
                    } else {
                        unsettled += row.id
                    }
                }
                SnapshotRow(row.id, row.takenAt, row.pinned, f.tunnelIds, f.observations)
            }
        }
        var selectionChanged = false
        _state.update { s ->
            val kept = s.selected.filter { id -> id in ids }
            selectionChanged = kept != s.selected
            s.copy(ready = true, snapshots = detailed, selected = kept)
        }
        if (selectionChanged) saveSelection()
        // A diff computed from a snapshot that was still being written is redone once that snapshot has settled.
        syncDiff(force = _state.value.selected.any { it in nowSettled })
    }

    private suspend fun describe(dao: TunnelsDao, id: Long): SnapshotFacts =
        SnapshotFacts(tunnelIds = dao.tunnelsIn(id).sorted(), observations = dao.observations(id).size)

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
        syncDiff()
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptyList()) }
        saveSelection()
        syncDiff()
    }

    private fun saveSelection() {
        saved[KEY_SELECTED] = _state.value.selected.toLongArray()
    }

    /** Brings [SnapshotsState.diff] in line with the selection, recomputing only when the pair changed (or [force]). */
    private fun syncDiff(force: Boolean = false) {
        val selected = _state.value.selected
        if (selected.size < 2) {
            diffJob?.cancel()
            diffPair = null
            if (_state.value.diff != null) _state.update { it.copy(diff = null) }
            return
        }
        val pair = selected.toSet()
        val shown = _state.value.diff?.let { setOf(it.fromId, it.toId) }
        if (!force && (pair == shown || (pair == diffPair && diffJob?.isActive == true))) return
        refreshDiff(pair)
    }

    private fun refreshDiff(pair: Set<Long>) {
        val rt = runtime ?: return
        diffJob?.cancel()
        diffPair = pair
        val (first, second) = pair.toList()
        diffJob = viewModelScope.launch(Dispatchers.IO) {
            val dao = rt.store.dao
            val a = dao.snapshot(first)
            val b = dao.snapshot(second)
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
        val transfer = transfer() ?: run {
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Exporting…", error = null, message = null) }
            val outcome = runCatching { withContext(Dispatchers.IO) { transfer.export(uri, password, System.currentTimeMillis()) } }
            password.fill('\u0000')
            outcome.onSuccess { r ->
                _state.update { it.copy(busy = null, message = TransferMessages.exported(r.counts, formatBytes(r.bytes))) }
            }.onFailure { e ->
                val text = when (e) {
                    is DataTransfer.ExportFailed -> TransferMessages.exportFailure(e.cause ?: e, e.cleanup)
                    else -> "Export failed: ${e.message ?: e.javaClass.simpleName}."
                }
                _state.update { it.copy(busy = null, error = text) }
            }
        }
    }

    private fun transfer(): DataTransfer? {
        val rt = runtime
        if (rt == null) _state.update { it.copy(error = "The store isn't open yet.") }
        return rt?.let { DataTransfer(app, it.store.dao) }
    }

    // Import: the file from OpenDocument first, then the password.

    fun pickImport(uri: Uri) {
        _state.update { it.copy(error = null, message = null, importError = null) }
        inspectPicked(uri, remember = true)
    }

    /**
     * Steps 1 to 6 of the container spec's section 5.1, before any passphrase: a file of another app, a newer schema
     * or no export at all is refused here, by name, and nothing is asked. [remember] keeps a good file for the retry
     * after a process restart.
     */
    private fun inspectPicked(uri: Uri, remember: Boolean = false) {
        val transfer = transfer() ?: return
        viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { transfer.inspect(uri) } }
            outcome.onSuccess { info ->
                if (remember) saved[KEY_IMPORT_URI] = uri.toString()
                val shown = when (info) {
                    is Inspected.Fwx -> ImportInfo(legacy = false, schema = info.schema, createdMs = info.createdMs)
                    Inspected.Legacy -> ImportInfo(legacy = true, schema = null, createdMs = null)
                }
                _state.update { it.copy(importPending = true, importInfo = shown, importError = null) }
            }.onFailure { e ->
                saved.remove<String>(KEY_IMPORT_URI)
                _state.update { it.copy(importPending = false, importInfo = null, error = "Import failed: ${TransferMessages.importFailure(e)}") }
            }
        }
    }

    fun cancelImport() {
        saved.remove<String>(KEY_IMPORT_URI)
        _state.update { it.copy(importPending = false, importInfo = null, importError = null) }
    }

    fun importWithPassword(password: CharArray) {
        val uri = saved.get<String>(KEY_IMPORT_URI)?.let(Uri::parse)
        val transfer = transfer()
        if (uri == null || transfer == null) {
            password.fill('\u0000')
            cancelImport()
            return
        }
        val legacy = _state.value.importInfo?.legacy == true
        viewModelScope.launch {
            _state.update { it.copy(busy = "Importing…", error = null, message = null, importError = null) }
            importing = true
            val outcome = runCatching { withContext(Dispatchers.IO) { transfer.import(uri, password) } }
            importing = false
            password.fill('\u0000')
            outcome.onSuccess { r ->
                saved.remove<String>(KEY_IMPORT_URI)
                _state.update { it.copy(busy = null, importPending = false, importInfo = null, message = TransferMessages.imported(r)) }
                refresh.update { it + 1 }
            }.onFailure { e ->
                if (TransferMessages.isWrongPassphrase(e)) {
                    // Keep the file selected so the user can retry the passphrase. Nothing was changed.
                    _state.update { it.copy(busy = null, importError = TransferMessages.importFailure(e, legacy)) }
                } else {
                    saved.remove<String>(KEY_IMPORT_URI)
                    _state.update {
                        it.copy(busy = null, importPending = false, importInfo = null, error = "Import failed: ${TransferMessages.importFailure(e, legacy)}")
                    }
                    // A failed write rolled back; make the list agree with the store.
                    refresh.update { it + 1 }
                }
            }
        }
    }

    // Settings

    fun setAppLock(enabled: Boolean) {
        val current = _state.value.withLockStatus()
        if (enabled && !current.canLock) {
            _state.value = current
            return
        }
        AppLock.setEnabled(app, enabled)
        _state.update { it.copy(appLockEnabled = enabled) }
    }

    fun refreshLock() {
        _state.update { it.withLockStatus() }
    }

    /**
     * Reads the lock setting and whether the device can lock. BiometricManager.canAuthenticate needs the normal
     * USE_BIOMETRIC permission; a build without it throws SecurityException, which must not take the screen down.
     */
    private fun SnapshotsState.withLockStatus(): SnapshotsState {
        val enabled = AppLock.isEnabled(app)
        return try {
            copy(appLockEnabled = enabled, canLock = AppLock.canLock(app), lockUnavailable = null)
        } catch (e: SecurityException) {
            copy(
                appLockEnabled = enabled,
                canLock = false,
                lockUnavailable = "This build can't check for a screen lock: it lacks the USE_BIOMETRIC permission.",
            )
        }
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
    }
}
