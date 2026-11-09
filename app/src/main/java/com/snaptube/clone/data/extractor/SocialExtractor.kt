package com.snaptube.clone.data.extractor

import com.snaptube.clone.data.model.FormatOption
import com.snaptube.clone.data.model.MediaType
import com.snaptube.clone.data.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Extractors for sites that NewPipe does not support: TikTok, Instagram and
 * Telegram. They parse the public page / embed payloads and always return the
 * real CDN URL that the site itself hands to a browser.
 */
object SocialExtractor {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
                .build()
            chain.proceed(request)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isSupported(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com") ||
            host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "instagr.am" || host.endsWith(".instagr.am") ||
            host == "t.me" || host == "telegram.me" || host.endsWith(".telegram.me")
    }

    suspend fun extract(url: String): VideoDetails = withContext(Dispatchers.IO) {
        val host = hostOf(url) ?: throw IllegalArgumentException("Unsupported link")
        when {
            host.contains("tiktok") -> extractTikTok(url)
            host.contains("instagram") || host.contains("instagr") -> extractInstagram(url)
            host.contains("t.me") || host.contains("telegram") -> extractTelegram(url)
            else -> throw IllegalArgumentException("Unsupported link")
        }
    }

    // ------------------------------------------------------------------
    // TikTok
    // ------------------------------------------------------------------

    private fun extractTikTok(url: String): VideoDetails {
        val response = get(url)
        val finalUrl = response.first
        val html = response.second
        val doc = Jsoup.parse(html, finalUrl)

        val json = doc.select("script#__UNIVERSAL_DATA_FOR_REHYDRATION__").firstOrNull()
            ?.data()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: doc.select("script[type=application/json]")
                .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
                .firstOrNull { findObject(it, "itemStruct") != null }

        val item = json?.let { findObject(it, "itemStruct") }
        val video = item?.optJSONObject("video")
        val author = item?.optJSONObject("author")
        val music = item?.optJSONObject("music")

        val playUrl = firstNonBlank(
            video?.optString("downloadAddr"),
            video?.optString("playAddr"),
            video?.optString("play_addr"),
            doc.select("meta[property=og:video]").attr("content"),
            doc.select("meta[property=og:video:url]").attr("content")
        )
        val cover = firstNonBlank(
            video?.optString("cover"),
            video?.optString("originCover"),
            video?.optString("dynamicCover"),
            doc.select("meta[property=og:image]").attr("content")
        )
        val title = firstNonBlank(
            item?.optString("desc"),
            doc.select("meta[property=og:title]").attr("content"),
            doc.title(),
            "TikTok video"
        )
        val authorName = firstNonBlank(
            author?.optString("nickname"),
            author?.optString("uniqueId"),
            doc.select("meta[name=author]").attr("content"),
            "TikTok"
        )
        val durationSeconds = ((video?.optLong("duration", 0L) ?: 0L)).coerceAtLeast(0L)

        val formats = mutableListOf<FormatOption>()
        if (playUrl.isNotBlank()) {
            val resolved = resolve(finalUrl, playUrl)
            formats += FormatOption(
                id = "tiktok_video",
                type = MediaType.VIDEO,
                qualityLabel = if (video?.optString("definition").isNullOrBlank()) "Original"
                else video?.optString("definition").orEmpty().uppercase(),
                subtitle = "No watermark",
                formatExtension = extensionOf(resolved, "mp4"),
                estimatedSizeBytes = video?.optLong("size", 0L) ?: 0L,
                downloadUrl = resolved,
                isHd = true,
                resolution = video?.optString("definition").orEmpty(),
                container = "mp4"
            )
        }
        val musicUrl = music?.optString("playUrl").orEmpty()
        if (musicUrl.isNotBlank()) {
            val resolved = resolve(finalUrl, musicUrl)
            formats += FormatOption(
                id = "tiktok_audio",
                type = MediaType.AUDIO,
                qualityLabel = "MP3",
                subtitle = firstNonBlank(music?.optString("title"), "Original sound"),
                formatExtension = extensionOf(resolved, "mp3"),
                estimatedSizeBytes = 0L,
                downloadUrl = resolved,
                container = "mp3"
            )
        }
        if (formats.isEmpty()) {
            // Last chance: some regional pages only expose og:video.
            val ogVideo = doc.select("meta[property=og:video]").attr("content")
            if (ogVideo.isNotBlank()) {
                formats += FormatOption(
                    id = "tiktok_video",
                    type = MediaType.VIDEO,
                    qualityLabel = "Original",
                    formatExtension = "mp4",
                    estimatedSizeBytes = 0L,
                    downloadUrl = resolve(finalUrl, ogVideo),
                    isHd = true,
                    container = "mp4"
                )
            }
        }
        if (formats.isEmpty()) throw IllegalStateException("Could not find a downloadable TikTok video")

        return VideoDetails(
            id = finalUrl.hashCode().toString(),
            title = title,
            author = authorName,
            durationSeconds = durationSeconds,
            durationFormatted = MediaRepository.formatDuration(durationSeconds),
            thumbnailUrl = cover,
            webpageUrl = finalUrl,
            formats = formats,
            description = item?.optString("desc").orEmpty()
        )
    }

