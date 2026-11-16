package com.scanlite.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.scanlite.ScanLiteApplication
import com.scanlite.data.local.ScanRecord
import com.scanlite.processing.*
import com.scanlite.storage.FileStorageManager
import com.scanlite.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.util.UUID

data class ScanState(
    val pages: List<SourcePage> = emptyList(),
    val pageIndex: Int = 0,
    val format: ExportFormat = ExportFormat.JPEG,
    val maximum: String = "",
    val unit: SizeUnit = SizeUnit.KB,
    val grayscale: Boolean = false,
    val prepared: PreparedExport? = null,
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val notice: String? = null,
    val folder: String? = null,
    val screen: String = "home"
)

class ScanViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val dao = (app as ScanLiteApplication).database.scans()
    private val storage = FileStorageManager(app)
    private val storagePreferences = app.getSharedPreferences("storage", android.content.Context.MODE_PRIVATE)
    private val root = File(app.cacheDir, "sessions").apply { mkdirs() }
    private var job: Job? = null
    @Volatile private var revision = 0
    private val restored = (saved.get<ArrayList<String>>("pages") ?: arrayListOf()).mapIndexedNotNull { i, path ->
        val file = File(path)
        if (file.isFile) SourcePage(file, saved.get<ArrayList<Int>>("rotations")?.getOrNull(i) ?: 0) else null
    }
    private val _state = MutableStateFlow(ScanState(pages = restored,
        format = saved.get<String>("format")?.let { runCatching { ExportFormat.valueOf(it) }.getOrNull() } ?: ExportFormat.JPEG,
        maximum = saved["maximum"] ?: "", unit = saved.get<String>("unit")?.let { SizeUnit.valueOf(it) } ?: SizeUnit.KB,
        grayscale = saved["grayscale"] ?: false, folder = storagePreferences.getString("default_folder", null),
        screen = if (restored.isNotEmpty()) "preview" else "home"))
    val state = _state.asStateFlow()
    val history = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Keep the restored session, discard orphaned outputs from prior sessions/process deaths.
        val keep = restored.map { it.file.parentFile?.absolutePath }.toSet()
        val orphaned = root.listFiles()?.filter { it.absolutePath !in keep }.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            orphaned.forEach { it.deleteRecursively() }
        }
    }

    private fun persist() {
        val s = state.value
        saved["pages"] = ArrayList(s.pages.map { it.file.absolutePath })
        saved["rotations"] = ArrayList(s.pages.map { it.rotation })
        saved["format"] = s.format.name
        saved["maximum"] = s.maximum
        saved["unit"] = s.unit.name
        saved["grayscale"] = s.grayscale
        saved["folder"] = s.folder
    }
    fun message(text: String) { _state.update { it.copy(error = text, busy = false) } }
    fun clearMessage() { _state.update { it.copy(error = null, notice = null) } }
    fun clearNotice() { _state.update { it.copy(notice = null) } }
    fun scannerLoading() { _state.update { it.copy(busy = true, progress = "Preparing scanner… First use may download components via Google Play services.", error = null) } }
    fun scannerFinished() { _state.update { it.copy(busy = false) } }
    fun navigate(screen: String) { _state.update { it.copy(screen = screen) } }
    fun page(index: Int) { _state.update { it.copy(pageIndex = index.coerceIn(0, maxOf(0, it.pages.lastIndex))) } }

    private fun invalidate(transform: (ScanState) -> ScanState) {
        revision++
        job?.cancel()
        val old = state.value.prepared
        _state.update { transform(it).copy(prepared = null, busy = false, error = null, notice = null) }
        persist()
        if (old != null) viewModelScope.launch(Dispatchers.IO) { old.directory.deleteRecursively() }
    }
    fun format(value: ExportFormat) = invalidate { it.copy(format = value) }
    fun maximum(value: String) = invalidate { it.copy(maximum = value) }
    fun unit(value: SizeUnit) = invalidate { it.copy(unit = value) }
    fun grayscale(value: Boolean) = invalidate { it.copy(grayscale = value) }
    fun rotate(delta: Int) = invalidate { s -> s.copy(pages = s.pages.mapIndexed { i, p ->
        if (i == s.pageIndex) p.copy(rotation = (p.rotation + delta + 360) % 360) else p
    }) }

    fun importPages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val previous = job
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            _state.update { it.copy(busy = true, progress = "Importing pages…", error = null) }
            val directory = File(root, UUID.randomUUID().toString())
            withImportDirectory(directory) { commit ->
                try {
                    val pages = withContext(Dispatchers.IO) {
                        directory.mkdirs()
                        uris.mapIndexed { index, uri ->
                            ensureActive()
                            val file = File(directory, "source-$index")
                            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        total += n
                                        if (total > 150L * 1024 * 1024) throw IOException("A page is too large to safely import (over 150 MB). Choose a smaller image.")
                                        output.write(buffer, 0, n)
                                    }
                                }
                            } ?: throw IOException("Unable to read the selected image.")
                            val probe = ImageProcessor.decode(file, edge = 64)
                            probe.recycle()
                            SourcePage(file)
                        }
                    }
                    val old = state.value
                    revision++
                    _state.value = ScanState(pages = pages, folder = old.folder, screen = "preview")
                    commit()
                    persist()
                    withContext(NonCancellable + Dispatchers.IO) {
                        old.prepared?.directory?.deleteRecursively()
                        old.pages.mapNotNull { it.file.parentFile }.distinct().forEach { it.deleteRecursively() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message("Import failed: ${e.localizedMessage}") }
                catch (_: OutOfMemoryError) { message("This image is too large to process safely. Try fewer or smaller pages.") }
            }
        }
    }

    fun prepare() {
        val snapshot = state.value
        if (snapshot.pages.isEmpty() || snapshot.busy) return
        val limit = try { SizeLimit.parse(snapshot.maximum, snapshot.unit) } catch (e: IllegalArgumentException) { message(e.message.orEmpty()); return }
        val token = ++revision
        val previous = job
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            _state.update { it.copy(busy = true, prepared = null, error = null, progress = "Processing…") }
            val directory = File(root, "export-${UUID.randomUUID()}")
            try {
                val output = withContext(Dispatchers.IO) {
                    snapshot.prepared?.directory?.deleteRecursively()
                    ExportProcessor().prepare(snapshot.pages, snapshot.format, limit, snapshot.grayscale,
                        directory, exportBaseName()) { progress ->
                        if (token == revision) _state.update { it.copy(progress = progress) }
                    }
                }
                if (token == revision) _state.update { it.copy(prepared = output, busy = false) }
                else withContext(Dispatchers.IO) { output.directory.deleteRecursively() }
            } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }; throw e }
            catch (e: Exception) { if (token == revision) message(e.localizedMessage ?: "Processing failed.") }
            catch (_: OutOfMemoryError) {
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
                if (token == revision) message("Not enough memory for this page. Try a smaller image or fewer pages.")
            }
        }
    }

    fun selectFolder(uri: Uri?) {
        if (uri == null) return
        try {
            storage.rememberFolder(uri)
            storagePreferences.edit().putString("default_folder", uri.toString()).apply()
            _state.update { it.copy(folder = uri.toString()) }; persist()
        }
        catch (e: Exception) { message("Could not keep access to this folder: ${e.localizedMessage}") }
    }
    fun defaultFolder() {
        storagePreferences.edit().remove("default_folder").apply()
        _state.update { it.copy(folder = null) }; persist()
    }

    fun save() {
        val snapshot = state.value
        val prepared = snapshot.prepared ?: return
        if (snapshot.busy) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progress = "Saving and verifying files…") }
            try {
                val records = withContext(Dispatchers.IO) {
                    val records = storage.save(prepared, snapshot.folder?.let(Uri::parse))
                    try { dao.insert(records) }
                    catch (e: Exception) {
                        records.forEach { runCatching { storage.deleteUri(Uri.parse(it.uri)) } }
                        throw e
                    }
                    records
                }
                _state.update { it.copy(busy = false, notice = if (records.size == 1) "File saved" else "${records.size} files saved") }
            } catch (e: Exception) { message("Save failed. Check available storage and folder access. ${e.localizedMessage.orEmpty()}") }
        }
    }
    fun delete(record: ScanRecord, forgetOnly: Boolean = false) {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(record.uri)
                    if (!forgetOnly && storage.exists(uri)) storage.deleteUri(uri)
                    dao.delete(record)
                }
            } catch (e: Exception) { message("Could not delete this file: ${e.localizedMessage}. Delete it with Android's Files app, or remove only its history entry.") }
        }
    }
    fun fileAction(record: ScanRecord, share: Boolean, launch: (Uri, String, Boolean) -> Unit) {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                val uri = Uri.parse(record.uri)
                val exists = withContext(Dispatchers.IO) { storage.exists(uri) }
                if (!exists) { message("This file was moved or deleted. You can remove its history entry."); return@launch }
                val mime = ExportFormat.entries.firstOrNull { it.name == record.format }?.mime ?: "application/octet-stream"
                launch(uri, mime, share)
            } catch (e: Exception) { message("Could not access this file: ${e.localizedMessage}") }
        }
    }
    fun discard() {
        if (state.value.busy) return
        val old = state.value
        invalidate { ScanState(folder = it.folder) }
        viewModelScope.launch(Dispatchers.IO) { old.pages.mapNotNull { it.file.parentFile }.distinct().forEach { it.deleteRecursively() } }
    }
}
