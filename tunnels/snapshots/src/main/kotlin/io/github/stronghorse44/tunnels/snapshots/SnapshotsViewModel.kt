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
        val rt = runtime ?: run {
            password.fill('\u0000')
            return
        }
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
        val uri = saved.get<String>(KEY_IMPORT_URI)?.let(Uri::parse)
        val rt = runtime
        if (uri == null || rt == null) {
            password.fill('\u0000')
            cancelImport()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Importing…", error = null, message = null, importError = null) }
            importing = true
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val sealed = readCapped(uri)
                    val plain = EncryptedFile.open(sealed, password)
                    // Parsed straight from the bytes: no String copy of the whole bundle.
                    val bundle = try {
                        BundleFormat.parse(plain.inputStream().reader(Charsets.UTF_8))
                    } finally {
                        plain.fill(0)
                    }
                    StoreBundles.write(rt.store.dao, bundle)
                }
            }
            importing = false
            password.fill('\u0000')
            outcome.onSuccess { r ->
                saved.remove<String>(KEY_IMPORT_URI)
                val skipped = if (r.skipped > 0) " ${r.skipped} already here, skipped." else ""
                _state.update {
                    it.copy(
                        busy = null,
                        importPending = false,
                        message = "Imported ${r.snapshots} snapshots with ${r.observations} observations.$skipped" +
                            if (r.snapshots > 0) " They're pinned, so retention keeps them." else "",
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
                        // A failed write may have rolled rows back; make the list agree with the store.
                        refresh.update { it + 1 }
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

        /** A full 12-snapshot export of a 300-app phone is ~10-15 MB; anything past this is not a Tunnels export. */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    }
}