    // ------------------------------------------------------------------
    // Instagram
    // ------------------------------------------------------------------

    private fun extractInstagram(url: String): VideoDetails {
        val (finalUrl, html) = get(url)
        val doc = Jsoup.parse(html, finalUrl)

        var videoUrl = firstNonBlank(
            doc.select("meta[property=og:video]").attr("content"),
            doc.select("meta[property=og:video:secure_url]").attr("content"),
            doc.select("meta[property=og:video:url]").attr("content")
        )
        if (videoUrl.isBlank()) {
            videoUrl = regexFirst(html, "\"video_url\"\\s*:\\s*\"([^\"]+)\"")
        }
        if (videoUrl.isBlank()) {
            // Public GraphQL endpoint still works for many reels.
            val apiUrl = "https://www.instagram.com/p/${shortcode(finalUrl)}/?__a=1&__d=dis"
            val api = runCatching { get(apiUrl).second }.getOrDefault("")
            videoUrl = regexFirst(api, "\"video_url\"\\s*:\\s*\"([^\"]+)\"")
        }
        if (videoUrl.isBlank()) {
            videoUrl = regexFirst(html, "<meta[^>]+content=\"([^\"]+\\.mp4[^\"]*)\"")
        }
        if (videoUrl.isBlank()) throw IllegalStateException("This Instagram post has no video")

        val thumbnail = firstNonBlank(
            doc.select("meta[property=og:image]").attr("content"),
            regexFirst(html, "\"display_url\"\\s*:\\s*\"([^\"]+)\"")
        )
        val title = firstNonBlank(
            doc.select("meta[property=og:title]").attr("content"),
            doc.select("meta[name=description]").attr("content"),
            "Instagram video"
        )
        val author = firstNonBlank(
            regexFirst(html, "\"owner\"\\s*:\\s*\\{[^}]*\"username\"\\s*:\\s*\"([^\"]+)\""),
            doc.select("meta[property=og:title]").attr("content").substringBefore(" on Instagram"),
            "Instagram"
        )
        val resolved = resolve(finalUrl, unescapeUrl(videoUrl))

        return VideoDetails(
            id = shortcode(finalUrl).ifBlank { finalUrl.hashCode().toString() },
            title = title,
            author = author,
            durationSeconds = 0L,
            durationFormatted = "--:--",
            thumbnailUrl = unescapeUrl(thumbnail),
            webpageUrl = finalUrl,
            formats = listOf(
                FormatOption(
                    id = "instagram_video",
                    type = MediaType.VIDEO,
                    qualityLabel = "Original",
                    subtitle = "Instagram video",
                    formatExtension = extensionOf(resolved, "mp4"),
                    estimatedSizeBytes = 0L,
                    downloadUrl = resolved,
                    isHd = true,
                    container = "mp4"
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // Telegram
    // ------------------------------------------------------------------

    private fun extractTelegram(url: String): VideoDetails {
        val cleanUrl = url.substringBefore("?")
        val (finalUrl, html) = get("$cleanUrl?embed=1")
        val doc = Jsoup.parse(html, finalUrl)

        var videoUrl = firstNonBlank(
            doc.select("video[src]").attr("src"),
            doc.select("video source[src]").attr("src"),
            doc.select("meta[property=og:video]").attr("content"),
            doc.select("meta[property=og:video:url]").attr("content")
        )
        if (videoUrl.isBlank()) {
            videoUrl = regexFirst(html, "\"video_url\"\\s*:\\s*\"([^\"]+)\"")
        }
        if (videoUrl.isBlank()) {
            // Document attachments (video files) are linked directly.
            videoUrl = doc.select("a.tgme_widget_message_document").attr("href")
        }
        if (videoUrl.isBlank()) throw IllegalStateException("This Telegram post has no video")

        val title = firstNonBlank(
            doc.select("meta[property=og:title]").attr("content"),
            doc.select(".tgme_widget_message_author_name").text(),
            "Telegram video"
        )
        val author = firstNonBlank(
            doc.select(".tgme_widget_message_author_name").text(),
            finalUrl.substringAfter("t.me/").substringBefore('/'),
            "Telegram"
        )
        val thumbnail = firstNonBlank(
            doc.select("meta[property=og:image]").attr("content"),
            doc.select("video[poster]").attr("poster")
        )
        val resolved = resolve(finalUrl, unescapeUrl(videoUrl))

        return VideoDetails(
            id = finalUrl.hashCode().toString(),
            title = title,
            author = author,
            durationSeconds = 0L,
            durationFormatted = "--:--",
            thumbnailUrl = unescapeUrl(thumbnail),
            webpageUrl = finalUrl.substringBefore("?embed=1"),
            formats = listOf(
                FormatOption(
                    id = "telegram_video",
                    type = MediaType.VIDEO,
                    qualityLabel = "Original",
                    subtitle = "Telegram",
                    formatExtension = extensionOf(resolved, "mp4"),
                    estimatedSizeBytes = 0L,
                    downloadUrl = resolved,
                    isHd = true,
                    container = "mp4"
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun get(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code !in 300..399) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val finalUrl = response.request.url.toString()
            return finalUrl to body
        }
    }

    private fun findObject(root: Any?, key: String, depth: Int = 0): JSONObject? {
        if (root == null || depth > 24) return null
        when (root) {
            is JSONObject -> {
                val direct = root.optJSONObject(key)
                if (direct != null) return direct
                val keys = root.keys()
                while (keys.hasNext()) {
                    val child = root.opt(keys.next())
                    findObject(child, key, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findObject(root.opt(i), key, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun regexFirst(input: String, pattern: String): String =
        runCatching { Regex(pattern).find(input)?.groupValues?.getOrNull(1).orEmpty() }
            .getOrDefault("")

    private fun unescapeUrl(value: String): String = value
        .replace("\\u0026", "&")
        .replace("\\u003D", "=")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun resolve(base: String, value: String): String {
        if (value.isBlank()) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun extensionOf(url: String, fallback: String): String {
        val clean = url.substringBefore('?').substringAfterLast('.', "")
        return clean.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: fallback
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()
    }.getOrNull()

    private fun shortcode(url: String): String {
        val parts = url.substringBefore('?').trimEnd('/').split('/')
        return when {
            parts.size >= 2 && (url.contains("/reel/") || url.contains("/p/") || url.contains("/tv/")) ->
                parts.last()
            else -> parts.lastOrNull().orEmpty()
        }
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}
