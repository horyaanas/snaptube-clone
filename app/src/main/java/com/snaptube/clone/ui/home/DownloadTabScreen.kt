package com.snaptube.clone.ui.home

import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.snaptube.clone.R
import com.snaptube.clone.data.extractor.MediaRepository
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.data.prefs.AppPreferences
import com.snaptube.clone.ui.components.VideoCard
import com.snaptube.clone.ui.theme.SnapYellow
import org.json.JSONArray

private enum class HomeTab(val labelRes: Int) {
    SEARCH(R.string.home_search),
    YOUTUBE(R.string.home_youtube),
    MUSIC(R.string.home_music),
    MORE(R.string.home_more)
}

@Composable
fun DownloadTabScreen(
    onSearch: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenVideo: (VideoDetails) -> Unit,
    onDownloadVideo: (VideoDetails) -> Unit,
    onOpenTool: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selected,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = SnapYellow,
            indicator = { positions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(positions[selected]),
                    color = SnapYellow
                )
            }
        ) {
            HomeTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = {
                        Text(
                            text = stringResource(tab.labelRes),
                            fontSize = 14.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) SnapYellow
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        when (HomeTab.entries[selected]) {
            HomeTab.SEARCH -> SearchHomeContent(onSearch = onSearch, onOpenUrl = onOpenUrl)
            HomeTab.YOUTUBE -> FeedContent(
                title = stringResource(R.string.trending),
                musicOnly = false,
                onOpenVideo = onOpenVideo,
                onDownloadVideo = onDownloadVideo
            )
            HomeTab.MUSIC -> FeedContent(
                title = stringResource(R.string.home_music),
                musicOnly = true,
                onOpenVideo = onOpenVideo,
                onDownloadVideo = onDownloadVideo
            )
            HomeTab.MORE -> MoreContent(onOpenUrl = onOpenUrl, onOpenTool = onOpenTool)
        }
    }
}

