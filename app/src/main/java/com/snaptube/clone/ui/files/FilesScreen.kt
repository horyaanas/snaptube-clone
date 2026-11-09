package com.snaptube.clone.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.snaptube.clone.R
import com.snaptube.clone.data.extractor.MediaRepository
import com.snaptube.clone.data.model.DownloadStatus
import com.snaptube.clone.data.model.DownloadTask
import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.storage.DownloadStorage
import com.snaptube.clone.download.DownloadManager
import com.snaptube.clone.media.Mp3Transcoder
import com.snaptube.clone.ui.theme.SnapYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MediaFile(
    val path: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val isAudio: Boolean,
    val thumbnail: String,
    val task: DownloadTask?
) {
    val extension: String
        get() = fileName.substringAfterLast('.', "").lowercase()
}

private enum class FileTab(val labelRes: Int) {
    VIDEOS(R.string.download_as_video),
    AUDIO(R.string.download_as_music),
    ACTIVE(R.string.downloading),
    HISTORY(R.string.history)
}

@Composable
fun FilesScreen(
    onPlay: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var mediaFiles by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var convertingTitle by remember { mutableStateOf<String?>(null) }
    val tasks by DownloadManager.tasks.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(tasks, refreshKey) {
        scanning = true
        mediaFiles = withContext(Dispatchers.IO) {
            buildMediaFiles(context, tasks)
        }
        scanning = false
    }

    fun convertToMp3(file: MediaFile) {
        if (convertingTitle != null) return
        convertingTitle = file.title
        scope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) { convertFileToMp3(context, file) }
            }.isSuccess
            convertingTitle = null
            Toast.makeText(
                context,
                if (success) R.string.conversion_done else R.string.conversion_failed,
                Toast.LENGTH_SHORT
            ).show()
            refreshKey++
        }
    }

    fun copyLink(task: DownloadTask?) {
        val link = task?.sourceUrl.orEmpty().ifBlank {
            task?.format?.downloadUrl.orEmpty()
        }
        if (link.isBlank()) {
            Toast.makeText(context, R.string.no_link, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("SnapTube", link))
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    fun reDownload(task: DownloadTask) {
        val source = task.sourceUrl.ifBlank { task.format.downloadUrl }
        if (source.isBlank()) {
            Toast.makeText(context, R.string.no_link, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val result = MediaRepository.extract(source)
            result.onSuccess { details ->
                val replacement = pickReplacementFormat(details, task.format)
                if (replacement != null) {
                    DownloadManager.enqueue(details, replacement)
                    Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                // The original direct URL may still be valid for non-YouTube sites.
                runCatching {
                    val fallback = task.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        targetFilePath = "",
                        downloadedBytes = 0L,
                        progressPercent = 0,
                        status = DownloadStatus.QUEUED,
                        createdAt = System.currentTimeMillis(),
                        errorMessage = null
                    )
                    DownloadManager.enqueue(
                        com.snaptube.clone.data.model.VideoDetails(
                            id = fallback.id,
                            title = fallback.title,
                            author = fallback.author,
                            durationSeconds = 0L,
                            durationFormatted = "--:--",
                            thumbnailUrl = fallback.thumbnailUrl,
                            webpageUrl = source,
                            formats = listOf(fallback.format)
                        ),
                        fallback.format
                    )
                    Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val activeTasks = tasks.filter {
        it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.MERGING ||
            it.status == DownloadStatus.PAUSED ||
            it.status == DownloadStatus.FAILED
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.my_files),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry), tint = SnapYellow)
            }
        }

        TabRow(
            selectedTabIndex = selected,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = SnapYellow,
            indicator = { positions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(positions[selected]),
                    color = SnapYellow,
                    height = 3.dp
                )
            }
        ) {
            FileTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = {
                        val count = when (tab) {
                            FileTab.VIDEOS -> mediaFiles.count { !it.isAudio }
                            FileTab.AUDIO -> mediaFiles.count { it.isAudio }
                            FileTab.ACTIVE -> activeTasks.size
                            FileTab.HISTORY -> tasks.size
                        }
                        Text(
                            text = "${stringResource(tab.labelRes)} ($count)",
                            fontSize = 12.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) SnapYellow
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        when (FileTab.entries[selected]) {
            FileTab.VIDEOS -> FileList(
                files = mediaFiles.filter { !it.isAudio },
                emptyText = stringResource(R.string.empty_videos),
                scanning = scanning,
                onPlay = onPlay,
                onCopyLink = ::copyLink,
                onConvert = ::convertToMp3,
                onDelete = { file ->
                    if (file.task != null) DownloadManager.deleteTask(file.task.id)
                    else deleteMediaFile(context, file.path)
                    refreshKey++
                }
            )

            FileTab.AUDIO -> FileList(
                files = mediaFiles.filter { it.isAudio },
                emptyText = stringResource(R.string.empty_audio),
                scanning = scanning,
                onPlay = onPlay,
                onCopyLink = ::copyLink,
                onConvert = ::convertToMp3,
                onDelete = { file ->
                    if (file.task != null) DownloadManager.deleteTask(file.task.id)
                    else deleteMediaFile(context, file.path)
                    refreshKey++
                }
            )

            FileTab.ACTIVE -> if (activeTasks.isEmpty()) {
                EmptyState(stringResource(R.string.empty_downloads))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(activeTasks, key = { it.id }) { task ->
                        ActiveDownloadRow(task)
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    }
                }
            }

            FileTab.HISTORY -> if (tasks.isEmpty()) {
                EmptyState(stringResource(R.string.history_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tasks.sortedByDescending { it.createdAt }, key = { it.id }) { task ->
                        HistoryRow(
                            task = task,
                            onCopyLink = { copyLink(task) },
                            onRedownload = { reDownload(task) },
                            onDelete = {
                                DownloadManager.deleteTask(task.id)
                                refreshKey++
                            }
                        )
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    }
                }
            }
        }
    }

    convertingTitle?.let { title ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 26.dp, vertical = 20.dp)
            ) {
                CircularProgressIndicator(color = SnapYellow)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.converting),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = title,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(220.dp)
                )
            }
        }
    }
}

