package com.snaptube.clone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.clone.R
import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.SiteShortcut
import com.snaptube.clone.data.model.VideoDetails
import com.snaptube.clone.ui.components.VideoCard
import com.snaptube.clone.ui.theme.SnapYellow

data class SiteShortcutUi(
    val title: String,
    val url: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
fun HomeScreen(
    onNavigateToUrl: (String) -> Unit,
    onVideoClick: (VideoDetails) -> Unit,
    onRequestDownload: (VideoDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("For You", "Trending", "Music", "Gaming", "News")

    val shortcuts = remember {
        listOf(
            SiteShortcutUi("YouTube", "https://m.youtube.com", Icons.Default.SmartDisplay, Color(0xFFFF0000)),
            SiteShortcutUi("Facebook", "https://m.facebook.com", Icons.Default.Language, Color(0xFF1877F2)),
            SiteShortcutUi("Instagram", "https://www.instagram.com", Icons.Default.CameraAlt, Color(0xFFE4405F)),
            SiteShortcutUi("WhatsApp", "snaptube://whatsapp_status", Icons.Default.Chat, Color(0xFF25D366)),
            SiteShortcutUi("TikTok", "https://www.tiktok.com", Icons.Default.MusicNote, Color(0xFF000000)),
            SiteShortcutUi("Vimeo", "https://vimeo.com", Icons.Default.PlayArrow, Color(0xFF1AB7EA))
        )
    }

    // Curated Feed Videos
    val demoVideos = remember {
        listOf(
            VideoDetails(
                id = "dQw4w9WgXcQ",
                title = "Rick Astley - Never Gonna Give You Up (Official Music Video)",
                author = "Rick Astley",
                durationSeconds = 213,
                durationFormatted = "03:33",
                thumbnailUrl = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                webpageUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                formats = createDefaultFormats("dQw4w9WgXcQ")
            ),
            VideoDetails(
                id = "9bZkp7q19f0",
                title = "PSY - GANGNAM STYLE(강남스타일) M/V",
                author = "officialpsy",
                durationSeconds = 253,
                durationFormatted = "04:13",
                thumbnailUrl = "https://img.youtube.com/vi/9bZkp7q19f0/hqdefault.jpg",
                webpageUrl = "https://www.youtube.com/watch?v=9bZkp7q19f0",
                formats = createDefaultFormats("9bZkp7q19f0")
            ),
            VideoDetails(
                id = "kJQP7kiw5Fk",
                title = "Luis Fonsi - Despacito ft. Daddy Yankee",
                author = "Luis Fonsi",
                durationSeconds = 281,
                durationFormatted = "04:41",
                thumbnailUrl = "https://img.youtube.com/vi/kJQP7kiw5Fk/hqdefault.jpg",
                webpageUrl = "https://www.youtube.com/watch?v=kJQP7kiw5Fk",
                formats = createDefaultFormats("kJQP7kiw5Fk")
            )
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        // Speed Dial Sites Row (SnapTube signature grid/row)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.speed_dial),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(shortcuts) { shortcut ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { onNavigateToUrl(shortcut.url) }
                                .padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(shortcut.color),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = shortcut.icon,
                                    contentDescription = shortcut.title,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = shortcut.title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Category Tabs
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = SnapYellow,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = SnapYellow,
                        height = 3.dp
                    )
                },
                divider = { Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) },
                edgePadding = 16.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) SnapYellow else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Feed Video Cards
        items(demoVideos) { video ->
            VideoCard(
                video = video,
                onVideoClick = { onVideoClick(video) },
                onDownloadClick = { onRequestDownload(video) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

private fun createDefaultFormats(id: String): List<FormatOption> = listOf(
    FormatOption("mp3_128k", MediaType.AUDIO, "MP3 128K", "Standard audio", "mp3", 3_500_000L, "https://stream.local/$id.mp3"),
    FormatOption("m4a_128k", MediaType.AUDIO, "M4A 128K", "Best for mobile", "m4a", 3_200_000L, "https://stream.local/$id.m4a"),
    FormatOption("mp4_360p", MediaType.VIDEO, "360P", "Data saver", "mp4", 12_000_000L, "https://stream.local/${id}_360.mp4"),
    FormatOption("mp4_720p", MediaType.VIDEO, "720P HD", "HD quality", "mp4", 35_000_000L, "https://stream.local/${id}_720.mp4", isHd = true),
    FormatOption("mp4_1080p", MediaType.VIDEO, "1080P FHD", "Full HD", "mp4", 68_000_000L, "https://stream.local/${id}_1080.mp4", isHd = true)
)
