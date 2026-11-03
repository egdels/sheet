// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

@file:androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)

package de.schliweb.sheet

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.schliweb.sheet.camera.toUpright
import de.schliweb.sheet.core.PageSize
import de.schliweb.sheet.core.Point
import de.schliweb.sheet.core.Quad
import de.schliweb.sheet.core.corners.CornerDetector
import de.schliweb.sheet.core.corners.EdgeSnap
import de.schliweb.sheet.core.corners.LiveTracker
import de.schliweb.sheet.core.document.Document
import de.schliweb.sheet.core.document.DocumentStore
import de.schliweb.sheet.core.image.Enhance
import de.schliweb.sheet.core.ocr.OcrEngine
import de.schliweb.sheet.core.ocr.RecModel
import de.schliweb.sheet.core.pdf.PdfPage
import de.schliweb.sheet.core.pdf.SearchablePdf
import de.schliweb.sheet.corners.BitmapPageImage
import de.schliweb.sheet.corners.OrtDocQuadModel
import de.schliweb.sheet.crop.warp
import de.schliweb.sheet.export.SaveFolder
import de.schliweb.sheet.ocr.AssetOcrModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class LiveOutline(val quad: Quad, val transform: OutputTransform, val far: Boolean = false)

data class Saved(val name: String, val folder: String, val uri: Uri?)

sealed interface Screen {
    data object Camera : Screen

    data class Crop(val photo: Bitmap, val quad: Quad, val found: Boolean, val editing: String? = null, val id: String = editing ?: "") : Screen

    data object Pages : Screen
}

class SheetViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private val modelLoader = lazy { OrtDocQuadModel(application) }
    private val detector by lazy { CornerDetector(modelLoader.value) }
    private val tracker = LiveTracker()
    private val ocrModels = lazy {
        val memory = ActivityManager.MemoryInfo().also { application.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        AssetOcrModels(application, arena = memory.totalMem >= ARENA_FROM_BYTES)
    }
    private val ocrLoader = lazy { OcrEngine(ocrModels.value, readers = OCR_READERS) }
    private val ocr by ocrLoader
    private val store = DocumentStore(File(application.filesDir, "document"))

    private val ocrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val ocrQueue = Channel<String>(Channel.UNLIMITED)
    private val transforms = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
        isUsingCropRect = false
    }

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _screen = MutableStateFlow<Screen>(Screen.Camera)
    val screen: StateFlow<Screen> = _screen

    private val _live = MutableStateFlow<LiveOutline?>(null)
    val live: StateFlow<LiveOutline?> = _live

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _document = MutableStateFlow(Document())
    val document: StateFlow<Document> = _document

    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread

    @Volatile private var photoWrite: Job? = null
    @Volatile private var capturing: String? = null

    private val pending = MutableStateFlow<Map<String, Int>>(emptyMap())
    val reading: StateFlow<Set<String>> = pending.map { it.keys }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val saveFolder = SaveFolder(application)

    private val _folder = MutableStateFlow(saveFolder.uri?.let { it to "" })
    val folder: StateFlow<Pair<Uri, String>?> = _folder

    private val _failures = Channel<Int>(Channel.BUFFERED)
    val failures: Flow<Int> = _failures.receiveAsFlow()

    private val documentLock = Mutex()

    private val loaded = CompletableDeferred<Unit>()

    private val _saved = MutableStateFlow<Saved?>(null)
    val saved: StateFlow<Saved?> = _saved

    private val _enhanced = MutableStateFlow(0)
    val enhanced: StateFlow<Int> = _enhanced

    private val thumbnails = LruCache<String, Bitmap>(20)

    @Volatile private var analyzing = true
    private var lastAnalysis = 0L

    private var far = false

    private val savedScreen = SavedScreen(
        state.get<String>(KEY_SCREEN),
        state.get<String>(KEY_CROP_ID),
        state.get<Boolean>(KEY_CROP_EDITING) == true,
        state.get<Boolean>(KEY_CROP_FOUND) == true,
        state.get<DoubleArray>(KEY_CROP_QUAD),
    )

    private class SavedScreen(val kind: String?, val cropId: String?, val editing: Boolean, val found: Boolean, val quad: DoubleArray?)

    init {
        _folder.value?.let { (uri, _) -> viewModelScope.launch { updateFolderName(uri) } }
        viewModelScope.launch {
            val doc = try {
                withContext(Dispatchers.IO) { store.load() }
            } catch (e: Exception) {
                Log.w(TAG, "could not load the document", e)
                Document()
            }
            documentLock.withLock { _document.value = doc }
            loaded.complete(Unit)
            val restored = restoreScreen(doc, savedScreen)
            documentLock.withLock {
                val keep = _document.value.pages.map { it.id }.toSet() + listOfNotNull(restored, capturing, (_screen.value as? Screen.Crop)?.id)
                withContext(Dispatchers.IO) { store.prune(keep) }
            }
            val unread = withContext(Dispatchers.IO) { doc.pages.filter { store.text(it) == null || !store.image(it.id).exists() } }
            unread.forEach { queueOcr(it.id) }
        }
        viewModelScope.launch {
            _screen.collect { s ->
                state[KEY_SCREEN] = when (s) {
                    Screen.Camera -> "camera"
                    Screen.Pages -> "pages"
                    is Screen.Crop -> "crop"
                }
                if (s is Screen.Crop) {
                    state[KEY_CROP_ID] = s.id
                    state[KEY_CROP_EDITING] = s.editing != null
                    state[KEY_CROP_FOUND] = s.found
                    state[KEY_CROP_QUAD] = s.quad.corners.flatMap { listOf(it.x, it.y) }.toDoubleArray()
                }
            }
        }
        viewModelScope.launch(ocrDispatcher) {
            for (id in ocrQueue) {
                readPage(id)
                if (pending.value.isEmpty()) releaseLater(RELEASE_AFTER_MS)
            }
        }
    }

    val analyzer = ImageAnalysis.Analyzer { image -> image.use(::analyze) }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (!analyzing || now - lastAnalysis < ANALYSIS_INTERVAL_MS) return
        lastAnalysis = now
        val frame = image.toUpright(CornerDetector.DETECTION_MAX_EDGE)
        val detection = try {
            detector.detectFrame(BitmapPageImage(frame.bitmap))
        } catch (e: Exception) {
            Log.w(TAG, "live detection failed", e)
            null
        }
        val quad = tracker.update(detection, frame.bitmap.width, frame.bitmap.height, now)
        if (quad != null) {
            val (pw, ph) = PageSize.of(quad, frame.bitmap.width, frame.bitmap.height)
            val share = max(pw, ph).toDouble() / max(frame.bitmap.width, frame.bitmap.height)
            far = if (far) share < FAR_OFF else share < FAR_ON
        }
        _live.value = quad?.let { q -> LiveOutline(q.map { Point(it.x / frame.scale, it.y / frame.scale) }, transforms.getOutputTransform(image), far) }
    }

    fun onCaptured(image: ImageProxy) {
        analyzing = false
        _live.value = null
        val id = UUID.randomUUID().toString()
        capturing = id
        val started = busyWhile(onFailure = { analyzing = true }) {
            val crop = withContext(Dispatchers.Default) {
                val photo = image.use { it.toUpright().bitmap }
                val page = BitmapPageImage(photo)
                val t0 = SystemClock.uptimeMillis()
                val outline = detector.detectStill(page)
                val t1 = SystemClock.uptimeMillis()
                val quad = outline?.let { EdgeSnap.refine(page, it.quad.clampedTo(photo.width, photo.height)) }
                Log.i(TAG, "still: ${photo.width}x${photo.height} ${photo.config}, confidence ${outline?.confidence}, pass ${outline?.angle}, detect ${t1 - t0} ms, snap ${SystemClock.uptimeMillis() - t1} ms")
                Screen.Crop(photo, quad ?: Quad.full(photo.width, photo.height, inset = 0.1), found = quad != null, id = id)
            }
            photoWrite = viewModelScope.launch(Dispatchers.IO) {
                try {
                    writeJpeg(crop.photo, store.photo(id))
                } catch (e: Exception) {
                    Log.w(TAG, "could not write the photo", e)
                }
            }
            _screen.value = crop
        }
        if (!started) {
            image.close()
            analyzing = true
        }
    }

    fun onCaptureFailed() {
        analyzing = true
    }

    fun moveCorner(index: Int, p: Point) {
        val crop = _screen.value as? Screen.Crop ?: return
        val inside = Point(p.x.coerceIn(0.0, crop.photo.width.toDouble()), p.y.coerceIn(0.0, crop.photo.height.toDouble()))
        _screen.value = crop.copy(quad = crop.quad.withCorner(index, inside))
    }

    fun usePage() {
        val dragged = _screen.value as? Screen.Crop ?: return
        val crop = dragged.copy(quad = dragged.quad.ordered())
        if (!crop.quad.isPlausibleIn(crop.photo.width, crop.photo.height)) return
        busyWhile {
            val editing = crop.editing
            if (editing == null) {
                val id = crop.id
                withContext(Dispatchers.Default) {
                    writeJpeg(warp(crop.photo, crop.quad), store.plain(id), PLAIN_QUALITY)
                    photoWrite?.join()
                    if (!store.photo(id).exists()) writeJpeg(crop.photo, store.photo(id))
                }
                updateDocument { it.add(id, crop.quad) }
                queueOcr(id)
                backToCamera()
            } else {
                val page = _document.value.pages.find { it.id == editing } ?: return@busyWhile
                withContext(Dispatchers.Default) {
                    writeJpeg(warp(crop.photo, crop.quad), store.plain(page.id), PLAIN_QUALITY)
                    store.image(page.id).delete()
                }
                updateDocument { it.recut(editing, crop.quad) }
                queueOcr(editing)
                _screen.value = Screen.Pages
            }
        }
    }

    fun editPage(id: String) {
        val page = _document.value.pages.find { it.id == id } ?: return
        busyWhile {
            val photo = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.photo(id).path) } ?: return@busyWhile
            _screen.value = Screen.Crop(photo, page.quad ?: Quad.full(photo.width, photo.height, inset = 0.1), found = true, editing = id)
        }
    }

    fun cancelCrop() {
        val crop = _screen.value as? Screen.Crop ?: return
        if (crop.editing != null) {
            _screen.value = Screen.Pages
        } else {
            val write = photoWrite
            viewModelScope.launch(Dispatchers.IO) {
                write?.join()
                store.delete(crop.id)
            }
            backToCamera()
        }
    }

    private suspend fun restoreScreen(doc: Document, saved: SavedScreen): String? {
        if (_screen.value != Screen.Camera) return null
        when (saved.kind) {
            "pages" -> if (!doc.isEmpty) showPages()
            "crop" -> {
                val id = saved.cropId ?: return null
                val editing = saved.editing
                if (editing != doc.pages.any { it.id == id }) return null
                val c = saved.quad?.takeIf { it.size == 8 } ?: return null
                val photo = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(store.photo(id).path) }.getOrNull() } ?: return null
                if (_screen.value != Screen.Camera) return null
                val quad = Quad(Point(c[0], c[1]), Point(c[2], c[3]), Point(c[4], c[5]), Point(c[6], c[7]))
                analyzing = false
                _screen.value = Screen.Crop(photo, quad, saved.found, editing = if (editing) id else null, id = id)
                return id
            }
        }
        return null
    }

    fun showPages() {
        _screen.value = Screen.Pages
        analyzing = false
        _live.value = null
    }

    fun backToCamera() {
        tracker.reset()
        _screen.value = Screen.Camera
        analyzing = true
    }

    fun movePage(id: String, index: Int) {
        viewModelScope.launch {
            try {
                updateDocument { it.move(id, index) }
            } catch (e: Exception) {
                failed("move failed", e)
            }
        }
    }

    fun deletePage(id: String) {
        viewModelScope.launch {
            try {
                updateDocument { it.remove(id) }
                withContext(Dispatchers.IO) { store.delete(id) }
                if (_document.value.isEmpty && _screen.value == Screen.Pages) backToCamera()
            } catch (e: Exception) {
                failed("delete failed", e)
            }
        }
    }

    fun readAgain(id: String) {
        queueOcr(id)
    }

    fun turnPage(id: String) {
        busyWhile {
            updateDocument { it.turned(id, 1) }
        }
    }

    fun discardDocument() {
        viewModelScope.launch {
            clearDocument()
            backToCamera()
        }
    }

    fun pickFolderAndSave(tree: Uri) {
        if (pickFolder(tree)) savePdf()
    }

    fun pickFolder(tree: Uri): Boolean {
        try {
            saveFolder.set(tree)
        } catch (e: SecurityException) {
            failed("folder not granted", e, R.string.folder_failed)
            return false
        }
        _folder.value = tree to ""
        viewModelScope.launch { updateFolderName(tree) }
        return true
    }

    private suspend fun updateFolderName(tree: Uri) {
        val name = withContext(Dispatchers.IO) { saveFolder.displayName(tree) } ?: return
        _folder.update { f -> if (f?.first == tree) tree to name else f }
    }

    fun savePdf() {
        val tree = _folder.value?.first ?: return
        busyWhile {
            pending.first { it.isEmpty() }
            val doc = _document.value
            if (doc.isEmpty) return@busyWhile
            val name = pdfName()
            val folderName = _folder.value?.second?.ifEmpty { null } ?: withContext(Dispatchers.IO) { saveFolder.displayName(tree) } ?: ""
            val result = withContext(Dispatchers.IO) {
                runCatching { saveFolder.writePdf(tree, name, doc.pages.size) { i -> pdfPage(doc.pages[i]) } }
            }
            result.onSuccess { uri ->
                val actual = withContext(Dispatchers.IO) { saveFolder.documentName(uri) } ?: name
                Log.i(TAG, "saved ${doc.pages.size} pages as $actual")
                _saved.value = Saved(actual, folderName, uri)
                clearDocument()
                backToCamera()
            }.onFailure { e ->
                Log.w(TAG, "save failed", e)
                _saved.value = Saved(name, folderName, null)
                val usable = withContext(Dispatchers.IO) { saveFolder.uri?.takeIf { saveFolder.exists(it) } }
                if (usable == null) {
                    withContext(Dispatchers.IO) { saveFolder.forget() }
                    _folder.value = null
                }
            }
        }
    }

    fun savedShown() {
        _saved.value = null
    }

    private val _share = MutableStateFlow<Uri?>(null)
    val share: StateFlow<Uri?> = _share

    fun sharePdf() {
        busyWhile {
            pending.first { it.isEmpty() }
            val doc = _document.value
            if (doc.isEmpty) return@busyWhile
            val app = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, SHARE_DIR)
                dir.deleteRecursively()
                dir.mkdirs()
                val file = File(dir, pdfName())
                file.outputStream().buffered().use { out ->
                    SearchablePdf.write(out, doc.pages.size, { i -> pdfPage(doc.pages[i]) })
                }
                FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            }
            _share.value = uri
        }
    }

    fun shareShown() {
        _share.value = null
    }

    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutter: SharedFlow<Unit> = _shutter

    fun onShutterKey(): Boolean {
        if (_screen.value != Screen.Camera || _busy.value || _shutter.subscriptionCount.value == 0) return false
        _shutter.tryEmit(Unit)
        return true
    }

    private fun pdfName() = "Scan " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT)) + ".pdf"

    private fun pdfPage(page: Document.Page): PdfPage {
        val text = store.text(page)
        return PdfPage(pageImage(page.id).readBytes(), text?.text, page.turns, text?.turns ?: page.turns)
    }

    private fun pageImage(id: String): File = store.image(id).takeIf { it.exists() } ?: store.plain(id)

    suspend fun thumbnail(page: Document.Page): Bitmap? {
        val file = pageImage(page.id)
        val key = "${page.id}:${page.revision}:${file.name}"
        thumbnails.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_EDGE) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { turn(it, page.turns) }
        }?.also { thumbnails.put(key, it) }
    }

    private fun queueOcr(id: String) {
        release?.cancel()
        _unread.update { it - id }
        pending.update { it + (id to (it[id] ?: 0) + 1) }
        ocrQueue.trySend(id)
    }

    @Volatile private var release: Job? = null

    private fun releaseLater(delayMs: Long) {
        release?.cancel()
        release = viewModelScope.launch(ocrDispatcher) {
            delay(delayMs)
            if (pending.value.isEmpty() && ocrModels.isInitialized()) {
                ocrModels.value.release()
                Log.i(TAG, "ocr models released")
            }
        }
    }

    fun onTrimMemory() {
        if (pending.value.isEmpty()) releaseLater(0)
    }

    private suspend fun readPage(id: String) {
        try {
            val page = _document.value.pages.find { it.id == id } ?: return
            try {
                enhance(id)
            } catch (e: Exception) {
                Log.w(TAG, "enhance failed for $id", e)
            }
            if (store.text(page) != null) return
            val bitmap = BitmapFactory.decodeFile(store.plain(id).path)?.let { turn(it, page.turns) }
            if (bitmap == null) {
                _unread.update { it + id }
                return
            }
            val locales = LocaleList.getDefault()
            val languages = RecModel.forLocales(List(locales.size()) { locales[it] })
            val t0 = SystemClock.uptimeMillis()
            val upright = ocr.recognizeUpright(BitmapPageImage(bitmap), languages)
            val quarters = upright.degrees / 90
            Log.i(TAG, "ocr: ${bitmap.width}x${bitmap.height}, ${upright.text.words.size} words, upright by ${upright.degrees}, ${SystemClock.uptimeMillis() - t0} ms")
            if (quarters != 0 && page.revision == 0) {
                var turned = false
                updateDocument { d ->
                    if (d.pages.find { it.id == id }?.revision != page.revision) return@updateDocument d
                    turned = true
                    d.turned(id, quarters)
                }
                if (turned) Log.i(TAG, "turned $id by ${upright.degrees} degrees")
            }
            if (_document.value.pages.find { it.id == id }?.cut == page.cut) store.saveText(page, upright.text, page.turns + quarters)
        } catch (e: Exception) {
            Log.w(TAG, "ocr failed for $id", e)
            _unread.update { it + id }
        } finally {
            if (_document.value.pages.none { it.id == id }) store.delete(id)
            pending.update { m ->
                val left = (m[id] ?: 1) - 1
                if (left <= 0) m - id else m + (id to left)
            }
            if (id !in pending.value) {
                val page = _document.value.pages.find { it.id == id }
                if (page == null || store.text(page) != null) _unread.update { it - id }
            }
        }
    }

    private suspend fun updateDocument(change: (Document) -> Document) {
        loaded.await()
        documentLock.withLock { changeDocument(change) }
    }

    private suspend fun changeDocument(change: (Document) -> Document) {
        val old = _document.value
        val doc = change(old)
        if (doc == old) return
        _document.value = doc
        withContext(Dispatchers.IO) { store.save(doc) }
    }

    private suspend fun clearDocument() {
        loaded.await()
        documentLock.withLock {
            _document.value = Document()
            _unread.value = emptySet()
            withContext(Dispatchers.IO) {
                store.clear()
                File(getApplication<Application>().cacheDir, SHARE_DIR).deleteRecursively()
            }
        }
        thumbnails.evictAll()
    }

    private fun turn(bitmap: Bitmap, quarters: Int): Bitmap {
        if (quarters.mod(4) == 0) return bitmap
        val m = Matrix().apply { postRotate(90f * quarters.mod(4)) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun enhance(id: String) {
        if (store.image(id).exists()) return
        val t0 = SystemClock.uptimeMillis()
        val plain = store.plain(id)
        val cut = plain.lastModified()
        val page = BitmapFactory.decodeFile(plain.path, BitmapFactory.Options().apply { inMutable = true }) ?: return
        val t1 = SystemClock.uptimeMillis()
        val px = IntArray(page.width * page.height)
        page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
        Enhance.apply(px, page.width, page.height)
        val t2 = SystemClock.uptimeMillis()
        page.setPixels(px, 0, page.width, 0, 0, page.width, page.height)
        if (plain.lastModified() != cut) return
        writeJpeg(page, store.image(id))
        Log.i(TAG, "enhance: ${page.width}x${page.height}, decode ${t1 - t0} ms, enhance ${t2 - t1} ms, encode ${SystemClock.uptimeMillis() - t2} ms")
        _enhanced.update { it + 1 }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int = JPEG_QUALITY) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "could not write $file" }
    }

    private fun busyWhile(onFailure: () -> Unit = {}, work: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        _busy.value = true
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("failed", e)
                onFailure()
            } catch (e: OutOfMemoryError) {
                failed("out of memory", e)
                onFailure()
            } finally {
                _busy.value = false
            }
        }
        return true
    }

    private fun failed(what: String, e: Throwable, message: Int = R.string.something_failed) {
        Log.w(TAG, what, e)
        _failures.trySend(message)
    }

    override fun onCleared() {
        analysisExecutor.execute { if (modelLoader.isInitialized()) modelLoader.value.close() }
        analysisExecutor.shutdown()
        ocrQueue.close()
        if (ocrModels.isInitialized()) {
            CoroutineScope(ocrDispatcher).launch {
                if (ocrLoader.isInitialized()) ocr.close()
                ocrModels.value.close()
            }
        }
    }

    private companion object {
        const val TAG = "SheetViewModel"
        const val ANALYSIS_INTERVAL_MS = 200L
        const val JPEG_QUALITY = 90
        const val PLAIN_QUALITY = 100
        const val OCR_READERS = 4
        const val ARENA_FROM_BYTES = 6L * 1024 * 1024 * 1024
        const val RELEASE_AFTER_MS = 30_000L
        const val FAR_ON = 0.5
        const val FAR_OFF = 0.58
        const val THUMBNAIL_EDGE = 480
        const val SHARE_DIR = "share"
        const val KEY_SCREEN = "screen"
        const val KEY_CROP_ID = "cropId"
        const val KEY_CROP_EDITING = "cropEditing"
        const val KEY_CROP_FOUND = "cropFound"
        const val KEY_CROP_QUAD = "cropQuad"
    }
}
