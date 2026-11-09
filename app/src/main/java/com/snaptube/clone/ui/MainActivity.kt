package com.snaptube.clone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.snaptube.clone.R
import com.snaptube.clone.data.extractor.MediaRepository
import com.snaptube.clone.data.model.DownloadTask
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.data.prefs.AppPreferences
import com.snaptube.clone.download.DownloadManager
import com.snaptube.clone.ui.browser.BrowserScreen
import com.snaptube.clone.ui.components.ChooseFormatBottomSheet
import com.snaptube.clone.ui.detail.VideoDetailScreen
import com.snaptube.clone.ui.files.FilesScreen
import com.snaptube.clone.ui.home.DownloadTabScreen
import com.snaptube.clone.ui.player.PlayerScreen
import com.snaptube.clone.ui.search.SearchResultsScreen
import com.snaptube.clone.ui.settings.SettingsScreen
import com.snaptube.clone.ui.theme.SnapTubeTheme
import com.snaptube.clone.ui.theme.SnapYellow
import com.snaptube.clone.ui.theme.resolveDarkTheme
import com.snaptube.clone.ui.tools.ToolScreen
import kotlinx.coroutines.launch

private sealed interface Route {
    data object Main : Route
    data class Search(val query: String) : Route
    data class Detail(val video: VideoDetails) : Route
    data class Player(val path: String, val title: String, val isAudio: Boolean) : Route
    data class Web(val url: String) : Route
    data class Tool(val tool: String) : Route
}

private enum class BottomTab(
    val labelRes: Int,
    val iconRes: Int,
    val filledIconRes: Int
) {
    DOWNLOAD(R.string.tab_download, R.drawable.ic_search, R.drawable.ic_search_filled),
    PLAY(R.string.tab_play, R.drawable.ic_files_play, R.drawable.ic_files_play_filled),
    SETTINGS(R.string.tab_settings, R.drawable.ic_setting_general, R.drawable.ic_setting_general_filled)
}

class MainActivity : AppCompatActivity() {

    private var pendingUrl by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        DownloadManager.init(this)
        pendingUrl = extractSharedUrl(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            var themeMode by remember { mutableIntStateOf(AppPreferences.themeMode) }

            SnapTubeTheme(darkTheme = resolveDarkTheme(themeMode)) {
                MainApp(
                    sharedUrl = pendingUrl,
                    onSharedUrlConsumed = { pendingUrl = null },
                    onThemeChanged = { themeMode = it },
                    onLanguageChanged = { recreate() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUrl = extractSharedUrl(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Regex("https?://\\S+").find(text)?.value
            }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    onThemeChanged: (Int) -> Unit,
    onLanguageChanged: () -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<Route>(Route.Main) }
    var videoForFormat by remember { mutableStateOf<VideoDetails?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var extractingTitle by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun openUrl(url: String) {
        if (url.isBlank()) return
        if (MediaRepository.isSupportedUrl(url)) {
            scope.launch {
                isExtracting = true
                extractingTitle = url
                val result = MediaRepository.extract(url)
                isExtracting = false
                result.onSuccess { route = Route.Detail(it) }
                    .onFailure {
                        Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
                        route = Route.Web(url)
                    }
            }
        } else {
            route = Route.Web(url)
        }
    }

    LaunchedEffect(sharedUrl) {
        sharedUrl?.let {
            openUrl(it)
            onSharedUrlConsumed()
        }
    }

    BackHandler(enabled = route != Route.Main) {
        route = Route.Main
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (route == Route.Main) {
                    SnapBottomBar(
                        selectedIndex = tabIndex,
                        onSelect = { tabIndex = it }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (val current = route) {
                    Route.Main -> when (tabIndex) {
                        0 -> DownloadTabScreen(
                            onSearch = { query ->
                                if (query.startsWith("http://") || query.startsWith("https://")) {
                                    openUrl(query)
                                } else {
                                    route = Route.Search(query)
                                }
                            },
                            onOpenUrl = ::openUrl,
                            onOpenVideo = { video -> openUrl(video.webpageUrl) },
                            onOpenTool = { tool -> route = Route.Tool(tool) }
                        )
                        1 -> FilesScreen(
                            onPlay = { path, title, isAudio ->
                                route = Route.Player(path, title, isAudio)
                            }
                        )
                        else -> SettingsScreen(
                            onThemeChanged = onThemeChanged,
                            onLanguageChanged = onLanguageChanged,
                            onOpenTool = { tool -> route = Route.Tool(tool) }
                        )
                    }

                    is Route.Search -> SearchResultsScreen(
                        query = current.query,
                        onBack = { route = Route.Main },
                        onOpenVideo = { video -> openUrl(video.webpageUrl) },
                        onOpenWeb = { url -> route = Route.Web(url) }
                    )

                    is Route.Detail -> VideoDetailScreen(
                        video = current.video,
                        onBack = { route = Route.Main },
                        onDownload = { videoForFormat = it }
                    )

                    is Route.Player -> PlayerScreen(
                        path = current.path,
                        title = current.title,
                        isAudio = current.isAudio,
                        onBack = { route = Route.Main }
                    )

                    is Route.Web -> BrowserScreen(
                        initialUrl = current.url,
                        onBack = { route = Route.Main },
                        onDownloadDetected = { url -> openUrl(url) }
                    )

                    is Route.Tool -> ToolScreen(
                        tool = current.tool,
                        onBack = { route = Route.Main },
                        onPlay = { path, title, isAudio ->
                            route = Route.Player(path, title, isAudio)
                        }
                    )
                }

                if (isExtracting) {
                    ExtractingOverlay(url = extractingTitle)
                }
            }
        }

        videoForFormat?.let { video ->
            ChooseFormatBottomSheet(
                video = video,
                sheetState = sheetState,
                onDismiss = { videoForFormat = null },
                onFormatSelected = { format ->
                    DownloadManager.enqueue(video, format)
                    Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
                    videoForFormat = null
                    tabIndex = 1
                    route = Route.Main
                }
            )
        }
    }
}

@Composable
private fun SnapBottomBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        BottomTab.entries.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        painter = painterResource(if (selected) tab.filledIconRes else tab.iconRes),
                        contentDescription = stringResource(tab.labelRes),
                        tint = if (selected) SnapYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SnapYellow,
                    selectedTextColor = SnapYellow,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun ExtractingOverlay(url: String) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = SnapYellow)
            Text(
                text = stringResource(R.string.loading),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
