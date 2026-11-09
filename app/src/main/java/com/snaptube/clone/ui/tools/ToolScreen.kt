package com.snaptube.clone.ui.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.clone.BuildConfig
import com.snaptube.clone.R
import com.snaptube.clone.ui.theme.SnapYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ToolFile(
    val path: String,
    val title: String,
    val size: Long,
    val isAudio: Boolean,
    val uri: String? = null
)

@Composable
fun ToolScreen(
    tool: String,
    onBack: () -> Unit,
    onPlay: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (tool) {
        "whatsapp" -> stringResource(R.string.whatsapp_status_saver)
        "vault" -> stringResource(R.string.vault_title)
        "cleaner", "whatsapp_clean" -> stringResource(R.string.cleaner)
        "boost" -> stringResource(R.string.phone_boost)
        "battery" -> stringResource(R.string.battery_saver)
        "large_files" -> stringResource(R.string.large_files)
        "trash" -> stringResource(R.string.trash)
        "recover" -> stringResource(R.string.recover_deleted)
        "photos" -> stringResource(R.string.photos)
        "uninstaller" -> stringResource(R.string.app_uninstaller)
        "account" -> stringResource(R.string.settings_account)
        else -> stringResource(R.string.download_tools)
    }

    Column(modifier = modifier.fillMaxSize()) {
        ToolTopBar(title = title, onBack = onBack)
        when (tool) {
            "whatsapp" -> WhatsAppStatusTool(onPlay = onPlay)
            "vault" -> VaultTool(onPlay = onPlay)
            "cleaner", "whatsapp_clean" -> CleanerTool()
            "boost" -> BoostTool()
            "battery" -> BatteryTool()
            "large_files" -> LargeFilesTool(onPlay = onPlay)
            "trash" -> TrashTool(onPlay = onPlay, recover = false)
            "recover" -> TrashTool(onPlay = onPlay, recover = true)
            "photos" -> PhotosTool(onPlay = onPlay)
            "uninstaller" -> UninstallerTool()
            "account" -> AccountTool()
            else -> ToolInfoScreen(stringResource(R.string.tool_coming_soon))
        }
    }
}

@Composable
private fun ToolTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

// ----------------------------------------------------------------------
// WhatsApp status saver
// ----------------------------------------------------------------------

@Composable
private fun WhatsAppStatusTool(onPlay: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<ToolFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        files = withContext(Dispatchers.IO) { scanWhatsAppStatuses(context) }
        loading = false
    }

    if (loading) {
        LoadingBox()
        return
    }

    if (files.isEmpty()) {
        EmptyToolBox(
            message = stringResource(R.string.whatsapp_no_status),
            actionLabel = stringResource(R.string.retry),
            onAction = { refresh++ }
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            ToolFileRow(file = file, onPlay = onPlay)
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

private fun scanWhatsAppStatuses(context: Context): List<ToolFile> {
    val result = mutableListOf<ToolFile>()

    val directDirs = listOf(
        File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
        File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/.Statuses"),
        File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses")
    )
    directDirs.forEach { dir ->
        if (dir.exists() && dir.canRead()) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val ext = file.extension.lowercase()
                    if (ext in setOf("mp4", "3gp", "jpg", "jpeg", "png", "webp")) {
                        result += ToolFile(
                            path = file.absolutePath,
                            title = file.name,
                            size = file.length(),
                            isAudio = false,
                            uri = Uri.fromFile(file).toString()
                        )
                    }
                }
            }
        }
    }
    if (result.isNotEmpty()) return result.sortedByDescending { it.path }

    // MediaStore fallback (Android 10+ scoped storage).
    val collections = listOf(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    )
    collections.forEach { collection ->
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATA
            )
            val selection = "${MediaStore.MediaColumns.DATA} LIKE ? AND " +
                "${MediaStore.MediaColumns.DATA} LIKE ?"
            val args = arrayOf("%com.whatsapp%", "%.Statuses%")
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val data = cursor.getString(dataCol).orEmpty()
                    result += ToolFile(
                        path = data.ifBlank { uri.toString() },
                        title = cursor.getString(nameCol).orEmpty(),
                        size = cursor.getLong(sizeCol),
                        isAudio = false,
                        uri = uri.toString()
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
    return result.distinctBy { it.path }.sortedByDescending { it.title }
}

