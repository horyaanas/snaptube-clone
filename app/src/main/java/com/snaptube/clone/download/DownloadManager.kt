package com.snaptube.clone.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import com.snaptube.clone.data.model.DownloadStatus
import com.snaptube.clone.data.model.DownloadTask
import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.data.prefs.AppPreferences
import com.snaptube.clone.data.storage.DownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Real, resumable, multi-connection download engine.
 *
 * - Probes each URL for length and Range support.
 * - Splits large files into several parallel HTTP range requests (chunk files),
 *   so a paused download can resume from the exact byte position.
 * - Downloads video-only and audio-only tracks separately when required and
 *   merges them with [MediaMuxerUtil].
 * - Persists queue state so completed downloads survive app restarts.
 */
object DownloadManager {

    private const val CHUNK_MIN_SIZE = 2L * 1024 * 1024 // 2 MiB
    private const val HISTORY_FILE = "download_history.json"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            loadHistory()
        }
    }

    private fun downloadsDir(): File {
        val external = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (!AppPreferences.downloadDir.isBlank()) {
            File(AppPreferences.downloadDir)
        } else {
            external ?: File(appContext.filesDir, "downloads")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun tempDir(): File {
        val dir = File(downloadsDir(), ".tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun enqueue(video: VideoDetails, format: FormatOption): DownloadTask {
        val finalFile = uniqueTargetFile(video.title, format)
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = format,
            targetFilePath = finalFile.absolutePath,
            mimeType = mimeFor(format),
            status = DownloadStatus.QUEUED,
            sourceUrl = video.webpageUrl
        )
        _tasks.update { it + task }
        startTask(task.id)
        if (::appContext.isInitialized) {
            DownloadForegroundService.start(appContext)
        }
        return task
    }

    fun pauseTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) {
            if (it.status == DownloadStatus.COMPLETED) it
            else it.copy(status = DownloadStatus.PAUSED, downloadSpeedBps = 0)
        }
        saveHistory()
    }

    fun resumeTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
            updateTask(taskId) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
            startTask(taskId)
            if (::appContext.isInitialized) {
                DownloadForegroundService.start(appContext)
            }
        }
    }

    fun retryTask(taskId: String) = resumeTask(taskId)

    fun cancelTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val task = _tasks.value.firstOrNull { it.id == taskId }
        task?.let { deleteTaskArtifacts(it) }
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        saveHistory()
    }

    fun deleteTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val task = _tasks.value.firstOrNull { it.id == taskId }
        task?.let {
            deletePath(it.targetFilePath)
            deleteTaskArtifacts(it)
        }
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        saveHistory()
    }

    fun clearCompleted() {
        val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED }
        completed.forEach { task ->
            deletePath(task.targetFilePath)
            deleteTaskArtifacts(task)
        }
        _tasks.update { list -> list.filterNot { it.status == DownloadStatus.COMPLETED } }
        saveHistory()
    }

    private fun deletePath(path: String) {
        if (DownloadStorage.isContentUri(path)) {
            DownloadStorage.delete(appContext, path)
        } else {
            runCatching { File(path).delete() }
        }
    }

    fun rescanFile(path: String) {
        if (::appContext.isInitialized) {
            MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
        }
    }

    // ------------------------------------------------------------------
    // Download execution
    // ------------------------------------------------------------------

    private fun startTask(taskId: String) {
        if (activeJobs.containsKey(taskId)) return

        val job = scope.launch {
            val initial = _tasks.value.firstOrNull { it.id == taskId } ?: return@launch
            if (AppPreferences.wifiOnly && !isOnWifi()) {
                updateTask(taskId) {
                    it.copy(status = DownloadStatus.FAILED, errorMessage = "Wi-Fi required")
                }
                saveHistory()
                return@launch
            }

            updateTask(taskId) {
                it.copy(status = DownloadStatus.DOWNLOADING, downloadSpeedBps = 0, errorMessage = null)
            }

            val format = initial.format
            val finalFile = File(initial.targetFilePath)
            finalFile.parentFile?.mkdirs()
            val workingDir = tempDir()
            val baseName = finalFile.nameWithoutExtension

            try {
                val wantsMux = format.type == MediaType.VIDEO &&
                    format.requiresMux &&
                    !format.audioUrl.isNullOrBlank()

                if (wantsMux) {
                    downloadMuxed(taskId, format, baseName, workingDir, finalFile)
                } else {
                    downloadDirect(taskId, format, baseName, workingDir, finalFile)
                }

                rescanFile(finalFile.absolutePath)
                var savedPath = finalFile.absolutePath
                var savedSize = finalFile.length()
                var treeError: String? = null
                if (DownloadStorage.isEnabled()) {
                    try {
                        val treeUri = DownloadStorage.copyToTree(
                            appContext,
                            finalFile,
                            finalFile.name,
                            mimeFor(format)
                        )
                        if (treeUri != null) {
                            savedPath = treeUri.toString()
                            savedSize = DownloadStorage.sizeOf(appContext, savedPath)
                            finalFile.delete()
                        }
                    } catch (t: Throwable) {
                        // Keep the local copy when the SAF folder becomes unavailable.
                        treeError = t.message
                    }
                }
                updateTask(taskId) {
                    it.copy(
                        targetFilePath = savedPath,
                        status = DownloadStatus.COMPLETED,
                        progressPercent = 100,
                        downloadedBytes = savedSize,
                        totalBytes = savedSize,
                        downloadSpeedBps = 0,
                        errorMessage = treeError
                    )
                }
                saveHistory()
            } catch (c: CancellationException) {
                // Paused / cancelled: keep partial files for resume.
                updateTask(taskId) {
                    if (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED) {
                        it.copy(status = DownloadStatus.PAUSED, downloadSpeedBps = 0)
                    } else {
                        it
                    }
                }
                saveHistory()
            } catch (t: Throwable) {
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        downloadSpeedBps = 0,
                        errorMessage = t.message ?: "Download failed"
                    )
                }
                saveHistory()
            } finally {
                activeJobs.remove(taskId)
            }
        }

        activeJobs[taskId] = job
    }

    private suspend fun downloadDirect(
        taskId: String,
        format: FormatOption,
        baseName: String,
        workingDir: File,
        finalFile: File
    ) {
        val partFile = File(workingDir, "$baseName.${format.formatExtension}.part")
        val probe = probe(format.downloadUrl)
        val total = if (probe.length > 0) probe.length else 0L
        val tracker = ProgressTracker(total)
        tracker.add(partFile.let { if (it.exists()) it.length() else 0L })

        val ticker = startProgressTicker(taskId, tracker, total, "Downloading")
        try {
            downloadPart(format.downloadUrl, partFile, probe, tracker)
        } finally {
            ticker.cancel()
        }

        moveIntoPlace(partFile, finalFile)
    }

    private suspend fun downloadMuxed(
        taskId: String,
        format: FormatOption,
        baseName: String,
        workingDir: File,
        finalFile: File
    ) {
        val videoFile = File(workingDir, "$baseName.video.mp4.part")
        val audioFile = File(workingDir, "$baseName.audio.m4a.part")

        val videoProbe = probe(format.downloadUrl)
        val audioUrl = format.audioUrl!!
        val audioProbe = probe(audioUrl)
        val total = max(0L, videoProbe.length) + max(0L, audioProbe.length)
        val tracker = ProgressTracker(total)
        tracker.add(if (videoFile.exists()) videoFile.length() else 0L)
        tracker.add(if (audioFile.exists()) audioFile.length() else 0L)

        val ticker = startProgressTicker(taskId, tracker, total, "Downloading")

        try {
            // Download tracks sequentially: each one already uses several connections.
            downloadPart(format.downloadUrl, videoFile, videoProbe, tracker)
            downloadPart(audioUrl, audioFile, audioProbe, tracker)
        } finally {
            ticker.cancel()
        }

        updateTask(taskId) {
            it.copy(status = DownloadStatus.MERGING, progressPercent = 99)
        }

        val muxedFile = File(workingDir, "$baseName.muxed.mp4")
        withContext(Dispatchers.IO) {
            MediaMuxerUtil.mux(videoFile, audioFile, muxedFile)
        }
        moveIntoPlace(muxedFile, finalFile)

        videoFile.delete()
        audioFile.delete()
        clearChunkFiles(videoFile)
        clearChunkFiles(audioFile)
    }

    private suspend fun startProgressTicker(
        taskId: String,
        tracker: ProgressTracker,
        total: Long,
        phase: String
    ): Job {
        val parent = currentCoroutineContext()[Job]
        return CoroutineScope(Dispatchers.Default + (parent ?: SupervisorJob())).launch {
            var lastBytes = tracker.value.get()
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(300)
                val now = System.currentTimeMillis()
                val bytes = tracker.value.get()
                val elapsed = max(1L, now - lastTime)
                val speed = ((bytes - lastBytes) * 1000L) / elapsed
                lastBytes = bytes
                lastTime = now
                val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 99) else 0
                updateTask(taskId) {
                    it.copy(
                        downloadedBytes = bytes,
                        totalBytes = if (total > 0) total else it.totalBytes,
                        progressPercent = percent,
                        downloadSpeedBps = speed.coerceAtLeast(0)
                    )
                }
            }
        }
    }

    /**
     * Downloads [url] into [dest], resuming from existing bytes and splitting
     * large payloads into parallel range requests.
     */
    private suspend fun downloadPart(
        url: String,
        dest: File,
        probe: ProbeResult,
        tracker: ProgressTracker
    ) {
        dest.parentFile?.mkdirs()
        val length = probe.length
        val threads = AppPreferences.downloadThreads
        val useChunks = probe.acceptRanges &&
            length >= CHUNK_MIN_SIZE &&
            threads > 1

        if (useChunks) {
            downloadChunked(url, dest, length, threads, tracker)
        } else {
            downloadSingleStream(url, dest, probe, tracker)
        }
    }

    private suspend fun downloadChunked(
        url: String,
        dest: File,
        length: Long,
        threads: Int,
        tracker: ProgressTracker
    ) {
        val chunkCount = min(threads, 6)
        val chunkFiles = (0 until chunkCount).map { i ->
            File(dest.parentFile, "${dest.name}.chunk$i")
        }

        // If the previous layout used a different chunk count, start over.
        val existingChunks = chunkFiles.filter { it.exists() }
        if (existingChunks.isNotEmpty() && existingChunks.size != chunkCount) {
            chunkFiles.forEach { it.delete() }
        }

        val chunkSize = length / chunkCount
        coroutineScope {
            (0 until chunkCount).map { index ->
                val start = index * chunkSize
                val end = if (index == chunkCount - 1) length - 1 else (index + 1) * chunkSize - 1
                val chunkFile = chunkFiles[index]
                val expected = end - start + 1
                var already = if (chunkFile.exists()) chunkFile.length() else 0L
                if (already > expected) {
                    chunkFile.delete()
                    already = 0L
                }
                if (already > 0) tracker.add(already)

                async {
                    downloadRange(url, chunkFile, start + already, end, already, tracker)
                }
            }.awaitAll()
        }

        // Concatenate chunks into the destination part file.
        if (dest.exists()) dest.delete()
        FileOutputStream(dest).use { out ->
            chunkFiles.forEach { chunk ->
                chunk.inputStream().use { input -> input.copyTo(out) }
            }
        }
        chunkFiles.forEach { it.delete() }
    }

    private suspend fun downloadRange(
        url: String,
        file: File,
        rangeStart: Long,
        rangeEnd: Long,
        fileOffset: Long,
        tracker: ProgressTracker
    ) {
        if (rangeStart > rangeEnd) return

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 206) {
                throw IOException("Server rejected range request (${response.code})")
            }
            val body = response.body ?: throw IOException("Empty response body")

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(fileOffset)
                raf.setLength(fileOffset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        tracker.add(read.toLong())
                    }
                }
            }
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        dest: File,
        probe: ProbeResult,
        tracker: ProgressTracker
    ) {
        var existing = if (dest.exists()) dest.length() else 0L
        if (probe.length > 0 && existing >= probe.length) return

        val builder = Request.Builder().url(url)
        if (existing > 0 && probe.acceptRanges) {
            builder.header("Range", "bytes=$existing-")
        } else if (existing > 0) {
            dest.delete()
            tracker.add(-existing)
            existing = 0L
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")

            // Server ignored our Range header: restart from zero.
            if (existing > 0 && response.code == 200) {
                dest.delete()
                tracker.add(-existing)
                existing = 0L
            }

            FileOutputStream(dest, existing > 0).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        tracker.add(read.toLong())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Probing / helpers
    // ------------------------------------------------------------------

    private data class ProbeResult(
        val length: Long,
        val acceptRanges: Boolean
    )

    private fun probe(url: String): ProbeResult {
        // HEAD is cheapest where supported.
        try {
            val head = Request.Builder().url(url).head().build()
            client.newCall(head).execute().use { response ->
                if (response.isSuccessful) {
                    val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val ranges = response.header("Accept-Ranges")
                        ?.contains("bytes", ignoreCase = true) == true
                    if (length > 0 && ranges) return ProbeResult(length, true)
                    if (length > 0) {
                        // Some CDNs only advertise ranges on GET; verify below.
                        val verified = verifyRangeSupport(url)
                        return ProbeResult(length, verified)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Fall back to a one-byte ranged GET.
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range")
                val length = contentRange?.substringAfterLast('/', "-1")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                ProbeResult(max(length, -1L), response.code == 206)
            }
        } catch (_: Exception) {
            ProbeResult(-1L, false)
        }
    }

    private fun verifyRangeSupport(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { it.code == 206 }
        } catch (_: Exception) {
            false
        }
    }

    private fun moveIntoPlace(source: File, target: File) {
        if (!source.exists()) throw IOException("Downloaded file is missing")
        if (target.exists()) target.delete()
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            source.delete()
        }
        if (!target.exists() || target.length() == 0L) {
            throw IOException("Saved file is empty")
        }
    }

    private fun clearChunkFiles(dest: File) {
        val parent = dest.parentFile ?: return
        parent.listFiles { file -> file.name.startsWith("${dest.name}.chunk") }
            ?.forEach { it.delete() }
    }

    private fun deleteTaskArtifacts(task: DownloadTask) {
        if (DownloadStorage.isContentUri(task.targetFilePath)) return
        val finalFile = File(task.targetFilePath)
        val workingDir = tempDir()
        val baseName = finalFile.nameWithoutExtension
        workingDir.listFiles { file -> file.name.startsWith(baseName) }?.forEach { it.delete() }
        clearChunkFiles(File(workingDir, "$baseName.${task.format.formatExtension}.part"))
        clearChunkFiles(File(workingDir, "$baseName.video.mp4.part"))
        clearChunkFiles(File(workingDir, "$baseName.audio.m4a.part"))
    }

    private fun uniqueTargetFile(title: String, format: FormatOption): File {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_")
            .trim()
            .take(80)
            .ifBlank { "SnapTube" }
        val suffix = format.qualityLabel.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val extension = format.formatExtension.ifBlank { if (format.isAudio) "m4a" else "mp4" }
        var file = File(downloadsDir(), "$safeTitle${if (suffix.isBlank()) "" else " [$suffix]"}.$extension")
        var counter = 2
        while (file.exists()) {
            file = File(downloadsDir(), "$safeTitle${if (suffix.isBlank()) "" else " [$suffix]"} ($counter).$extension")
            counter++
        }
        return file
    }

    private fun mimeFor(format: FormatOption): String = when {
        format.type == MediaType.SUBTITLE -> "text/vtt"
        format.type == MediaType.AUDIO -> "audio/mp4"
        else -> "video/mp4"
    }

    private fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun updateTask(taskId: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) block(it) else it } }
    }

    private class ProgressTracker(total: Long) {
        val value = AtomicLong(0)
        init {
            // total is kept for reference; individual callers compute percentages.
            if (total < 0) value.set(0)
        }
        fun add(delta: Long) {
            if (delta == 0L) return
            value.addAndGet(delta)
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Serialised download history, used by the backup/restore feature. */
    fun exportHistoryJson(): String = runCatching {
        if (::appContext.isInitialized) historyToJson().toString() else "[]"
    }.getOrDefault("[]")

    /**
     * Merges a previously exported history into the current queue. Existing
     * entries with the same id win so active downloads are never clobbered.
     */
    fun importHistory(json: String) {
        if (!::appContext.isInitialized) return
        try {
            val imported = JSONArray(json)
            val merged = historyToJson()
            val knownIds = buildSet {
                for (i in 0 until merged.length()) {
                    merged.optJSONObject(i)?.optString("id")?.let { add(it) }
                }
            }
            for (i in 0 until imported.length()) {
                val obj = imported.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotBlank() && id !in knownIds) {
                    merged.put(obj)
                }
            }
            File(appContext.filesDir, HISTORY_FILE).writeText(merged.toString())
            loadHistory()
        } catch (_: Exception) {
        }
    }

    private fun historyToJson(): JSONArray {
        val array = JSONArray()
        _tasks.value.forEach { task ->
                val obj = JSONObject()
                obj.put("id", task.id)
                obj.put("title", task.title)
                obj.put("author", task.author)
                obj.put("thumbnail", task.thumbnailUrl)
                obj.put("path", task.targetFilePath)
                obj.put("status", task.status.name)
                obj.put("total", task.totalBytes)
                obj.put("downloaded", task.downloadedBytes)
                obj.put("progress", task.progressPercent)
                obj.put("mime", task.mimeType)
                obj.put("created", task.createdAt)
                obj.put("error", task.errorMessage ?: "")
                obj.put("sourceUrl", task.sourceUrl)

                val f = JSONObject()
                f.put("id", task.format.id)
                f.put("type", task.format.type.name)
                f.put("quality", task.format.qualityLabel)
                f.put("subtitle", task.format.subtitle)
                f.put("ext", task.format.formatExtension)
                f.put("size", task.format.estimatedSizeBytes)
                f.put("url", task.format.downloadUrl)
                f.put("audioUrl", task.format.audioUrl ?: "")
                f.put("hd", task.format.isHd)
                f.put("bitrate", task.format.bitrateKbps)
                f.put("resolution", task.format.resolution)
                f.put("mux", task.format.requiresMux)
                f.put("container", task.format.container)
                f.put("audioLanguage", task.format.audioLanguage)
                obj.put("format", f)

            array.put(obj)
        }
        return array
    }

    private fun saveHistory() {
        if (!::appContext.isInitialized) return
        runCatching {
            File(appContext.filesDir, HISTORY_FILE).writeText(historyToJson().toString())
        }
    }

    private fun loadHistory() {
        try {
            val file = File(appContext.filesDir, HISTORY_FILE)
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            val loaded = mutableListOf<DownloadTask>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val path = obj.optString("path")
                if (path.isBlank()) continue
                if (!DownloadStorage.isContentUri(path) && !File(path).exists()) continue

                val f = obj.optJSONObject("format") ?: continue
                val format = FormatOption(
                    id = f.optString("id"),
                    type = runCatching { MediaType.valueOf(f.optString("type")) }.getOrDefault(MediaType.VIDEO),
                    qualityLabel = f.optString("quality"),
                    subtitle = f.optString("subtitle"),
                    formatExtension = f.optString("ext"),
                    estimatedSizeBytes = f.optLong("size"),
                    downloadUrl = f.optString("url"),
                    audioUrl = f.optString("audioUrl").ifBlank { null },
                    isHd = f.optBoolean("hd"),
                    bitrateKbps = f.optInt("bitrate"),
                    resolution = f.optString("resolution"),
                    requiresMux = f.optBoolean("mux"),
                    container = f.optString("container"),
                    audioLanguage = f.optString("audioLanguage")
                )
                val savedStatus = runCatching { DownloadStatus.valueOf(obj.optString("status")) }
                    .getOrDefault(DownloadStatus.COMPLETED)
                val status = if (savedStatus == DownloadStatus.COMPLETED) savedStatus else DownloadStatus.PAUSED

                loaded += DownloadTask(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title"),
                    author = obj.optString("author"),
                    thumbnailUrl = obj.optString("thumbnail"),
                    format = format,
                    targetFilePath = path,
                    totalBytes = obj.optLong("total"),
                    downloadedBytes = obj.optLong("downloaded"),
                    progressPercent = if (status == DownloadStatus.COMPLETED) 100 else obj.optInt("progress"),
                    status = status,
                    mimeType = obj.optString("mime", mimeFor(format)),
                    createdAt = obj.optLong("created", System.currentTimeMillis()),
                    errorMessage = obj.optString("error").ifBlank { null },
                    sourceUrl = obj.optString("sourceUrl")
                )
            }
            _tasks.value = loaded
        } catch (_: Exception) {
        }
    }
}
