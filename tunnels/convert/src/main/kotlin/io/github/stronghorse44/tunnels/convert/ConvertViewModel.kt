package io.github.stronghorse44.tunnels.convert

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.common.StagedFile
import io.github.stronghorse44.tunnels.common.Staging
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

sealed interface ConvertState {
    data object Idle : ConvertState
    data class Working(val message: String) : ConvertState

    /** A file is open. [targets] is empty when it can't be converted, and [refusal] says why. */
    data class Ready(val name: String, val format: InputFormat, val bytes: Long, val targets: List<OutputFormat>, val refusal: String?) : ConvertState
    data class Converting(val name: String, val target: OutputFormat, val done: Int, val total: Int) : ConvertState

    /** [uri] is the file written, or the folder when [isFolder]. */
    data class Done(val name: String, val target: OutputFormat, val outputName: String, val uri: Uri, val isFolder: Boolean, val detail: String) : ConvertState
    data class Failed(val title: String, val detail: String) : ConvertState
}

/**
 * Converts one file at a time, entirely on the phone. The staged copy and the chosen target survive Android
 * recreating the screen while the save picker is open.
 */
class ConvertViewModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<ConvertState>(ConvertState.Idle)
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    private var staged: StagedFile? = null
    private var format: InputFormat = InputFormat.UNKNOWN
    private var job: Job? = null

    init {
        val path = saved.get<String>(KEY_PATH)
        val name = saved.get<String>(KEY_NAME)
        val file = path?.let(::File)
        if (file != null && name != null && file.isFile && Staging.contains(app, file)) {
            staged = StagedFile(file, name)
            job = viewModelScope.launch { inspect() }
        }
    }

    fun open(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            discard()
            _state.value = ConvertState.Working("Copying file…")
            val copied = runCatching { withContext(Dispatchers.IO) { Staging.copyIn(app, uri) } }.getOrElse {
                _state.value = ConvertState.Failed("Can't open this file", it.message ?: "The file couldn't be read.")
                return@launch
            }
            remember(copied)
            inspect()
        }
    }

    /** Text shared from another app (not a file) becomes a plain-text document. */
    fun openText(text: String, subject: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            discard()
            val name = (subject?.trim()?.take(60)?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.ifBlank { null } ?: "Shared text") + ".txt"
            val file = withContext(Dispatchers.IO) { File(Staging.newDir(app), name).apply { writeText(text) } }
            remember(StagedFile(file, name))
            inspect()
        }
    }

    private fun remember(file: StagedFile) {
        staged = file
        saved[KEY_PATH] = file.file.absolutePath
        saved[KEY_NAME] = file.displayName
    }

    private suspend fun inspect() {
        val file = staged ?: return
        val (detected, size) = withContext(Dispatchers.IO) {
            val head = ByteArray(Formats.HEAD)
            val n = file.file.inputStream().use { input -> input.readNBytes(head, 0, head.size) }
            Formats.detect(head.copyOf(n), file.displayName) to file.file.length()
        }
        format = detected
        val tooBig = size > ConvertLimits.DEFAULT.maxInputBytes
        _state.value = ConvertState.Ready(
            name = file.displayName,
            format = detected,
            bytes = size,
            targets = if (tooBig) emptyList() else Conversions.targets(detected),
            refusal = if (tooBig) "Files over ${ConvertLimits.DEFAULT.maxInputBytes / (1024 * 1024)} MB aren't converted." else Conversions.refusal(detected),
        )
    }

    /** Remembers the target before a picker opens, so the result still knows it after recreation. */
    fun choose(target: OutputFormat) {
        saved[KEY_TARGET] = target.name
    }

    val chosen: OutputFormat? get() = saved.get<String>(KEY_TARGET)?.let { n -> OutputFormat.entries.firstOrNull { it.name == n } }

    /** Writes the converted file to [dest], a document the user just created in the save picker. */
    fun convertTo(dest: Uri) = start(dest, isFolder = false)

    /** Writes one image per page into a new folder inside [tree]. */
    fun convertToFolder(tree: Uri) = start(tree, isFolder = true)

    private fun start(dest: Uri, isFolder: Boolean) {
        val previous = job
        job = viewModelScope.launch {
            previous?.join()
            val file = staged
            val target = chosen
            if (file == null || !file.file.isFile || target == null) {
                // Never fail silently: Android may have stopped Tunnels while the picker was open.
                if (!isFolder) deleteQuietly(dest)
                _state.value = ConvertState.Failed("Nothing was converted", "Tunnels lost track of the file while the picker was open. Open it again and retry.")
                return@launch
            }
            _state.value = ConvertState.Converting(file.displayName, target, 0, 0)
            var folder: DocumentFile? = null
            try {
                val (outName, detail) = withContext(Dispatchers.IO) {
                    if (isFolder) {
                        val root = DocumentFile.fromTreeUri(app, dest)?.createDirectory(Conversions.baseName(file.displayName) + " pages")
                            ?: throw IOException("Couldn't create a folder there. Pick another location.")
                        folder = root
                        val pages = PdfPages.render(
                            file.file,
                            target,
                            openPage = { i, count -> create(root, target, Conversions.pageName(file.displayName, i + 1, count, target)) },
                            isCancelled = { !isActive },
                            progress = { done, total -> _state.value = ConvertState.Converting(file.displayName, target, done, total) },
                        )
                        (root.name ?: "") to "$pages ${if (pages == 1) "page" else "pages"}"
                    } else {
                        val out = app.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("Couldn't write there.")
                        val detail = out.use { convertOne(file, target, it) }
                        (DocumentFile.fromSingleUri(app, dest)?.name ?: Conversions.outputName(file.displayName, target)) to detail
                    }
                }
                val uri = folder?.uri ?: dest
                _state.value = ConvertState.Done(file.displayName, target, outName, uri, isFolder, detail)
                saved.remove<String>(KEY_TARGET)
                withContext(Dispatchers.IO) {
                    runCatching {
                        TunnelsStore.get(app).recordEvent(
                            tunnelId = ConvertActivity.STREAM,
                            kind = "CONVERTED",
                            subject = file.displayName,
                            summary = "${format.label} → ${target.label}${if (detail.isNotEmpty()) " · $detail" else ""}",
                        )
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + NonCancellable) { if (!isFolder) deleteQuietly(dest) }
                _state.value = ConvertState.Failed("Cancelled", if (isFolder) "Pages written so far were kept." else "The partly written file was removed.")
                throw e
            } catch (e: ConvertException) {
                withContext(Dispatchers.IO) { if (!isFolder) deleteQuietly(dest) else folder?.takeIf { it.listFiles().isEmpty() }?.delete() }
                _state.value = failure(e.error)
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { if (!isFolder) deleteQuietly(dest) }
                _state.value = ConvertState.Failed("Conversion failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Converts into one output stream. Returns a short detail for the result ("3 pages"), or "". */
    private suspend fun convertOne(file: StagedFile, target: OutputFormat, out: OutputStream): String {
        val name = file.displayName
        return when {
            format.isDocument -> {
                val doc = Documents.read(format, file.file.readBytes())
                when (target) {
                    OutputFormat.PDF -> {
                        val pages = withContext(Dispatchers.IO) {
                            DocumentPdf.write(doc, out, isCancelled = { !isActive }, progress = { n -> _state.value = ConvertState.Converting(name, target, n, 0) })
                        }
                        "$pages ${if (pages == 1) "page" else "pages"}"
                    }
                    OutputFormat.TXT -> {
                        out.write(TextFiles.write(doc).toByteArray(Charsets.UTF_8))
                        "${doc.paragraphs.size} paragraphs"
                    }
                    else -> throw ConvertException(ConvertError.Unsupported("${format.label} can't become ${target.label}."))
                }
            }
            format.isImage -> {
                if (target == OutputFormat.PDF) Images.toPdf(file.file, out) else Images.toImage(file.file, target, out)
                ""
            }
            else -> throw ConvertException(ConvertError.Unsupported(Conversions.refusal(format) ?: "${format.label} can't become ${target.label}."))
        }
    }

    private fun create(root: DocumentFile, target: OutputFormat, name: String): OutputStream {
        val doc = root.createFile(target.mime, name) ?: throw IOException("Couldn't create $name")
        return app.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Couldn't write $name")
    }

    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
    }

    private fun failure(error: ConvertError): ConvertState.Failed = when (error) {
        is ConvertError.Unsupported -> ConvertState.Failed("Can't convert this", error.detail)
        is ConvertError.Damaged -> ConvertState.Failed("File looks damaged", error.detail)
        is ConvertError.LimitExceeded -> ConvertState.Failed("Stopped for safety", error.detail)
    }

    fun cancel() {
        job?.cancel()
    }

    fun backToFile() {
        job = viewModelScope.launch { inspect() }
    }

    /** Deletes the staged copy. Called when the screen is really closing, not when it's merely recreated. */
    fun discard() {
        staged?.let { Staging.discard(app, it.file) }
        staged = null
        format = InputFormat.UNKNOWN
        listOf(KEY_PATH, KEY_NAME, KEY_TARGET).forEach { saved.remove<Any>(it) }
    }

    companion object {
        private const val KEY_PATH = "staged_path"
        private const val KEY_NAME = "staged_name"
        private const val KEY_TARGET = "target"
    }
}
