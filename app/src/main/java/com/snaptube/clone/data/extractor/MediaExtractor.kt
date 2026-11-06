package com.snaptube.clone.data.extractor

import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object MediaExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extractMedia(url: String): Result<VideoDetails> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            if (isYouTubeUrl(cleanUrl)) {
                extractYouTube(cleanUrl)
            } else {
                extractGenericWeb(cleanUrl)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com") || url.contains("youtu.be")
    }

    private fun extractYouTube(url: String): Result<VideoDetails> {
        val videoId = extractYouTubeId(url) ?: return Result.failure(IllegalArgumentException("Invalid YouTube URL"))

        // Fetch oEmbed for quick verified title, author, and high-res thumbnail
        val oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val request = Request.Builder()
            .url(oEmbedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        var title = "YouTube Video ($videoId)"
        var author = "YouTube Creator"
        if (response.isSuccessful) {
            response.body?.string()?.let { jsonStr ->
                val json = JSONObject(jsonStr)
                title = json.optString("title", title)
                author = json.optString("author_name", author)
            }
        }

        val thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

        // Generate standard SnapTube formats (Audio + Video resolutions)
        val formats = listOf(
            // Music formats
            FormatOption(
                id = "audio_mp3_128k",
                type = MediaType.AUDIO,
                qualityLabel = "MP3 (128K)",
                subtitle = "Standard quality",
                formatExtension = "mp3",
                estimatedSizeBytes = 3_800_000L,
                downloadUrl = "https://stream.snaptube.local/audio/$videoId?q=128k"
            ),
            FormatOption(
                id = "audio_mp3_160k",
                type = MediaType.AUDIO,
                qualityLabel = "MP3 (160K)",
                subtitle = "High quality",
                formatExtension = "mp3",
                estimatedSizeBytes = 4_900_000L,
                downloadUrl = "https://stream.snaptube.local/audio/$videoId?q=160k"
            ),
            FormatOption(
                id = "audio_m4a_128k",
                type = MediaType.AUDIO,
                qualityLabel = "M4A (128K)",
                subtitle = "Best for mobile play",
                formatExtension = "m4a",
                estimatedSizeBytes = 3_500_000L,
                downloadUrl = "https://stream.snaptube.local/audio/$videoId?format=m4a"
            ),
            // Video formats
            FormatOption(
                id = "video_360p",
                type = MediaType.VIDEO,
                qualityLabel = "360P",
                subtitle = "Low data",
                formatExtension = "mp4",
                estimatedSizeBytes = 11_200_000L,
                downloadUrl = "https://stream.snaptube.local/video/$videoId?res=360p"
            ),
            FormatOption(
                id = "video_480p",
                type = MediaType.VIDEO,
                qualityLabel = "480P",
                subtitle = "Standard definition",
                formatExtension = "mp4",
                estimatedSizeBytes = 19_500_000L,
                downloadUrl = "https://stream.snaptube.local/video/$videoId?res=480p"
            ),
            FormatOption(
                id = "video_720p",
                type = MediaType.VIDEO,
                qualityLabel = "720P HD",
                subtitle = "High definition",
                formatExtension = "mp4",
                estimatedSizeBytes = 38_000_000L,
                downloadUrl = "https://stream.snaptube.local/video/$videoId?res=720p",
                isHd = true
            ),
            FormatOption(
                id = "video_1080p",
                type = MediaType.VIDEO,
                qualityLabel = "1080P FHD",
                subtitle = "Full HD clarity",
                formatExtension = "mp4",
                estimatedSizeBytes = 74_000_000L,
                downloadUrl = "https://stream.snaptube.local/video/$videoId?res=1080p",
                isHd = true
            )
        )

        return Result.success(
            VideoDetails(
                id = videoId,
                title = title,
                author = author,
                durationSeconds = 240,
                durationFormatted = "04:00",
                thumbnailUrl = thumbnailUrl,
                webpageUrl = url,
                formats = formats
            )
        )
    }

    private fun extractGenericWeb(url: String): Result<VideoDetails> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: ""
        val doc = Jsoup.parse(html, url)

        val title = doc.select("meta[property=og:title]").attr("content").ifEmpty {
            doc.title().ifEmpty { "Web Video" }
        }
        val thumbnail = doc.select("meta[property=og:image]").attr("content")
        val videoUrl = doc.select("meta[property=og:video]").attr("content").ifEmpty {
            doc.select("video source").attr("src").ifEmpty {
                doc.select("video").attr("src")
            }
        }

        val directStream = if (videoUrl.isNotEmpty()) doc.baseUri() + videoUrl else url

        val formats = listOf(
            FormatOption(
                id = "web_video_original",
                type = MediaType.VIDEO,
                qualityLabel = "Original MP4",
                subtitle = "Best available source",
                formatExtension = "mp4",
                estimatedSizeBytes = 15_000_000L,
                downloadUrl = directStream,
                isHd = true
            ),
            FormatOption(
                id = "web_audio_mp3",
                type = MediaType.AUDIO,
                qualityLabel = "MP3 (128K)",
                subtitle = "Extracted Audio",
                formatExtension = "mp3",
                estimatedSizeBytes = 4_000_000L,
                downloadUrl = directStream
            )
        )

        return Result.success(
            VideoDetails(
                id = url.hashCode().toString(),
                title = title,
                author = doc.select("meta[name=author]").attr("content").ifEmpty { "Web" },
                durationSeconds = 0,
                durationFormatted = "--:--",
                thumbnailUrl = thumbnail,
                webpageUrl = url,
                formats = formats
            )
        )
    }

    private fun extractYouTubeId(url: String): String? {
        val pattern = "(?<=watch\\?v=|/videos/|embed/|youtu.be/|/v/|/e/|watch\\?v%3D|watch\\?feature=player_embedded&v=)[^#&?\\n]*"
        val compiledPattern = Pattern.compile(pattern)
        val matcher = compiledPattern.matcher(url)
        return if (matcher.find()) matcher.group() else null
    }
}
