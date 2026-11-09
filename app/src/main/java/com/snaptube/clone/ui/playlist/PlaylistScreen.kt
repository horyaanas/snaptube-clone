package com.snaptube.clone.ui.playlist

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.PlaylistDetails
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.download.DownloadManager
import com.snaptube.clone.ui.theme.SnapYellow
import kotlinx.coroutines.launch

private enum class BatchMode { VIDEO, AUDIO }

@Composable
fun PlaylistScreen(
    playlist: PlaylistDetails,
    onBack: () -> Unit,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = playlist.items
    var selected by remember(playlist.id) {
        mutableStateOf(items.indices.toSet())
    }
    var mode by remember { mutableStateOf(BatchMode.VIDEO) }
    var maxHeight by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var queued by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var phaseText by remember { mutableStateOf("") }

    fun startBatch() {
        if (running || selected.isEmpty()) return
        running = true
        finished = false
        current = 0
        queued = 0
        scope.launch {
            val chosen = selected.sorted().mapNotNull { items.getOrNull(it) }
            for ((index, item) in chosen.withIndex()) {
                current = index + 1
                phaseText = item.title
                val details = MediaRepository.extract(item.webpageUrl).getOrNull()
                if (details != null) {
                    val format = pickBatchFormat(details, mode, maxHeight)
                    if (format != null) {
                        DownloadManager.enqueue(details, format)
                        queued++
                    }
                }
            }
            running = false
            finished = true
            phaseText = ""
            Toast.makeText(
                context,
                context.getString(R.string.batch_finished, queued),
                Toast.LENGTH_LONG
            ).show()
            if (queued > 0) onQueued()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.playlist),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = playlist.thumbnailUrl,
                        contentDescription = playlist.title,
                        modifier = Modifier
                            .size(width = 110.dp, height = 64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(playlist.author)
                                append(" · ")
                                append(stringResource(R.string.playlist_items, items.size))
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.batch_mode),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == BatchMode.VIDEO,
                            onClick = { mode = BatchMode.VIDEO },
                            label = { Text(stringResource(R.string.download_as_video)) },
                            leadingIcon = {
                                Icon(Icons.Default.Videocam, null, Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SnapYellow.copy(alpha = 0.25f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        FilterChip(
                            selected = mode == BatchMode.AUDIO,
                            onClick = { mode = BatchMode.AUDIO },
                            label = { Text(stringResource(R.string.download_as_music)) },
                            leadingIcon = {
                                Icon(Icons.Default.Audiotrack, null, Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SnapYellow.copy(alpha = 0.25f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    if (mode == BatchMode.VIDEO) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.batch_quality),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            listOf(0 to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
                                .forEach { (height, label) ->
                                    FilterChip(
                                        selected = maxHeight == height,
                                        onClick = { maxHeight = height },
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SnapYellow.copy(alpha = 0.25f)
                                        )
                                    )
                                }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.size == items.size) emptySet()
                                else items.indices.toSet()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.size == items.size && items.isNotEmpty(),
                            onCheckedChange = {
                                selected = if (it) items.indices.toSet() else emptySet()
                            },
                            colors = CheckboxDefaults.colors(checkedColor = SnapYellow)
                        )
                        Text(
                            text = stringResource(R.string.select_all) + " (${selected.size}/${items.size})",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            itemsIndexed(items, key = { index, item -> "${item.webpageUrl}#$index" }) { index, item ->
                val checked = index in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - index else selected + index
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value ->
                            selected = if (value) selected + index else selected - index
                        },
                        colors = CheckboxDefaults.colors(checkedColor = SnapYellow)
                    )
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .size(width = 76.dp, height = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.author} · ${item.durationFormatted}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                )
            }
        }
    }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (running) {
                Text(
                    text = stringResource(R.string.batch_preparing, current, selected.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = if (selected.isNotEmpty()) current.toFloat() / selected.size else 0f,
                    color = SnapYellow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(6.dp))
            } else if (finished) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = SnapYellow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.batch_finished, queued),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Button(
                onClick = { startBatch() },
                enabled = !running && selected.isNotEmpty() && items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SnapYellow,
                    contentColor = Color.Black
                )
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (running) stringResource(R.string.batch_downloading)
                    else stringResource(R.string.download_selected, selected.size),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Chooses the best real format for a batch item. */
private fun pickBatchFormat(
    details: VideoDetails,
    mode: BatchMode,
    maxHeight: Int
): FormatOption? {
    val videos = details.formats.filter { it.type == MediaType.VIDEO }
    val audios = details.formats.filter { it.type == MediaType.AUDIO }

    if (mode == BatchMode.AUDIO) {
        return audios.maxByOrNull { it.bitrateKbps }
            ?: videos.maxByOrNull { heightOf(it) }
    }

    if (videos.isEmpty()) return audios.firstOrNull()
    val withHeight = videos.map { it to heightOf(it) }
    val eligible = if (maxHeight > 0) {
        withHeight.filter { it.second > 0 && it.second <= maxHeight }
    } else {
        withHeight
    }
    val best = eligible.maxByOrNull { it.second }
        ?: withHeight.minByOrNull { it.second }
        ?: withHeight.first()
    return best.first
}

private fun heightOf(format: FormatOption): Int {
    val fromResolution = Regex("(\\d{3,4})").find(format.resolution)?.groupValues?.getOrNull(1)
    val fromLabel = Regex("(\\d{3,4})").find(format.qualityLabel)?.groupValues?.getOrNull(1)
    return (fromResolution ?: fromLabel)?.toIntOrNull() ?: 0
}
