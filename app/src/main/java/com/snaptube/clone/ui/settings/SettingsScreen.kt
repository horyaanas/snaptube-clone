package com.snaptube.clone.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.clone.BuildConfig
import com.snaptube.clone.R
import com.snaptube.clone.data.backup.BackupManager
import com.snaptube.clone.data.prefs.AppPreferences
import com.snaptube.clone.data.storage.DownloadStorage
import com.snaptube.clone.ui.theme.SnapYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onThemeChanged: (Int) -> Unit,
    onLanguageChanged: () -> Unit,
    onOpenTool: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(AppPreferences.notificationsEnabled) }
    var themeMode by remember { mutableIntStateOf(AppPreferences.themeMode) }
    var folderVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val ok = runCatching { DownloadStorage.persist(context, uri) }.isSuccess
            folderVersion++
            Toast.makeText(
                context,
                if (ok) R.string.folder_selected else R.string.folder_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    BackupManager.writeToUri(context, uri, BackupManager.exportJson())
                }
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) R.string.backup_done else R.string.backup_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = BackupManager.readFromUri(context, uri)
                    BackupManager.restoreJson(json)
                }
            }
            result.onSuccess { restored ->
                notifications = AppPreferences.notificationsEnabled
                themeMode = AppPreferences.themeMode
                onThemeChanged(AppPreferences.themeMode)
                Toast.makeText(context, R.string.restore_done, Toast.LENGTH_SHORT).show()
                if (restored.languageChanged) onLanguageChanged()
            }.onFailure {
                Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = SnapYellow
                )
                Text(
                    text = "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_general)) {
                SettingsRow(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.settings_download),
                    subtitle = stringResource(R.string.settings_download_dir_desc),
                    onClick = { showDownloadDialog = true }
                )
                SettingsDivider()
                val folderName = remember(folderVersion) { DownloadStorage.displayName(context) }
                SettingsRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.settings_download_folder),
                    subtitle = folderName.ifBlank { stringResource(R.string.app_folder) },
                    onClick = { folderLauncher.launch(null) }
                )
                if (DownloadStorage.isEnabled()) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.reset_folder),
                        onClick = {
                            DownloadStorage.clear()
                            folderVersion++
                            Toast.makeText(context, R.string.folder_reset, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.settings_notifications_desc),
                    checked = notifications,
                    onCheckedChange = {
                        notifications = it
                        AppPreferences.notificationsEnabled = it
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    title = stringResource(R.string.settings_theme),
                    subtitle = when (themeMode) {
                        AppPreferences.THEME_LIGHT -> stringResource(R.string.theme_light)
                        AppPreferences.THEME_DARK -> stringResource(R.string.theme_dark)
                        else -> stringResource(R.string.theme_system)
                    },
                    onClick = { showThemeDialog = true }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_backup)) {
                SettingsRow(
                    icon = Icons.Default.Backup,
                    title = stringResource(R.string.backup),
                    subtitle = stringResource(R.string.backup_desc),
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        backupLauncher.launch("snaptube-backup-$stamp.json")
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Restore,
                    title = stringResource(R.string.restore),
                    subtitle = stringResource(R.string.restore_desc),
                    onClick = {
                        restoreLauncher.launch(
                            arrayOf("application/json", "text/plain", "text/*", "application/octet-stream")
                        )
                    }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_download_tools)) {
                SettingsRow(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.recover_deleted),
                    onClick = { onOpenTool("recover") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Chat,
                    title = stringResource(R.string.whatsapp_status_saver),
                    onClick = { onOpenTool("whatsapp") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.vault_title),
                    onClick = { onOpenTool("vault") }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_phone_clean)) {
                SettingsRow(
                    icon = Icons.Default.CleaningServices,
                    title = stringResource(R.string.cleaner),
                    onClick = { onOpenTool("cleaner") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Speed,
                    title = stringResource(R.string.phone_boost),
                    onClick = { onOpenTool("boost") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.BatterySaver,
                    title = stringResource(R.string.battery_saver),
                    onClick = { onOpenTool("battery") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.large_files),
                    onClick = { onOpenTool("large_files") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.trash),
                    onClick = { onOpenTool("trash") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Chat,
                    title = stringResource(R.string.whatsapp_clean),
                    onClick = { onOpenTool("whatsapp_clean") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = stringResource(R.string.photos),
                    onClick = { onOpenTool("photos") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Apps,
                    title = stringResource(R.string.app_uninstaller),
                    onClick = { onOpenTool("uninstaller") }
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_info)) {
                SettingsRow(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(R.string.settings_account),
                    onClick = { onOpenTool("account") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_language),
                    subtitle = if (AppPreferences.languageTag == "ar") {
                        stringResource(R.string.language_arabic)
                    } else {
                        stringResource(R.string.language_english)
                    },
                    onClick = { showLanguageDialog = true }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Email,
                    title = stringResource(R.string.settings_feedback),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:feedback@snaptube.clone")
                            putExtra(Intent.EXTRA_SUBJECT, "SnapTube feedback")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.settings_share),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "SnapTube — download videos from YouTube and 40+ sites."
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_about),
                    subtitle = "v${BuildConfig.VERSION_NAME}",
                    onClick = { showAboutDialog = true }
                )
            }
        }
    }

    if (showThemeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(
                stringResource(R.string.theme_system) to AppPreferences.THEME_SYSTEM,
                stringResource(R.string.theme_light) to AppPreferences.THEME_LIGHT,
                stringResource(R.string.theme_dark) to AppPreferences.THEME_DARK
            ),
            selected = themeMode,
            onSelect = { mode ->
                themeMode = mode
                AppPreferences.themeMode = mode
                onThemeChanged(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = listOf(
                stringResource(R.string.language_english) to 0,
                stringResource(R.string.language_arabic) to 1
            ),
            selected = if (AppPreferences.languageTag == "ar") 1 else 0,
            onSelect = { choice ->
                AppPreferences.languageTag = if (choice == 1) "ar" else "en"
                showLanguageDialog = false
                onLanguageChanged()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showDownloadDialog) {
        DownloadSettingsDialog(onDismiss = { showDownloadDialog = false })
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.settings_about)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_text))
                    Spacer(Modifier.height(10.dp))
                    Text("${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}")
                    Text("${stringResource(R.string.build_number)} ${BuildConfig.VERSION_CODE}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = SnapYellow
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SnapYellow,
                checkedTrackColor = SnapYellow.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    Divider(
        modifier = Modifier.padding(start = 50.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = { onSelect(value) },
                            colors = RadioButtonDefaults.colors(selectedColor = SnapYellow)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DownloadSettingsDialog(onDismiss: () -> Unit) {
    var wifiOnly by remember { mutableStateOf(AppPreferences.wifiOnly) }
    var threads by remember { mutableIntStateOf(AppPreferences.downloadThreads) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_download)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_wifi_only), fontSize = 14.sp)
                        Text(
                            stringResource(R.string.settings_wifi_only_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            AppPreferences.wifiOnly = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SnapYellow)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                Text(stringResource(R.string.settings_threads), fontSize = 14.sp)
                Text(
                    stringResource(R.string.settings_threads_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..8).forEach { value ->
                        val selected = threads == value
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clickable {
                                    threads = value
                                    AppPreferences.downloadThreads = value
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) SnapYellow
                                else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(
                                    modifier = Modifier.size(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = value.toString(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_download_dir) + ": " +
                        DownloadStorage.displayName(LocalContext.current)
                            .ifBlank { stringResource(R.string.app_folder) },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
