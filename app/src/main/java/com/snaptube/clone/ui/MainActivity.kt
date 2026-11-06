package com.snaptube.clone.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.clone.R
import com.snaptube.clone.data.extractor.MediaExtractor
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.download.DownloadManager
import com.snaptube.clone.ui.browser.BrowserScreen
import com.snaptube.clone.ui.components.ChooseFormatBottomSheet
import com.snaptube.clone.ui.components.TopSearchBar
import com.snaptube.clone.ui.downloads.DownloadsScreen
import com.snaptube.clone.ui.home.HomeScreen
import com.snaptube.clone.ui.me.MeScreen
import com.snaptube.clone.ui.theme.SnapTubeTheme
import com.snaptube.clone.ui.theme.SnapYellow
import com.snaptube.clone.ui.tools.ToolsScreen
import kotlinx.coroutines.launch

enum class AppTab(val titleRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Default.Home),
    BROWSER(R.string.tab_browser, Icons.Default.Language),
    DOWNLOADS(R.string.tab_downloads, Icons.Default.Folder),
    TOOLS(R.string.tab_tools, Icons.Default.Build),
    ME(R.string.tab_me, Icons.Default.Person)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)

        setContent {
            var isDarkTheme by remember { mutableStateOf(true) }

            SnapTubeTheme(darkTheme = isDarkTheme) {
                MainAppScreen(
                    initialSharedUrl = sharedUrl,
                    isDarkTheme = isDarkTheme,
                    onToggleDarkTheme = { isDarkTheme = it }
                )
            }
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    initialSharedUrl: String?,
    isDarkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit
) {
    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    var searchQuery by remember { mutableStateOf("") }
    var browserUrl by remember { mutableStateOf(initialSharedUrl ?: "https://m.youtube.com") }

    var selectedVideoForDownload by remember { mutableStateOf<VideoDetails?>(null) }
    var isExtracting by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Handle shared url if opened via Send intent
    LaunchedEffect(initialSharedUrl) {
        initialSharedUrl?.let { url ->
            isExtracting = true
            val result = MediaExtractor.extractMedia(url)
            isExtracting = false
            result.onSuccess { video ->
                selectedVideoForDownload = video
            }
        }
    }

    fun triggerUrlExtraction(url: String) {
        scope.launch {
            isExtracting = true
            val result = MediaExtractor.extractMedia(url)
            isExtracting = false
            result.onSuccess { video ->
                selectedVideoForDownload = video
            }.onFailure {
                // If direct extraction isn't ready, open in built-in browser
                browserUrl = url
                currentTab = AppTab.BROWSER
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentTab == AppTab.HOME) {
                TopSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { query ->
                        if (query.startsWith("http://") || query.startsWith("https://")) {
                            triggerUrlExtraction(query)
                        } else {
                            browserUrl = "https://m.youtube.com/results?search_query=$query"
                            currentTab = AppTab.BROWSER
                        }
                    },
                    onQuickDownloadClick = {
                        if (searchQuery.isNotEmpty()) {
                            triggerUrlExtraction(searchQuery)
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                tonalElevation = 8.dp
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.titleRes)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SnapYellow,
                            selectedTextColor = SnapYellow,
                            indicatorColor = SnapYellow.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.HOME -> {
                    HomeScreen(
                        onNavigateToUrl = { url ->
                            browserUrl = url
                            currentTab = AppTab.BROWSER
                        },
                        onVideoClick = { video ->
                            browserUrl = video.webpageUrl
                            currentTab = AppTab.BROWSER
                        },
                        onRequestDownload = { video ->
                            selectedVideoForDownload = video
                        }
                    )
                }
                AppTab.BROWSER -> {
                    BrowserScreen(
                        initialUrl = browserUrl,
                        onDownloadDetected = { url ->
                            triggerUrlExtraction(url)
                        }
                    )
                }
                AppTab.DOWNLOADS -> {
                    DownloadsScreen(
                        onPlayTask = { /* Play downloaded media in internal/external player */ }
                    )
                }
                AppTab.TOOLS -> {
                    ToolsScreen(
                        onToolClick = { /* Handle tool navigation */ }
                    )
                }
                AppTab.ME -> {
                    MeScreen(
                        onToggleDarkTheme = onToggleDarkTheme,
                        isDarkTheme = isDarkTheme
                    )
                }
            }

            // Format Selection Modal Bottom Sheet
            if (selectedVideoForDownload != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                ChooseFormatBottomSheet(
                    video = selectedVideoForDownload,
                    sheetState = sheetState,
                    onDismiss = { selectedVideoForDownload = null },
                    onFormatSelected = { format ->
                        selectedVideoForDownload?.let { video ->
                            DownloadManager.enqueueDownload(context, video, format)
                            Toast.makeText(context, "Download started: ${format.qualityLabel}", Toast.LENGTH_SHORT).show()
                        }
                        selectedVideoForDownload = null
                    }
                )
            }
        }
    }
}
