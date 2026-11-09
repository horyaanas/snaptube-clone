package com.snaptube.clone.data.extractor

import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Real media extraction built on top of NewPipeExtractor.
 *
 * Supports YouTube, SoundCloud, PeerTube, Bandcamp and MediaCCC through
 * NewPipe, plus a generic OpenGraph/HTML fallback for other sites.
 */
object MediaRepository {

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", NewPipeDownloader.USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val youtubeIdPattern = Pattern.compile(
        "(?<=watch\\?v=|/videos/|embed/|youtu\\.be/|/v/|/e/|shorts/|watch\\?v%3D)[^#&?\\n]*"
    )

    @Volatile
    private var initialized = false

    /** Must be called after NewPipe.init(...) is performed in Application.onCreate(). */
    fun ensureInitialized() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    initialized = true
                }
            }
        }
    }

    fun isSupportedUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return try {
            NewPipe.getServiceByUrl(trimmed)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Search / trending
    // ------------------------------------------------------------------

    suspend fun search(query: String, service: StreamingService = ServiceList.YouTube): Result<List<VideoDetails>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val qh = service.getSearchQHFactory().fromQuery(query, listOf("videos"), "")
                val info = SearchInfo.getInfo(service, qh)
                info.getRelatedItems()
                    .filterIsInstance<StreamInfoItem>()
                    .filter { it.getStreamType() != StreamType.AUDIO_STREAM }
                    .map { it.toVideoDetails(service.getServiceId()) }
            }
        }

    suspend fun searchMusic(query: String): Result<List<VideoDetails>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = ServiceList.YouTube
                val qh = service.getSearchQHFactory().fromQuery(query, listOf("music_songs"), "")
                val info = SearchInfo.getInfo(service, qh)
                info.getRelatedItems()
                    .filterIsInstance<StreamInfoItem>()
                    .map { it.toVideoDetails(service.getServiceId()) }
            }
        }

    suspend fun trending(): Result<List<VideoDetails>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = ServiceList.YouTube
            val kiosk = service.getKioskList().getDefaultKioskExtractor()
            val page = kiosk.getInitialPage()
            page.getItems()
                .filterIsInstance<StreamInfoItem>()
                .map { it.toVideoDetails(service.getServiceId()) }
        }.recoverCatching {
            search("trending videos").getOrThrow()
        }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            ServiceList.YouTube.getSuggestionExtractor().suggestionList(query)
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // Stream extraction
    // ------------------------------------------------------------------

    suspend fun extract(url: String): Result<VideoDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            val service = try {
                NewPipe.getServiceByUrl(trimmed)
            } catch (_: Exception) {
                null
            }
            if (service != null) {
                extractWithNewPipe(service, trimmed)
            } else {
                extractGenericWeb(trimmed)
            }
        }
    }

    private fun extractWithNewPipe(service: StreamingService, url: String): VideoDetails {
        val info = StreamInfo.getInfo(service, url)
        val formats = buildFormats(info)

        return VideoDetails(
            id = info.getId() ?: url.hashCode().toString(),
            title = info.getName().orEmpty().ifBlank { "Untitled" },
            author = info.getUploaderName().orEmpty().ifBlank { service.getServiceInfo().getName() },
            durationSeconds = info.getDuration().coerceAtLeast(0),
            durationFormatted = formatDuration(info.getDuration()),
            thumbnailUrl = bestThumbnail(info.getThumbnails()),
            webpageUrl = url,
            formats = formats,
            viewCountText = formatCount(info.getViewCount()),
            uploadDateText = info.getTextualUploadDate().orEmpty(),
            serviceId = service.getServiceId(),
            description = ""
        )
    }

    private fun buildFormats(info: StreamInfo): List<FormatOption> {
        val duration = info.getDuration().coerceAtLeast(0)
        val formats = mutableListOf<FormatOption>()

        val audioStreams = info.getAudioStreams()
            .filter { it.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && !it.getUrl().isNullOrBlank() }

        // MediaMuxer writes MP4: prefer an M4A audio track so the muxed
        // container is valid even when a higher-bitrate Opus track exists.
        val m4aAudio = audioStreams.filter { it.getFormat() == MediaFormat.M4A }
        val bestAudio = (if (m4aAudio.isNotEmpty()) m4aAudio else audioStreams)
            .maxByOrNull { it.getAverageBitrate() }

        // Audio-only formats
        audioStreams
            .distinctBy { it.getItag() }
            .sortedByDescending { it.getAverageBitrate() }
            .take(6)
            .forEach { audio ->
                val ext = audio.getFormat()?.getSuffix().orEmpty().ifBlank { "m4a" }
                val kbps = if (audio.getAverageBitrate() > 0) audio.getAverageBitrate() / 1000 else 0
                formats += FormatOption(
                    id = "audio_${audio.getItag()}",
                    type = MediaType.AUDIO,
                    qualityLabel = audioLabel(ext, kbps),
                    subtitle = audio.getAudioTrackName().orEmpty().ifBlank { "Audio only" },
                    formatExtension = ext,
                    estimatedSizeBytes = estimateSize(kbps, duration),
                    downloadUrl = audio.getUrl().orEmpty(),
                    bitrateKbps = kbps,
                    container = ext,
                    audioLanguage = audio.getAudioTrackName().orEmpty()
                )
            }

        // Progressive video (already contains audio)
        info.getVideoStreams()
            .filter { it.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && !it.getUrl().isNullOrBlank() }
            .distinctBy { it.getItag() }
            .sortedByDescending { it.getHeight() }
            .forEach { video ->
                val ext = video.getFormat()?.getSuffix().orEmpty().ifBlank { "mp4" }
                val kbps = if (video.getBitrate() > 0) video.getBitrate() / 1000 else 0
                formats += FormatOption(
                    id = "video_progressive_${video.getItag()}",
                    type = MediaType.VIDEO,
                    qualityLabel = videoLabel(video),
                    subtitle = if (video.getHeight() >= 720) "HD" else "SD",
                    formatExtension = ext,
                    estimatedSizeBytes = estimateSize(kbps, duration),
                    downloadUrl = video.getUrl().orEmpty(),
                    isHd = video.getHeight() >= 720,
                    bitrateKbps = kbps,
                    resolution = video.getResolution().orEmpty(),
                    container = ext
                )
            }

        // Video-only streams muxed with the best audio stream.
        info.getVideoOnlyStreams()
            .filter { it.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && !it.getUrl().isNullOrBlank() }
            .filter { it.getFormat()?.getSuffix() == "mp4" }
            .distinctBy { it.getHeight() }
            .sortedByDescending { it.getHeight() }
            .forEach { video ->
                val kbps = if (video.getBitrate() > 0) video.getBitrate() / 1000 else 0
                val videoSize = estimateSize(kbps, duration)
                val audioKbps = bestAudio?.getAverageBitrate()?.let { if (it > 0) it / 1000 else 0 } ?: 0
                formats += FormatOption(
                    id = "video_muxed_${video.getItag()}",
                    type = MediaType.VIDEO,
                    qualityLabel = videoLabel(video),
                    subtitle = if (video.getHeight() >= 720) "HD" else "SD",
                    formatExtension = "mp4",
                    estimatedSizeBytes = videoSize + estimateSize(audioKbps, duration),
                    downloadUrl = video.getUrl().orEmpty(),
                    audioUrl = bestAudio?.getUrl(),
                    isHd = video.getHeight() >= 720,
                    bitrateKbps = kbps,
                    resolution = video.getResolution().orEmpty(),
                    requiresMux = bestAudio != null,
                    container = "mp4",
                    audioLanguage = bestAudio?.getAudioTrackName().orEmpty()
                )
            }

        // Subtitles
        info.getSubtitles()
            .filter { !it.getUrl().isNullOrBlank() }
            .distinctBy { it.getLanguageTag() }
            .forEach { subtitle ->
                formats += FormatOption(
                    id = "subtitle_${subtitle.getLanguageTag()}",
                    type = MediaType.SUBTITLE,
                    qualityLabel = subtitle.getDisplayLanguageName().orEmpty().ifBlank { subtitle.getLanguageTag() },
                    subtitle = if (subtitle.isAutoGenerated()) "auto-generated" else subtitle.getFormat()?.getName().orEmpty(),
                    formatExtension = subtitle.getFormat()?.getSuffix().orEmpty().ifBlank { "vtt" },
                    estimatedSizeBytes = 0L,
                    downloadUrl = subtitle.getUrl().orEmpty(),
                    container = subtitle.getFormat()?.getSuffix().orEmpty().ifBlank { "vtt" }
                )
            }

        return formats
    }

    private fun videoLabel(video: VideoStream): String {
        val height = video.getHeight()
        return when {
            height > 0 -> "${height}P"
            !video.getResolution().isNullOrBlank() -> video.getResolution().uppercase()
            else -> "Video"
        }
    }

    private fun audioLabel(extension: String, kbps: Int): String {
        val name = extension.uppercase()
        return if (kbps > 0) "$name $kbps K" else name
    }

    private fun estimateSize(kbps: Int, durationSeconds: Long): Long {
        if (kbps <= 0 || durationSeconds <= 0) return 0L
        return (kbps * 1000L / 8L) * durationSeconds
    }

    private fun StreamInfoItem.toVideoDetails(serviceId: Int): VideoDetails {
        val thumbnail = bestThumbnail(getThumbnails())
        return VideoDetails(
            id = url.substringAfterLast("v=", url).substringBefore("&").ifBlank { url },
            title = getName().orEmpty().ifBlank { "Untitled" },
            author = getUploaderName().orEmpty().ifBlank { "Unknown" },
            durationSeconds = getDuration().coerceAtLeast(0),
            durationFormatted = formatDuration(getDuration()),
            thumbnailUrl = thumbnail,
            webpageUrl = getUrl(),
            viewCountText = formatCount(getViewCount()),
            uploadDateText = getTextualUploadDate().orEmpty(),
            serviceId = serviceId
        )
    }

    private fun bestThumbnail(images: List<org.schabi.newpipe.extractor.Image>?): String {
        if (images.isNullOrEmpty()) return ""
        return images.maxByOrNull { it.width }?.url ?: images.last().url
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "--:--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    fun formatCount(count: Long): String {
        if (count <= 0) return ""
        return when {
            count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    // ------------------------------------------------------------------
    // Generic website fallback
    // ------------------------------------------------------------------

    private fun extractGenericWeb(url: String): VideoDetails {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val html = response.use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(html, url)

        val title = doc.select("meta[property=og:title]").attr("content").ifEmpty {
            doc.title().ifEmpty { "Web Video" }
        }
        val thumbnail = doc.select("meta[property=og:image]").attr("content")
        val directVideo = doc.select("meta[property=og:video]").attr("content").ifEmpty {
            doc.select("meta[property=og:video:url]").attr("content").ifEmpty {
                doc.select("video source[src]").attr("src").ifEmpty {
                    doc.select("video[src]").attr("src")
                }
            }
        }
        val resolved = if (directVideo.isNotBlank()) {
            try {
                java.net.URI(url).resolve(directVideo).toString()
            } catch (_: Exception) {
                directVideo
            }
        } else {
            ""
        }

        val formats = mutableListOf<FormatOption>()
        if (resolved.isNotBlank()) {
            val extension = resolved.substringAfterLast('.', "mp4").substringBefore('?').ifBlank { "mp4" }
            formats += FormatOption(
                id = "web_direct",
                type = MediaType.VIDEO,
                qualityLabel = "Original",
                subtitle = "Best available source",
                formatExtension = extension,
                estimatedSizeBytes = 0L,
                downloadUrl = resolved,
                isHd = true,
                container = extension
            )
        }

        return VideoDetails(
            id = url.hashCode().toString(),
            title = title,
            author = doc.select("meta[name=author]").attr("content").ifBlank { "Web" },
            durationSeconds = 0,
            durationFormatted = "--:--",
            thumbnailUrl = thumbnail,
            webpageUrl = url,
            formats = formats
        )
    }

    fun youtubeId(url: String): String? {
        val matcher = youtubeIdPattern.matcher(url)
        return if (matcher.find()) matcher.group() else null
    }

    /** Builds a thumbnail URL for a YouTube id without a network call. */
    fun youtubeThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
