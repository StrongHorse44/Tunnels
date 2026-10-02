package io.github.stronghorse44.tunnels.unzip

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.archive.ArchiveEntry
import io.github.stronghorse44.tunnels.archive.ArchiveError
import io.github.stronghorse44.tunnels.archive.ArchiveException
import io.github.stronghorse44.tunnels.archive.ArchiveFormat
import io.github.stronghorse44.tunnels.archive.ArchiveLayout
import io.github.stronghorse44.tunnels.archive.Archives
import io.github.stronghorse44.tunnels.archive.DirectorySink
import io.github.stronghorse44.tunnels.archive.ExtractResult
import io.github.stronghorse44.tunnels.archive.SafePath
import io.github.stronghorse44.tunnels.archive.StripFirstSegmentSink
import io.github.stronghorse44.tunnels.common.StagedFile
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.install.Bundles
import io.github.stronghorse44.tunnels.install.PackageShape
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UnzipState {
    data object Idle : UnzipState
    data class Working(val message: String) : UnzipState
    data class NeedsPassword(val name: String, val wrong: Boolean) : UnzipState
    data class Listing(
        val name: String,
        val format: ArchiveFormat,
        val entries: List<ArchiveEntry>,
        val selected: Set<Int>,
        val shape: PackageShape,
    ) : UnzipState
    data class Extracting(val name: String, val bytesDone: Long, val bytesTotal: Long, val current: String) : UnzipState
    /** [location] is a readable path like "Download/pusher"; [folderUri] opens it in Files. */
    data class Done(val name: String, val location: String, val folderUri: Uri, val result: ExtractResult) : UnzipState
    /** [keepable]: the file itself is still at hand, so it can be saved or shared whole. */
    data class Failed(val title: String, val detail: String, val keepable: Boolean = false) : UnzipState
}

/** One-shot request to open another screen, consumed by the UI. */
sealed interface UnzipNav {
    data class Install(val file: File) : UnzipNav
    data class Share(val uri: Uri, val mimeType: String, val name: String) : UnzipNav
    data class Message(val text: String) : UnzipNav
}

/**
 * Survives Android recreating the screen while the folder picker is open (low memory, or
 * "Don't keep activities"): the staged archive and selection live in [saved]. Passwords are not saved.
 */
class UnzipViewModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UnzipState>(UnzipState.Idle)
    val state: StateFlow<UnzipState> = _state.asStateFlow()

    private val _nav = MutableStateFlow<UnzipNav?>(null)
    val nav: StateFlow<UnzipNav?> = _nav.asStateFlow()

    private var staged: StagedFile? = null
    private var password: CharArray? = null
    private var pendingTree: Uri? = null
    private var job: Job? = null
    private var restoring = false
    private var restoredTree: Uri? = null

    init {
        val path = saved.get<String>(KEY_PATH)
        val name = saved.get<String>(KEY_NAME)
        val file = path?.let(::File)
        if (file != null && name != null && file.isFile && Staging.contains(app, file)) {
            staged = StagedFile(file, name)
            restoring = true
            job = viewModelScope.launch {
                list(saved.get<IntArray>(KEY_SELECTED)?.toSet())
                restoring = false
                val tree = restoredTree ?: return@launch
                restoredTree = null
                if (_state.value is UnzipState.NeedsPassword) pendingTree = tree else extractTo(tree)
            }
        }
    }

    private fun saveSelection() {
        (_state.value as? UnzipState.Listing)?.let { saved[KEY_SELECTED] = it.selected.toIntArray() }
    }

    fun open(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            discard()
            _state.value = UnzipState.Working("Copying archive…")
            val copied = runCatching { withContext(Dispatchers.IO) { Staging.copyIn(app, uri) } }.getOrElse {
                _state.value = UnzipState.Failed("Can't open this file", it.message ?: "The file couldn't be read.")
                return@launch
            }
            staged = copied
            saved[KEY_PATH] = copied.file.absolutePath
            saved[KEY_NAME] = copied.displayName
            saved.remove<IntArray>(KEY_SELECTED)
            list()
        }
    }

    fun submitPassword(value: String) {
        password = value.toCharArray()
        val tree = pendingTree
        viewModelScope.launch { if (list() && tree != null) extractTo(tree) }
    }

    /** Lists the archive. Returns true when the listing is shown. */
    private suspend fun list(restoreSelection: Set<Int>? = null): Boolean {
        val file = staged ?: return false
        _state.value = UnzipState.Working("Reading archive…")
        return try {
            val (format, entries) = withContext(Dispatchers.IO) {
                Archives.open(file.file, file.displayName, password).use { it.format to it.entries() }
            }
            val all = entries.filterNot { it.isDirectory }.map { it.index }.toSet()
            val selected = restoreSelection?.intersect(all) ?: all
            val shape = if (format == ArchiveFormat.ZIP) Bundles.shape(entries.map { it.path }) else PackageShape.NOT_AN_APP
            _state.value = UnzipState.Listing(file.displayName, format, entries, selected, shape)
            saveSelection()
            true
        } catch (e: ArchiveException) {
            onError(e.error)
            false
        }
    }

    fun toggle(index: Int) {
        _state.update { s ->
            if (s !is UnzipState.Listing) s
            else s.copy(selected = if (index in s.selected) s.selected - index else s.selected + index)
        }
        saveSelection()
    }

    fun selectAll(all: Boolean) {
        _state.update { s ->
            if (s !is UnzipState.Listing) s
            else s.copy(selected = if (all) s.entries.filterNot { it.isDirectory }.map { it.index }.toSet() else emptySet())
        }
        saveSelection()
    }

    fun extractTo(tree: Uri) {
        val listing = _state.value as? UnzipState.Listing
        val file = staged
        if (listing == null && restoring) {
            // Screen was recreated while the picker was open; extract once the archive is re-read.
            restoredTree = tree
            return
        }
        if (listing == null || file == null || !file.file.isFile) {
            // Never fail silently: Android may have stopped Tunnels while the folder picker was open.
            _state.value = UnzipState.Failed(
                "Nothing was extracted",
                "Tunnels lost track of the archive while the folder picker was open. Open the archive again and retry.",
            )
            return
        }
        if (password == null && listing.entries.any { it.encrypted && it.index in listing.selected }) {
            pendingTree = tree
            _state.value = UnzipState.NeedsPassword(listing.name, wrong = false)
            return
        }
        job = viewModelScope.launch {
            // An archive whose contents already sit in one folder gets that folder, not a second wrapper.
            val singleRoot = ArchiveLayout.singleRoot(listing.entries.filter { it.index in listing.selected })
            val folderName = singleRoot ?: baseName(listing.name)
            _state.value = UnzipState.Extracting(listing.name, 0, -1, "")
            try {
                val (root, result) = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(app, tree)?.createDirectory(folderName)
                        ?: throw ArchiveException(ArchiveError.Corrupt("Couldn't create a folder there. Pick another location."))
                    val treeSink = DocumentTreeSink(app.contentResolver, root)
                    val sink = if (singleRoot != null) StripFirstSegmentSink(treeSink) else treeSink
                    var lastEmit = 0L
                    root to Archives.open(file.file, file.displayName, password).use { reader ->
                        reader.extract(
                            selection = listing.selected,
                            sink = sink,
                            progress = { done, total, current ->
                                val now = System.nanoTime()
                                if (now - lastEmit > 100_000_000L) {
                                    lastEmit = now
                                    _state.value = UnzipState.Extracting(listing.name, done, total, current)
                                }
                            },
                            isCancelled = { !isActive },
                        )
                    }
                }
                pendingTree = null
                val location = describeLocation(tree, root.name ?: folderName)
                _state.value = UnzipState.Done(listing.name, location, root.uri, result)
                withContext(Dispatchers.IO) {
                    runCatching {
                        TunnelsStore.get(app).recordEvent(
                            tunnelId = TunnelCatalog.UNZIP,
                            kind = "EXTRACTED",
                            subject = listing.name,
                            summary = "${result.files} files → $location",
                        )
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UnzipState.Failed("Cancelled", "Files extracted so far were kept.")
                throw e
            } catch (e: ArchiveException) {
                pendingTree = tree
                onError(e.error)
            } catch (e: Exception) {
                _state.value = UnzipState.Failed("Extraction failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Pulls one APK out of the archive and hands it to the installer. */
    fun installEntry(entry: ArchiveEntry) {
        val file = staged ?: return
        viewModelScope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = Staging.newDir(app)
                    val sink = DirectorySink(dir)
                    Archives.open(file.file, file.displayName, password).use { it.extract(setOf(entry.index), sink) }
                    SafePath.segments(entry.path)?.let(sink::resolve)?.takeIf { it.isFile }
                }
            }.getOrNull()
            if (apk != null) _nav.value = UnzipNav.Install(apk)
            else _state.value = UnzipState.Failed("Couldn't extract the APK", entry.path)
        }
    }

    /** Copies the file as it arrived, unextracted, into Download/. MediaStore needs no permission for this. */
    fun saveToDownloads() {
        val file = staged ?: return
        if (_state.value is UnzipState.Working) return
        val previous = _state.value
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UnzipState.Working("Saving ${file.displayName} to Downloads…")
            val saved = runCatching { withContext(Dispatchers.IO) { copyToDownloads(file) } }
            _state.value = previous
            _nav.value = UnzipNav.Message(
                saved.fold({ "Saved to Download/$it" }, { "Couldn't save to Downloads: ${it.message ?: it.javaClass.simpleName}" }),
            )
        }
    }

    private fun copyToDownloads(file: StagedFile): String {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(file.displayName))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Downloads refused the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("Can't write to Downloads")
            out.use { o -> file.file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames on a clash ("map (1).pmtiles"); report the name it actually used.
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: file.displayName
    }

    /** Hands the file as it arrived, unextracted, to another app through the share sheet. */
    fun share() {
        val file = staged ?: return
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.unzip.files", file.file, file.displayName)
        _nav.value = UnzipNav.Share(uri, mimeType(file.displayName), file.displayName)
    }

    /** Sends the whole archive to the installer as an app bundle. */
    fun installBundle() {
        staged?.let { _nav.value = UnzipNav.Install(it.file) }
    }

    fun navHandled() {
        _nav.value = null
    }

    fun cancel() {
        job?.cancel()
    }

    fun backToListing() {
        viewModelScope.launch { list() }
    }

    private fun onError(error: ArchiveError) {
        val name = staged?.displayName ?: "Archive"
        _state.value = when (error) {
            ArchiveError.PasswordRequired -> UnzipState.NeedsPassword(name, wrong = false)
            ArchiveError.WrongPassword -> UnzipState.NeedsPassword(name, wrong = true).also { password = null }
            is ArchiveError.Unsupported -> UnzipState.Failed("Can't open ${error.what}", "Supported: zip (incl. password), 7z, tar, tar.gz, tar.xz, tar.bz2, gz, xz, bz2, pmtiles.", staged != null)
            is ArchiveError.Corrupt -> UnzipState.Failed("Archive looks damaged", error.detail, staged != null)
            is ArchiveError.LimitExceeded -> UnzipState.Failed("Stopped for safety", error.detail, staged != null)
            ArchiveError.Cancelled -> UnzipState.Failed("Cancelled", "Files extracted so far were kept.")
        }
    }

    /** Deletes the staged archive. Called when the screen is really closing, not when it's merely recreated. */
    fun discard() {
        staged?.let { Staging.discard(app, it.file) }
        staged = null
        password = null
        pendingTree = null
        listOf(KEY_PATH, KEY_NAME, KEY_SELECTED).forEach { saved.remove<Any>(it) }
    }

    companion object {
        private const val KEY_PATH = "staged_path"
        private const val KEY_NAME = "staged_name"
        private const val KEY_SELECTED = "selected"

        /** "primary:Download/Stuff" + "pusher" -> "Download/Stuff/pusher". */
        fun describeLocation(tree: Uri, folder: String): String {
            val base = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?.substringAfter(':')?.trim('/').orEmpty()
            return if (base.isEmpty()) folder else "$base/$folder"
        }

        private val suffixes = listOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz", ".tbz2", ".zip", ".7z", ".tar", ".gz", ".xz", ".bz2", ".pmtiles", ".apks", ".xapk", ".apkm")

        fun mimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "pmtiles") return "application/vnd.pmtiles"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun baseName(name: String): String {
            val lower = name.lowercase()
            val suffix = suffixes.firstOrNull { lower.endsWith(it) }
            return (if (suffix != null) name.dropLast(suffix.length) else name).ifBlank { "extracted" }
        }
    }
}
