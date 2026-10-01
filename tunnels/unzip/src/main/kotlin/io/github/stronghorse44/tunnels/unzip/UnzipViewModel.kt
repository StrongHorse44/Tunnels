package io.github.stronghorse44.tunnels.unzip

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
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
    data class Failed(val title: String, val detail: String) : UnzipState
}

/** One-shot request to open another screen, consumed by the UI. */
sealed interface UnzipNav {
    data class Install(val file: File) : UnzipNav
}

class UnzipViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UnzipState>(UnzipState.Idle)
    val state: StateFlow<UnzipState> = _state.asStateFlow()

    private val _nav = MutableStateFlow<UnzipNav?>(null)
    val nav: StateFlow<UnzipNav?> = _nav.asStateFlow()

    private var staged: StagedFile? = null
    private var password: CharArray? = null
    private var pendingTree: Uri? = null
    private var job: Job? = null

    fun open(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            release()
            _state.value = UnzipState.Working("Copying archive…")
            staged = runCatching { withContext(Dispatchers.IO) { Staging.copyIn(app, uri) } }.getOrElse {
                _state.value = UnzipState.Failed("Can't open this file", it.message ?: "The file couldn't be read.")
                return@launch
            }
            list()
        }
    }

    fun submitPassword(value: String) {
        password = value.toCharArray()
        val tree = pendingTree
        viewModelScope.launch { if (list() && tree != null) extractTo(tree) }
    }

    /** Lists the archive. Returns true when the listing is shown. */
    private suspend fun list(): Boolean {
        val file = staged ?: return false
        _state.value = UnzipState.Working("Reading archive…")
        return try {
            val (format, entries) = withContext(Dispatchers.IO) {
                Archives.open(file.file, file.displayName, password).use { it.format to it.entries() }
            }
            val all = entries.filterNot { it.isDirectory }.map { it.index }.toSet()
            val shape = if (format == ArchiveFormat.ZIP) Bundles.shape(entries.map { it.path }) else PackageShape.NOT_AN_APP
            _state.value = UnzipState.Listing(file.displayName, format, entries, all, shape)
            true
        } catch (e: ArchiveException) {
            onError(e.error)
            false
        }
    }

    fun toggle(index: Int) = _state.update { s ->
        if (s !is UnzipState.Listing) s
        else s.copy(selected = if (index in s.selected) s.selected - index else s.selected + index)
    }

    fun selectAll(all: Boolean) = _state.update { s ->
        if (s !is UnzipState.Listing) s
        else s.copy(selected = if (all) s.entries.filterNot { it.isDirectory }.map { it.index }.toSet() else emptySet())
    }

    fun extractTo(tree: Uri) {
        val listing = _state.value as? UnzipState.Listing ?: return
        val file = staged ?: return
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
                _state.value = UnzipState.Done(listing.name, describeLocation(tree, root.name ?: folderName), root.uri, result)
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
            is ArchiveError.Unsupported -> UnzipState.Failed("Can't open ${error.what}", "Supported: zip (incl. password), 7z, tar, tar.gz, tar.xz, tar.bz2, gz, xz, bz2.")
            is ArchiveError.Corrupt -> UnzipState.Failed("Archive looks damaged", error.detail)
            is ArchiveError.LimitExceeded -> UnzipState.Failed("Stopped for safety", error.detail)
            ArchiveError.Cancelled -> UnzipState.Failed("Cancelled", "Files extracted so far were kept.")
        }
    }

    private fun release() {
        staged?.let { Staging.discard(app, it.file) }
        staged = null
        password = null
        pendingTree = null
    }

    override fun onCleared() = release()

    companion object {
        /** "primary:Download/Stuff" + "pusher" -> "Download/Stuff/pusher". */
        fun describeLocation(tree: Uri, folder: String): String {
            val base = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?.substringAfter(':')?.trim('/').orEmpty()
            return if (base.isEmpty()) folder else "$base/$folder"
        }

        private val suffixes = listOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz", ".tbz2", ".zip", ".7z", ".tar", ".gz", ".xz", ".bz2", ".apks", ".xapk", ".apkm")

        fun baseName(name: String): String {
            val lower = name.lowercase()
            val suffix = suffixes.firstOrNull { lower.endsWith(it) }
            return (if (suffix != null) name.dropLast(suffix.length) else name).ifBlank { "extracted" }
        }
    }
}