@Composable
private fun SearchHomeContent(
    onSearch: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var history by remember { mutableStateOf(AppPreferences.searchHistory) }

    fun submit(text: String = query) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val extractedUrl = MediaRepository.extractUrlFromText(trimmed)
        if (extractedUrl != null && MediaRepository.looksLikeUrl(extractedUrl)) {
            onSearch(extractedUrl)
        } else if (MediaRepository.looksLikeUrl(trimmed)) {
            onSearch(trimmed)
        } else {
            AppPreferences.addSearchHistory(trimmed)
            onSearch(trimmed)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.snaptube_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(52.dp)
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_hint), fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = SnapYellow)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    clipboard.getText()?.text?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { pasted ->
                                            query = pasted
                                            submit(pasted)
                                        }
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = stringResource(R.string.paste),
                                    tint = SnapYellow
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SnapYellow,
                        cursorColor = SnapYellow
                    )
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { submit() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = SnapYellow,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search), fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            SocialRow(
                onYouTube = { onOpenUrl("https://m.youtube.com") },
                onInstagram = { onOpenUrl("https://www.instagram.com") },
                onTikTok = { onOpenUrl("https://www.tiktok.com") },
                onFacebook = { onOpenUrl("https://m.facebook.com") },
                onWhatsApp = { onOpenUrl("https://www.whatsapp.com") }
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            SectionTitle(stringResource(R.string.hot_searches))
            HotSearchRow(onSelect = { submit(it) })
            Spacer(Modifier.height(12.dp))
        }

        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.search_history),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.clear_history),
                        fontSize = 13.sp,
                        color = SnapYellow,
                        modifier = Modifier.clickable {
                            AppPreferences.clearSearchHistory()
                            history = emptyList()
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            items(history) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { submit(entry) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = entry,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                AppPreferences.searchHistory =
                                    AppPreferences.searchHistory.filterNot { it == entry }
                                history = AppPreferences.searchHistory
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialRow(
    onYouTube: () -> Unit,
    onInstagram: () -> Unit,
    onTikTok: () -> Unit,
    onFacebook: () -> Unit,
    onWhatsApp: () -> Unit
) {
    val entries = listOf(
        Triple(R.string.home_youtube, R.drawable.ic_youtube_colored, onYouTube),
        Triple("Instagram", R.drawable.ic_instagram_colored, onInstagram),
        Triple("TikTok", R.drawable.ic_tiktok_colored, onTikTok),
        Triple("Facebook", R.drawable.ic_facebook_colored, onFacebook),
        Triple("WhatsApp", R.drawable.ic_whatsapp, onWhatsApp)
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries) { entry ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { entry.third() }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(entry.second),
                        contentDescription = entry.first.toString(),
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                val label = if (entry.first is Int) stringResource(entry.first as Int) else entry.first as String
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun HotSearchRow(onSelect: (String) -> Unit) {
    val hot = listOf(
        "trending music",
        "football highlights",
        "funny videos",
        "movie trailers",
        "Quran recitation",
        "cooking recipes",
        "learn English",
        "tech reviews"
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(hot) { term ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(term) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Whatshot,
                    contentDescription = null,
                    tint = SnapYellow,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(term, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun FeedContent(
    title: String,
    musicOnly: Boolean,
    onOpenVideo: (VideoDetails) -> Unit,
    onDownloadVideo: (VideoDetails) -> Unit
) {
    var feedItems by remember { mutableStateOf<List<VideoDetails>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, musicOnly) {
        loading = true
        errorMessage = null
        val result = if (musicOnly) {
            MediaRepository.searchMusic("trending music")
        } else {
            MediaRepository.trending()
        }
        loading = false
        result.onSuccess { feedItems = it }
            .onFailure { errorMessage = it.message ?: " " }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { refreshKey++ }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = SnapYellow
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SnapYellow)
            }
            errorMessage != null && feedItems.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.error_generic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { refreshKey++ }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            feedItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(feedItems) { video ->
                    VideoCard(
                        video = video,
                        onVideoClick = { onOpenVideo(video) },
                        onDownloadClick = { onDownloadVideo(video) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private data class SiteEntry(val title: String, val url: String, val icon: String)

private fun loadSites(context: Context): List<SiteEntry> {
    return try {
        val json = context.assets.open("full_sites.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title")
                val url = obj.optString("url")
                if (title.isNotBlank() && url.isNotBlank()) {
                    add(SiteEntry(title, url, obj.optString("smallIconUrl")))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun MoreContent(
    onOpenUrl: (String) -> Unit,
    onOpenTool: (String) -> Unit
) {
    val context = LocalContext.current
    val sites = remember { loadSites(context) }

    val tools = listOf(
        Triple(R.string.whatsapp_status_saver, R.drawable.ic_whatsapp, "whatsapp"),
        Triple(R.string.vault_title, R.drawable.ic_lock, "vault"),
        Triple(R.string.cleaner, R.drawable.ic_cleaner, "cleaner"),
        Triple(R.string.phone_boost, R.drawable.ic_boost, "boost"),
        Triple(R.string.battery_saver, R.drawable.ic_battery, "battery"),
        Triple(R.string.large_files, R.drawable.ic_video, "large_files"),
        Triple(R.string.recover_deleted, R.drawable.ic_download, "recover")
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SectionTitle(stringResource(R.string.download_tools))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tools) { tool ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenTool(tool.third) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .width(74.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SnapYellow.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(tool.second),
                            contentDescription = null,
                            tint = SnapYellow,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(tool.first),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.supported_sites))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(sites, key = { it.url + it.title }) { site ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onOpenUrl(site.url) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (site.icon.isNotBlank()) {
                            AsyncImage(
                                model = site.icon,
                                contentDescription = site.title,
                                modifier = Modifier.size(34.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = site.title.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = SnapYellow
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = site.title,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