// ----------------------------------------------------------------------
// Private vault
// ----------------------------------------------------------------------

@Composable
private fun VaultTool(onPlay: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<ToolFile>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    fun vaultDir(): File = File(context.filesDir, "vault").apply { mkdirs() }
    fun reload() {
        val dir = vaultDir()
        files = dir.listFiles()?.filter { it.isFile }?.map {
            ToolFile(
                it.absolutePath,
                it.name,
                it.length(),
                it.extension.lowercase() in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac")
            )
        } ?: emptyList()
    }

    LaunchedEffect(refresh) { reload() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(context, uri) ?: "vault_${System.currentTimeMillis()}"
                    val target = File(vaultDir(), name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.vault_title),
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { importLauncher.launch(arrayOf("video/*", "audio/*", "image/*")) },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SnapYellow, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (files.isEmpty()) {
            EmptyToolBox(message = stringResource(R.string.vault_empty))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { file ->
                    ToolFileRow(
                        file = file,
                        onPlay = onPlay,
                        onDelete = {
                            File(file.path).delete()
                            reload()
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

// ----------------------------------------------------------------------
// Cleaner / boost / battery
// ----------------------------------------------------------------------

@Composable
private fun CleanerTool() {
    val context = LocalContext.current
    var cacheSize by remember { mutableStateOf(0L) }
    var scanning by remember { mutableStateOf(true) }
    var cleaned by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        scanning = true
        cacheSize = withContext(Dispatchers.IO) {
            dirSize(context.cacheDir) + dirSize(context.codeCacheDir) + dirSize(context.externalCacheDir)
        }
        scanning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.CircularProgressIndicator(
            progress = { if (cacheSize > 0) 0.65f else 0f },
            modifier = Modifier.size(140.dp),
            color = SnapYellow,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (scanning) stringResource(R.string.scanning) else readableSize(cacheSize),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.clean_junk),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                context.codeCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                cleaned = true
                refresh++
            },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SnapYellow, contentColor = Color.Black)
        ) {
            Text(stringResource(R.string.clean_now), fontWeight = FontWeight.Bold)
        }
        if (cleaned) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.clean_done),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun BoostTool() {
    val context = LocalContext.current
    var usedPercent by remember { mutableIntStateOf(0) }
    var available by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        total = info.totalMem
        available = info.availMem
        usedPercent = if (info.totalMem > 0) {
            (((info.totalMem - info.availMem) * 100) / info.totalMem).toInt()
        } else 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "$usedPercent%",
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            color = SnapYellow
        )
        Text(
            text = stringResource(R.string.phone_boost),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = usedPercent / 100f,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = SnapYellow,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${readableSize(available)} free of ${readableSize(total)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                Runtime.getRuntime().gc()
                refresh++
            },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SnapYellow, contentColor = Color.Black)
        ) {
            Text(stringResource(R.string.phone_boost), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatteryTool() {
    val context = LocalContext.current
    val intent = remember {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val percent = if (scale > 0) level * 100 / scale else 0
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("$percent%", fontSize = 54.sp, fontWeight = FontWeight.Black, color = SnapYellow)
        Text(
            text = if (charging) "Charging" else "On battery",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = if (percent <= 20) MaterialTheme.colorScheme.error else SnapYellow,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        InfoLine("Temperature", String.format("%.1f °C", temperature))
        InfoLine("Health", healthLabel(health))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.battery_saver),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun healthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    else -> "Unknown"
}

// ----------------------------------------------------------------------
// Large files
// ----------------------------------------------------------------------

@Composable
private fun LargeFilesTool(onPlay: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<ToolFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        files = withContext(Dispatchers.IO) {
            val roots = listOfNotNull(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                context.getExternalFilesDir(null),
                context.filesDir
            )
            val out = mutableListOf<ToolFile>()
            roots.forEach { root ->
                root.walkTopDown().forEach { file ->
                    if (file.isFile && file.length() >= 10L * 1024 * 1024) {
                        val ext = file.extension.lowercase()
                        out += ToolFile(
                            path = file.absolutePath,
                            title = file.name,
                            size = file.length(),
                            isAudio = ext in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac")
                        )
                    }
                }
            }
            out.distinctBy { it.path }.sortedByDescending { it.size }
        }
        loading = false
    }

    if (loading) {
        LoadingBox()
        return
    }
    if (files.isEmpty()) {
        EmptyToolBox(
            message = stringResource(R.string.no_large_files),
            actionLabel = stringResource(R.string.retry),
            onAction = { refresh++ }
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            ToolFileRow(
                file = file,
                onPlay = onPlay,
                onDelete = {
                    File(file.path).delete()
                    refresh++
                }
            )
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

// ----------------------------------------------------------------------
// Trash / recover
// ----------------------------------------------------------------------

@Composable
private fun TrashTool(onPlay: (String, String, Boolean) -> Unit, recover: Boolean) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<ToolFile>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    fun trashDir(): File = File(context.filesDir, "trash").apply { mkdirs() }
    fun reload() {
        files = trashDir().listFiles()?.filter { it.isFile }?.map {
            ToolFile(
                it.absolutePath,
                it.name,
                it.length(),
                it.extension.lowercase() in setOf("m4a", "mp3", "opus", "ogg", "wav", "aac")
            )
        } ?: emptyList()
    }

    LaunchedEffect(refresh) { reload() }

    if (files.isEmpty()) {
        EmptyToolBox(
            message = if (recover) stringResource(R.string.empty_finished)
            else stringResource(R.string.trash) + ": empty"
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            ToolFileRow(
                file = file,
                onPlay = onPlay,
                onDelete = {
                    if (recover) {
                        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        val target = File(downloads ?: context.filesDir, file.title)
                        File(file.path).renameTo(target)
                    } else {
                        File(file.path).delete()
                    }
                    refresh++
                },
                actionIcon = if (recover) Icons.Default.Refresh else Icons.Default.Delete
            )
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

// ----------------------------------------------------------------------
// Photos
// ----------------------------------------------------------------------

@Composable
private fun PhotosTool(onPlay: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<ToolFile>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        files = withContext(Dispatchers.IO) {
            val dirs = listOfNotNull(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            )
            dirs.flatMap { dir ->
                dir.listFiles()?.filter {
                    it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
                }?.map {
                    ToolFile(it.absolutePath, it.name, it.length(), isAudio = false)
                } ?: emptyList()
            }.distinctBy { it.path }
        }
    }

    if (files.isEmpty()) {
        EmptyToolBox(message = stringResource(R.string.no_results))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            ToolFileRow(
                file = file,
                onPlay = { path, title, _ ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(File(path)), "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                },
                onDelete = {
                    File(file.path).delete()
                    refresh++
                }
            )
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

// ----------------------------------------------------------------------
// App uninstaller
// ----------------------------------------------------------------------

@Composable
private fun UninstallerTool() {
    val context = LocalContext.current
    val pm = context.packageManager
    var refresh by remember { mutableIntStateOf(0) }
    val apps = remember(refresh) {
        runCatching {
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { it to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(apps, key = { it.first.packageName }) { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:${pair.first.packageName}")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SnapYellow.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = pair.second.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = SnapYellow
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pair.second,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pair.first.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.delete),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

// ----------------------------------------------------------------------
// Account / info
// ----------------------------------------------------------------------

@Composable
private fun ToolInfoScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun AccountTool() {
    Column(modifier = Modifier.padding(20.dp)) {
        InfoLine(stringResource(R.string.app_name), "SnapTube")
        InfoLine(stringResource(R.string.version), BuildConfig.VERSION_NAME)
        InfoLine(stringResource(R.string.build_number), BuildConfig.VERSION_CODE.toString())
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_text),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ----------------------------------------------------------------------
// Shared widgets / helpers
// ----------------------------------------------------------------------

@Composable
private fun ToolFileRow(
    file: ToolFile,
    onPlay: (String, String, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Delete
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!file.uri.isNullOrBlank() && file.uri.startsWith("content:")) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.parse(file.uri),
                            if (file.isAudio) "audio/*" else "video/*"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                } else {
                    onPlay(file.path, file.title, file.isAudio)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = SnapYellow,
                modifier = Modifier.size(24.dp)
            )
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
            if (file.size > 0) {
                Text(
                    text = readableSize(file.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
                Icon(
                    actionIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SnapYellow)
    }
}

@Composable
private fun EmptyToolBox(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = SnapYellow)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun readableSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}

private fun dirSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