private fun buildMediaFiles(context: Context, tasks: List<DownloadTask>): List<MediaFile> {
    val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }
    val byPath = completed.associateBy { it.targetFilePath }
    val seen = mutableSetOf<String>()
    val result = mutableListOf<MediaFile>()

    fun addFile(
        path: String,
        name: String,
        size: Long,
        isAudio: Boolean,
        task: DownloadTask?,
        lastModified: Long
    ) {
        if (path.isBlank() || path in seen) return
        seen += path
        val fileName = when {
            name.contains('.') -> name
            path.substringBefore('?').substringAfterLast('/').contains('.') ->
                path.substringBefore('?').substringAfterLast('/')
            else -> name
        }
        result += MediaFile(
            path = path,
            title = task?.title ?: name,
            fileName = fileName,
            sizeBytes = size,
            isAudio = isAudio,
            thumbnail = task?.thumbnailUrl.orEmpty(),
            task = task
        )
    }

    // Files that the downloader saved directly (including content:// URIs).
    completed.forEach { task ->
        val isAudio = task.format.type == MediaType.AUDIO
        val size = if (DownloadStorage.isContentUri(task.targetFilePath)) {
            DownloadStorage.sizeOf(context, task.targetFilePath).takeIf { it > 0 }
                ?: task.totalBytes
        } else {
            runCatching { File(task.targetFilePath).length() }.getOrDefault(task.totalBytes)
        }
        addFile(
            path = task.targetFilePath,
            name = DownloadStorage.nameOf(context, task.targetFilePath),
            size = size,
            isAudio = isAudio,
            task = task,
            lastModified = task.createdAt
        )
    }

    // Loose files in the app's own downloads folder.
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "downloads")
    runCatching {
        dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { file ->
            val extension = file.extension.lowercase()
            val isAudio = extension in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac", "flac")
            val isVideo = extension in setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v")
            if (isAudio || isVideo) {
                addFile(
                    path = file.absolutePath,
                    name = file.nameWithoutExtension,
                    size = file.length(),
                    isAudio = isAudio,
                    task = byPath[file.absolutePath],
                    lastModified = file.lastModified()
                )
            }
        }
    }

    // Files in the user-selected SAF folder.
    DownloadStorage.listMedia(context).forEach { entry ->
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val isAudio = entry.mimeType.startsWith("audio/") ||
            extension in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac", "flac")
        val isVideo = entry.mimeType.startsWith("video/") ||
            extension in setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v")
        if (isAudio || isVideo) {
            addFile(
                path = entry.uri,
                name = entry.name.substringBeforeLast('.', entry.name),
                size = entry.sizeBytes,
                isAudio = isAudio,
                task = byPath[entry.uri],
                lastModified = entry.lastModified
            )
        }
    }

    return result.sortedByDescending { it.task?.createdAt ?: 0L }
}

