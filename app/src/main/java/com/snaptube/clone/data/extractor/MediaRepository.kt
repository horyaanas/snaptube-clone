package com.snaptube.clone.data.extractor

import android.util.Log
import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.PlaylistDetails
import com.snaptube.clone.data.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Real media extraction built on top of NewPipeExtractor, with an independent
 * YouTube HTML fallback for search/trending when the Innertube API is
 * rate-limited or unreachable.
 *
 * Supports YouTube, SoundCloud, PeerTube, Bandcamp and MediaCCC through
 * NewPipe, TikTok/Instagram/Telegram through [SocialExtractor], plus a
 * generic OpenGraph/HTML fallback for other sites.
 */
object MediaRepository {

    private const val TAG = "MediaRepository"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", NewPipeDownloader.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8,ar-EG;q=0.7")
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

    private val urlInTextPattern = Pattern.compile("https?://[^\\s<>\"']+")

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

    // ------------------------------------------------------------------
    // URL helpers
    // ------------------------------------------------------------------

    /** True when [text] looks like a video/page URL (with or without scheme). */
    fun looksLikeUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return false
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        val known = listOf(
            "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "instagr.am",
            "facebook.com", "fb.watch", "soundcloud.com", "t.me/", "telegram.me",
            "vimeo.com", "dailymotion.com", "x.com/", "twitter.com", "twitch.tv",
            "bandcamp.com", "peertube", "media.ccc.de"
        )
        return lower.startsWith("www.") || known.any { lower.contains(it) }
    }

    /** Pulls the first URL out of arbitrary pasted/shared text. */
    fun extractUrlFromText(text: String): String? {
        val matcher = urlInTextPattern.matcher(text)
        if (!matcher.find()) return null
        return matcher.group().trimEnd('.', ',', '!', '?', ')', ']', '>')
    }

    /**
     * Normalises share/clipboard links: adds a scheme when missing, drops
     * tracking parameters (si, feature, utm_*, …) and unwraps quotes that
     * share sheets sometimes add around the link.
     */
    fun normalizeUrl(raw: String): String {
        var url = raw.trim().trim('"', '\'', '<', '>', '`')
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "https://$url"
        }
        return runCatching {
            val uri = URI(url)
            val query = uri.rawQuery
            if (query.isNullOrBlank() || uri.host == null) return@runCatching url
            val kept = query.split('&').filter { pair ->
                val key = pair.substringBefore('=').lowercase()
                key != "si" && key != "feature" && !key.startsWith("utm_") &&
                    key != "fbclid" && key != "gclid"
            }
            val rebuilt = StringBuilder()
                .append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) rebuilt.append(":").append(uri.port)
            rebuilt.append(uri.path)
            if (kept.isNotEmpty()) rebuilt.append("?").append(kept.joinToString("&"))
            rebuilt.toString()
        }.getOrDefault(url)
    }

    fun isSupportedUrl(url: String): Boolean {
        if (!looksLikeUrl(url)) return false
        val normalized = normalizeUrl(url)
        if (SocialExtractor.isSupported(normalized)) return true
        return try {
            NewPipe.getServiceByUrl(normalized)
            true
        } catch (e: Exception) {
            Log.w(TAG, "getServiceByUrl failed for $normalized", e)
            false
        }
    }

    /** True for YouTube/SoundCloud links that contain a list/set of videos. */
    fun isPlaylistUrl(url: String): Boolean {
        val trimmed = normalizeUrl(url).lowercase()
        if (trimmed.isEmpty()) return false
        val host = hostOf(trimmed).orEmpty()
        val youtubeLike = host.contains("youtube.com") || host == "youtu.be" || host == "youtube-nocookie.com"
        if (youtubeLike) {
            // A watch link with a list parameter still opens the single video.
            return trimmed.contains("/playlist") || (trimmed.contains("list=") && !trimmed.contains("v="))
        }
        val listLike = (host.contains("soundcloud.com") && trimmed.contains("/sets/")) ||
            trimmed.contains("bandcamp.com/album/")
        return listLike && !trimmed.contains("watch?v=")
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()
    }.getOrNull()

    // ------------------------------------------------------------------
    // Search / trending / music
    // ------------------------------------------------------------------

    suspend fun search(query: String, service: StreamingService = ServiceList.YouTube): Result<List<VideoDetails>> =
        withContext(Dispatchers.IO) {
            newPipeSearch(query, listOf("videos"), service)
                .onFailure { Log.w(TAG, "NewPipe search failed, trying HTML fallback", it) }
                .recoverCatching { youtubeHtmlSearch(query).getOrThrow() }
        }

    suspend fun searchMusic(query: String): Result<List<VideoDetails>> =
        withContext(Dispatchers.IO) {
            val merged = mutableListOf<VideoDetails>()
            newPipeSearch(query, listOf("music_songs"), ServiceList.YouTube)
                .onSuccess { merged += it }
                .onFailure { Log.w(TAG, "music_songs search failed", it) }
            newPipeSearch("$query music", listOf("videos"), ServiceList.YouTube)
                .onSuccess { merged += it }
                .onFailure { Log.w(TAG, "music video search failed", it) }
            val deduped = merged.distinctBy { it.id }
            if (deduped.isNotEmpty()) {
                Result.success(deduped)
            } else {
                youtubeHtmlSearch(query)
            }
        }

    /**
     * Trending is assembled from several real searches because the default
     * NewPipe kiosk is the (broken) Live feed. Results are de-duplicated and
     * capped so the tab feels like a curated feed.
     */
    suspend fun trending(): Result<List<VideoDetails>> = withContext(Dispatchers.IO) {
        val queries = listOf(
            "trending music",
            "viral videos",
            "أغاني رائجة",
            "trending now"
        )
        val merged = mutableListOf<VideoDetails>()
        for (query in queries) {
            search(query).onSuccess { merged += it }
            if (merged.size >= 20) break
        }
        val deduped = merged.distinctBy { it.id }.take(30)
        if (deduped.isNotEmpty()) Result.success(deduped)
        else youtubeHtmlSearch("trending")
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            ServiceList.YouTube.getSuggestionExtractor().suggestionList(query)
        }.getOrDefault(emptyList())
    }

    private fun newPipeSearch(
        query: String,
        filters: List<String>,
        service: StreamingService
    ): Result<List<VideoDetails>> = runCatching {
        val qh = service.getSearchQHFactory().fromQuery(query, filters, "")
        val info = SearchInfo.getInfo(service, qh)
        info.getRelatedItems()
            .filterIsInstance<StreamInfoItem>()
            .filter { it.getStreamType() != StreamType.AUDIO_STREAM }
            .map { it.toVideoDetails(service.getServiceId()) }
            .filter { it.webpageUrl.isNotBlank() && it.thumbnailUrl.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // YouTube HTML search fallback (independent of the Innertube API)
    // ------------------------------------------------------------------

    /** Parses m.youtube.com search results (ytInitialData JSON) into videos. */
    private fun youtubeHtmlSearch(query: String): Result<List<VideoDetails>> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = client.newCall(
            Request.Builder()
                .url("https://m.youtube.com/results?search_query=$encoded")
                .build()
        ).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val data = parseInitialData(html)
            ?: throw IllegalStateException("Could not find search data on the page")

        val renderers = mutableListOf<JSONObject>()
        collectRenderers(data, setOf("videoWithContextRenderer", "videoRenderer"), renderers)

        renderers.mapNotNull { renderer ->
            val videoId = renderer.optString("videoId")
                .ifBlank { renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty() }
            if (videoId.isBlank() || videoId.length != 11) return@mapNotNull null
            val title = runText(renderer.optJSONObject("headline"))
                ?: runText(renderer.optJSONObject("title"))
                ?: return@mapNotNull null
            val thumbs = run {
                val thumb = renderer.optJSONObject("thumbnail")
                val arr = thumb?.optJSONArray("thumbnails") ?: return@run null
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url") }
                    .takeIf { it.isNotEmpty() }?.last()
            }
            val author = runText(renderer.optJSONObject("shortBylineText"))
                ?: renderer.optJSONObject("owner")?.optString("channelName")
                ?: runText(renderer.optJSONObject("longBylineText"))
                ?: "YouTube"
            val durationText = renderer.optString("lengthText").ifBlank {
                renderer.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
            }.let { if (it.isNotBlank()) it else renderer.optString("lengthText") }
            val viewText = renderer.optString("shortViewCountText").ifBlank {
                renderer.optJSONObject("shortViewCountText")?.optString("simpleText").orEmpty()
            }.ifBlank {
                renderer.optString("viewCountText").ifBlank {
                    renderer.optJSONObject("viewCountText")?.optString("simpleText").orEmpty()
                }
            }
            val uploadText = renderer.optString("publishedTimeText").ifBlank {
                renderer.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty()
            }
            VideoDetails(
                id = videoId,
                title = title,
                author = author,
                durationSeconds = parseDuration(durationText),
                durationFormatted = if (durationText.isNotBlank()) durationText else "--:--",
                thumbnailUrl = thumbs ?: youtubeThumbnail(videoId),
                webpageUrl = "https://www.youtube.com/watch?v=$videoId",
                viewCountText = viewText,
                uploadDateText = uploadText
            )
        }.distinctBy { it.id }.take(24)
            .takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No videos found on the page")
    }

    /** Extracts `ytInitialData` whether it is a raw JSON object or an escaped JS string. */
    private fun parseInitialData(html: String): JSONObject? {
        // Form 1: ytInitialData = { ... };  (raw JSON object)
        val rawMarker = "ytInitialData = {"
        val rawIndex = html.indexOf(rawMarker)
        if (rawIndex >= 0) {
            val start = html.indexOf('{', rawIndex)
            val jsonText = balancedJson(html, start)
            if (jsonText != null) {
                runCatching { return JSONObject(jsonText) }
            }
        }
        // Form 2: ytInitialData = '<escaped>';  (mobile pages inline it as a JS string)
        val marker = "ytInitialData = "
        var searchFrom = 0
        while (true) {
            val index = html.indexOf(marker, searchFrom)
            if (index < 0) return null
            val quoteIndex = html.indexOf('\'', index + marker.length)
            if (quoteIndex < 0) return null
            val endQuote = closingQuote(html, quoteIndex)
            if (endQuote < 0) return null
            val escaped = html.substring(quoteIndex + 1, endQuote)
            val decoded = unescapeJsString(escaped)
            runCatching {
                val obj = JSONObject(decoded)
                if (obj.has("contents") || obj.has("tabbedSearchResults")) return obj
            }
            searchFrom = endQuote + 1
        }
    }

    private fun balancedJson(text: String, start: Int): String? {
        if (start < 0 || start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun closingQuote(text: String, start: Int): Int {
        var escaped = false
        for (i in start + 1 until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '\'') return i
        }
        return -1
    }

    /** Decodes the `\\xNN` / `\\uNNNN` escapes used in mobile ytInitialData strings. */
    private fun unescapeJsString(escaped: String): String {
        val out = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch == '\\' && i + 1 < escaped.length) {
                when (val next = escaped[i + 1]) {
                    'x' -> {
                        if (i + 3 < escaped.length) {
                            val hex = escaped.substring(i + 2, i + 4)
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 2
                        }
                    }
                    'u' -> {
                        if (i + 5 < escaped.length) {
                            val hex = escaped.substring(i + 2, i + 6)
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 4
                        }
                    }
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun collectRenderers(node: Any?, keys: Set<String>, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val names = node.keys()
                while (names.hasNext()) {
                    val key = names.next()
                    val value = node.opt(key)
                    if (key in keys && value is JSONObject) out += value
                    collectRenderers(value, keys, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectRenderers(node.opt(i), keys, out)
                }
            }
        }
    }

    private fun runText(node: JSONObject?): String? {
        if (node == null) return null
        val direct = node.optString("simpleText")
        if (direct.isNotBlank()) return direct
        val runs = node.optJSONArray("runs") ?: return null
        val joined = buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        return joined.takeIf { it.isNotBlank() }
    }

    private fun parseDuration(text: String): Long {
        val parts = text.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    // ------------------------------------------------------------------
    // Stream extraction
    // ------------------------------------------------------------------

    suspend fun extract(url: String): Result<VideoDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = normalizeUrl(url.trim())
            check(looksLikeUrl(trimmed)) { "Unsupported link" }
            if (SocialExtractor.isSupported(trimmed)) {
                return@runCatching SocialExtractor.extract(trimmed)
            }
            val service = try {
                NewPipe.getServiceByUrl(trimmed)
            } catch (e: Exception) {
                Log.w(TAG, "No NewPipe service for $trimmed", e)
                null
            }
            if (service != null) {
                extractWithNewPipe(service, trimmed)
            } else {
                extractGenericWeb(trimmed)
            }
        }.onFailure { Log.w(TAG, "Extraction failed for $url", it) }
    }

    /** Extracts a real playlist and all of its first-page items. */
    suspend fun extractPlaylist(url: String): Result<PlaylistDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = normalizeUrl(url.trim())
            val service = NewPipe.getServiceByUrl(trimmed)
            val info = PlaylistInfo.getInfo(service, trimmed)
            val items = info.getRelatedItems()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.getStreamType() != StreamType.AUDIO_STREAM }
                .map { it.toVideoDetails(service.getServiceId()) }
            PlaylistDetails(
                id = info.getId() ?: trimmed.hashCode().toString(),
                title = info.getName().orEmpty().ifBlank { "Playlist" },
                author = info.getUploaderName().orEmpty()
                    .ifBlank { service.getServiceInfo().getName() },
                thumbnailUrl = bestThumbnail(info.getThumbnails()),
                webpageUrl = trimmed,
                items = items,
                streamCount = info.getStreamCount()
            )
        }.onFailure { Log.w(TAG, "Playlist extraction failed for $url", it) }
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