@Composable
private fun FileList(
    files: List<MediaFile>,
    emptyText: String,
    scanning: Boolean,
    onPlay: (String, String, Boolean) -> Unit,
    onCopyLink: (DownloadTask?) -> Unit,
    onConvert: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit
) {
    when {
        scanning && files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = SnapYellow)
        }
        files.isEmpty() -> EmptyState(emptyText)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(files, key = { it.path }) { file ->
                MediaFileRow(
                    file = file,
                    onPlay = onPlay,
                    onCopyLink = onCopyLink,
                    onConvert = onConvert,
                    onDelete = onDelete
                )
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }
    }
}

@Composable
private fun MediaFileRow(
    file: MediaFile,
    onPlay: (String, String, Boolean) -> Unit,
    onCopyLink: (DownloadTask?) -> Unit,
    onConvert: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canConvert = file.extension != "mp3" && file.extension !in setOf("srt", "vtt", "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay(file.path, file.title, file.isAudio) }
            .padding(start = 12.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 52.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file.thumbnail.isNotBlank() && !file.isAudio) {
                AsyncImage(
                    model = file.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (file.isAudio) Icons.Default.MusicNote else Icons.Default.Movie,
                    contentDescription = null,
                    tint = SnapYellow,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(readableSize(file.sizeBytes))
                    file.task?.format?.qualityLabel?.let { append(" · $it") }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = { onPlay(file.path, file.title, file.isAudio) },
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SnapYellow)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.play),
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (canConvert) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.convert_to_mp3)) },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = SnapYellow) },
                        onClick = {
                            menuOpen = false
                            onConvert(file)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_link)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = {
                        menuOpen = false
                        onCopyLink(file.task)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = {
                        menuOpen = false
                        onDelete(file)
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    task: DownloadTask,
    onCopyLink: () -> Unit,
    onRedownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 46.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (task.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = task.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.History, contentDescription = null, tint = SnapYellow)
            }
        }
        Spacer(Modifier.width(10.dp))
        val historyStatus = statusLabel(task)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(formatDate(task.createdAt))
                    if (task.format.qualityLabel.isNotBlank()) append(" · ${task.format.qualityLabel}")
                    append(" · ")
                    append(historyStatus)
                },
                fontSize = 10.sp,
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCopyLink, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_link),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onRedownload, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Download,
                contentDescription = stringResource(R.string.redownload),
                tint = SnapYellow,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ActiveDownloadRow(task: DownloadTask) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 52.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (task.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = task.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = SnapYellow)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = task.progressPercent / 100f,
                color = SnapYellow,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(4.dp))
            val mergingLabel = stringResource(R.string.merging)
            val pausedLabel = stringResource(R.string.pause)
            Text(
                text = buildString {
                    append(task.progressPercent)
                    append("%")
                    if (task.format.qualityLabel.isNotBlank()) append(" · ${task.format.qualityLabel}")
                    when (task.status) {
                        DownloadStatus.MERGING -> append(" · $mergingLabel")
                        DownloadStatus.PAUSED -> append(" · $pausedLabel")
                        DownloadStatus.FAILED -> task.errorMessage?.let { append(" · $it") }
                        else -> if (task.readableSpeed.isNotBlank()) append(" · ${task.readableSpeed}")
                    }
                },
                fontSize = 11.sp,
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(6.dp))

        when (task.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.MERGING -> {
                IconButton(onClick = { DownloadManager.pauseTask(task.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = stringResource(R.string.pause),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            else -> {
                IconButton(onClick = { DownloadManager.resumeTask(task.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.resume),
                        tint = SnapYellow
                    )
                }
            }
        }

        IconButton(onClick = { DownloadManager.cancelTask(task.id) }, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

private fun pickReplacementFormat(details: com.snaptube.clone.data.model.VideoDetails, old: FormatOption): FormatOption? {
    val sameType = details.formats.filter { it.type == old.type && it.formatExtension == old.formatExtension }
    sameType.firstOrNull { it.qualityLabel == old.qualityLabel }?.let { return it }
    sameType.maxByOrNull { it.bitrateKbps }?.let { return it }
    details.formats.firstOrNull { it.type == old.type }?.let { return it }
    return details.formats.firstOrNull()
}

private suspend fun convertFileToMp3(context: Context, file: MediaFile): Boolean {
    val timestamp = System.currentTimeMillis()
    val fromContentUri = DownloadStorage.isContentUri(file.path)
    val cachedInput = if (fromContentUri) File(context.cacheDir, "mp3_input_$timestamp") else null
    return runCatching {
        val input: File = if (fromContentUri) {
            val cached = cachedInput!!
            context.contentResolver.openInputStream(Uri.parse(file.path))?.use { source ->
                cached.outputStream().use { target -> source.copyTo(target, 128 * 1024) }
            } ?: throw java.io.IOException("Could not read the source file")
            cached
        } else {
            File(file.path)
        }
        val baseName = file.title.substringBeforeLast('.', file.title)
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_")
            .take(80)
            .ifBlank { "SnapTube_$timestamp" }

        val outputDir = if (!fromContentUri) {
            input.parentFile ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } ?: context.filesDir
        outputDir.mkdirs()
        val output = uniqueFile(outputDir, baseName, "mp3")

        Mp3Transcoder.convert(input, output)

        // Copy into the user's folder first; only drop the local copy once the
        // SAF write definitely succeeded, so the MP3 can never be lost.
        val copied = if (DownloadStorage.isEnabled()) {
            DownloadStorage.copyToTree(context, output, output.name, "audio/mpeg")
        } else {
            null
        }
        if (copied != null) output.delete()
        output.exists() || copied != null
    }.getOrDefault(false).also {
        runCatching { cachedInput?.delete() }
    }
}

private fun uniqueFile(dir: File, baseName: String, extension: String): File {
    var file = File(dir, "$baseName.$extension")
    var counter = 2
    while (file.exists()) {
        file = File(dir, "$baseName ($counter).$extension")
        counter++
    }
    return file
}

private fun deleteMediaFile(context: Context, path: String) {
    if (DownloadStorage.isContentUri(path)) {
        DownloadStorage.delete(context, path)
    } else {
        runCatching { File(path).delete() }
    }
}

private fun readableSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun statusLabel(task: DownloadTask): String = when (task.status) {
    DownloadStatus.COMPLETED -> stringResource(R.string.finished)
    DownloadStatus.FAILED -> stringResource(R.string.download_failed)
    DownloadStatus.PAUSED -> stringResource(R.string.pause)
    DownloadStatus.QUEUED -> stringResource(R.string.downloading)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.downloading)
    DownloadStatus.MERGING -> stringResource(R.string.merging)
}
